package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.data.BannerSlide;
import io.pacod.cms.core.exceptions.MediaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filesystem side, against a temporary directory.
 *
 * <p>Same shape as {@code BannerCreativeServiceTest}: {@code @TempDir} plus
 * {@link ReflectionTestUtils} for the one {@code @Value} field, so nothing here needs a Spring
 * context and nothing writes to the shared content volume.
 */
class BannerLayoutStorageTest {

    // The banner root sits one level inside the temporary directory, not at it: backups go to a
    // *sibling* of the root, and a sibling of the @TempDir itself is the shared system temp
    // directory, where a stale folder from an earlier run makes backsUpBeforeAdoption fail.
    @TempDir
    Path tmp;

    Path folder;

    private BannerLayoutStorage storage;
    private BannerSkeleton skeleton;

    @BeforeEach
    void setUp() throws IOException {
        folder = Files.createDirectories(tmp.resolve("banners"));
        storage = new BannerLayoutStorage();
        ReflectionTestUtils.setField(storage, "bannerFolder", folder.toString());

        skeleton = new BannerSkeleton();
        skeleton.setId("sidebar");
        skeleton.setHtmlHead("<html><head><link rel=stylesheet href=\"css/banner.css\"></head><body>\n");
        skeleton.setContainerTag("<div class=\"banner-container-inline\"");
        skeleton.setHtmlTail("</div>\n<script src=\"js/banner.js\"></script><script>banner();</script></body></html>");
        skeleton.setCss(".banner-container-inline { overflow: hidden; }\n");
        skeleton.setGtmId("GTM-TEST1234");
    }

    private BannerLayout layout(String id) {
        BannerLayout layout = new BannerLayout(id);
        layout.setSkeleton("sidebar");
        layout.setTitle(id);
        return layout;
    }

    private BannerSlide slide(String image, String href, String title, String alt) {
        BannerSlide slide = new BannerSlide(image);
        slide.setHref(href);
        slide.setTitle(title);
        slide.setAlt(alt);
        return slide;
    }

    // ------------------------------------------------------------------------------ generating

    @Test
    @DisplayName("writing a layout produces the three generated files and the rotation interval")
    void writesTheGeneratedFiles() throws IOException {

        BannerLayout layout = layout("acme");
        layout.setRotationMs(7500);
        layout.getSlides().add(slide("a.webp", "https://example.com/a", "A", "alt A"));

        storage.write(layout, skeleton);

        Path index = folder.resolve("acme/index.html");
        assertThat(index).exists();
        assertThat(folder.resolve("acme/css/banner.css")).exists();
        assertThat(folder.resolve("acme/js/banner.js")).exists();

        String html = Files.readString(index);
        assertThat(html).contains("data-speed=\"7500\"");
        assertThat(html).contains("<img src=\"a.webp\"");
        // The vendored rotator, not the skeleton's script: rotation belongs to the console.
        assertThat(Files.readString(folder.resolve("acme/js/banner.js")))
                .contains("data-speed");
    }

    @Test
    @DisplayName("only the first slide is visible; the rest inherit display:none from the markup")
    void hidesEveryFrameButTheFirst() {

        BannerLayout layout = layout("acme");
        layout.getSlides().add(slide("a.webp", "https://example.com/a", "A", null));
        layout.getSlides().add(slide("b.webp", "https://example.com/b", "B", null));

        String html = storage.renderIndex(layout, skeleton);

        assertThat(html).contains("<div id=\"banner-1\"><a id=\"a-banner-1\"");
        assertThat(html).contains("<div id=\"banner-2\" style=\"display: none\"");
        assertThat(html).doesNotContain("<div id=\"banner-1\" style=\"display: none\"");
    }

    @Test
    @DisplayName("a quotation mark in an advertiser's tooltip cannot break out of the attribute")
    void escapesOperatorText() {

        BannerLayout layout = layout("acme");
        layout.getSlides().add(slide("a.webp", "https://example.com/a",
                "Глобекс \"Продукт\" & Co", "<b>alt</b>"));

        String html = storage.renderIndex(layout, skeleton);

        assertThat(html).contains("title=\"Глобекс &quot;Продукт&quot; &amp; Co\"");
        assertThat(html).contains("alt=\"&lt;b&gt;alt&lt;/b&gt;\"");
        // The escaping must not have produced a stray tag anywhere.
        assertThat(html).doesNotContain("<b>alt</b>");
    }

    @Test
    @DisplayName("the GTM token is replaced by the layout's own container id")
    void substitutesTheGtmId() {
        skeleton.setHtmlHead("<html><head><link rel=stylesheet href=\"css/banner.css\">"
                + "<script>id='" + BannerSkeletonParser.GTM_TOKEN + "'</script></head><body>");

        BannerLayout layout = layout("acme");
        layout.setGtmId("GTM-OWN9999");

        assertThat(storage.renderIndex(layout, skeleton)).contains("GTM-OWN9999");
        assertThat(storage.renderIndex(layout, skeleton)).doesNotContain(BannerSkeletonParser.GTM_TOKEN);
    }

    @Test
    @DisplayName("dimensions are appended after the skeleton's stylesheet, never substituted into it")
    void appendsDimensions() {

        BannerLayout fluid = layout("top");
        assertThat(storage.renderCss(fluid, skeleton)).isEqualTo(skeleton.getCss());

        BannerLayout sized = layout("square");
        sized.setWidth(300);
        sized.setHeight(250);

        String css = storage.renderCss(sized, skeleton);
        // The skeleton's own rule survives untouched, and the override follows it.
        assertThat(css).startsWith(skeleton.getCss());
        assertThat(css).contains("width: 300px;");
        assertThat(css).contains("max-height: 250px;");
        assertThat(css.indexOf("max-height: 250px")).isGreaterThan(css.indexOf("overflow: hidden"));
    }

    // -------------------------------------------------------------------------------- uploads

    @Test
    @DisplayName("a slide image lands inside the layout's folder under a sanitised name")
    void storesSlideImages() throws MediaException {

        String stored = storage.storeSlideImage("acme",
                new MockMultipartFile("file", "../ре кла ма.webp", "image/webp", new byte[]{1, 2, 3}));

        assertThat(stored).doesNotContain("/").doesNotContain("..");
        assertThat(folder.resolve("acme").resolve(stored)).exists();
    }

    @Test
    @DisplayName("a second upload of the same name does not overwrite the first slide")
    void doesNotOverwriteAnotherSlide() throws MediaException {

        String first = storage.storeSlideImage("acme",
                new MockMultipartFile("file", "banner.webp", "image/webp", new byte[]{1}));
        String second = storage.storeSlideImage("acme",
                new MockMultipartFile("file", "banner.webp", "image/webp", new byte[]{2}));

        assertThat(second).isNotEqualTo(first);
        assertThat(folder.resolve("acme").resolve(first)).exists();
        assertThat(folder.resolve("acme").resolve(second)).exists();
    }

    @Test
    @DisplayName("the article image rules do not apply, but the banner ones do")
    void appliesTheBannerUploadRules() {

        // 300 KB would be refused by ImageService and is fine here -- the same trade
        // BannerCreativeService makes, and the reason the constants are shared rather than copied.
        assertThatThrownBy(() -> storage.storeSlideImage("x",
                new MockMultipartFile("file", "a.svg", "image/svg+xml", new byte[]{1})))
                .isInstanceOf(MediaException.class);

        byte[] tooBig = new byte[(int) BannerCreativeService.MAX_BANNER_BYTES + 1];
        assertThatThrownBy(() -> storage.storeSlideImage("x",
                new MockMultipartFile("file", "a.webp", "image/webp", tooBig)))
                .isInstanceOf(MediaException.class);
    }

    // --------------------------------------------------------------------------------- import

    private MockMultipartFile zip(String... namesAndBodies) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndBodies[i]));
                zip.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "x.zip", "application/zip", out.toByteArray());
    }

    @Test
    @DisplayName("a zipped folder is flattened by one level, so the entry file lands at the root")
    void stripsTheSharedLeadingDirectory() throws IOException {

        BannerLayoutStorage.Imported imported = storage.importArchive("globex",
                zip("banner-top-template/index.html", "<html></html>",
                        "banner-top-template/css/banner.css", ".x{}"));

        assertThat(imported.entryFile()).isEqualTo("index.html");
        assertThat(folder.resolve("globex/index.html")).exists();
        assertThat(folder.resolve("globex/css/banner.css")).exists();
        assertThat(folder.resolve("globex/banner-top-template")).doesNotExist();
    }

    @Test
    @DisplayName("a folder with no index.html but one html answers at that file")
    void findsTheSingleEntryFile() throws IOException {

        // An animation tool's export: no index.html, one HTML file named after the creative.
        BannerLayoutStorage.Imported imported = storage.importArchive("sample",
                zip("Globex_300_250.html", "<html></html>", "Globex_300_250.js", "//x"));

        assertThat(imported.entryFile()).isEqualTo("Globex_300_250.html");
    }

    @Test
    @DisplayName("several html files with no index are refused, naming the candidates")
    void refusesAnAmbiguousEntryFile() throws IOException {
        MockMultipartFile archive = zip("a.html", "<html></html>", "b.html", "<html></html>");

        assertThatThrownBy(() -> storage.importArchive("x", archive))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("a.html")
                .hasMessageContaining("b.html");
    }

    @Test
    @DisplayName("an archive with no html at the root is refused")
    void refusesAnArchiveWithNoEntryFile() throws IOException {
        MockMultipartFile archive = zip("css/banner.css", ".x{}");

        assertThatThrownBy(() -> storage.importArchive("x", archive))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("HTML");
    }

    @Test
    @DisplayName("zip-slip is refused, and nothing outside the root is written")
    void refusesZipSlip() throws IOException {
        MockMultipartFile archive = zip("index.html", "<html></html>",
                "../escaped.html", "<html></html>");

        assertThatThrownBy(() -> storage.importArchive("x", archive))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("outside the folder");

        assertThat(folder.resolve("escaped.html")).doesNotExist();
        assertThat(folder.getParent().resolve("escaped.html")).doesNotExist();
    }

    @Test
    @DisplayName("an absolute entry path is refused too")
    void refusesAbsoluteEntries() throws IOException {
        MockMultipartFile archive = zip("index.html", "<html></html>", "/etc/passwd.txt", "x");

        assertThatThrownBy(() -> storage.importArchive("x", archive))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("a file type with no business in a creative is refused by name")
    void refusesForeignExtensions() throws IOException {
        MockMultipartFile archive = zip("index.html", "<html></html>", "shell.php", "<?php ?>");

        assertThatThrownBy(() -> storage.importArchive("x", archive))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("shell.php");
    }

    @Test
    @DisplayName("too many entries is refused before anything is written")
    void refusesTooManyEntries() throws IOException {
        String[] entries = new String[(BannerLayoutStorage.MAX_ZIP_ENTRIES + 2) * 2];
        for (int i = 0; i < entries.length; i += 2) {
            entries[i] = "f" + i + ".txt";
            entries[i + 1] = "x";
        }

        MockMultipartFile archive = zip(entries);
        assertThatThrownBy(() -> storage.importArchive("x", archive))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining(String.valueOf(BannerLayoutStorage.MAX_ZIP_ENTRIES));

        assertThat(folder.resolve("x")).doesNotExist();
    }

    @Test
    @DisplayName("archiver metadata is skipped rather than refused")
    void skipsArchiverNoise() throws IOException {
        BannerLayoutStorage.Imported imported = storage.importArchive("x",
                zip("index.html", "<html></html>", "__MACOSX/._index.html", "junk",
                        ".DS_Store", "junk"));

        assertThat(imported.files()).isEqualTo(1);
        assertThat(folder.resolve("x/index.html")).exists();
    }

    // --------------------------------------------------------------------------- stat, delete

    @Test
    @DisplayName("stat reports an absent folder as a fact rather than throwing")
    void statNeverThrows() {
        BannerLayoutStorage.LayoutFiles files = storage.stat("never-created");

        assertThat(files.present()).isFalse();
        assertThat(files.files()).isZero();
        assertThat(files.names()).isEmpty();
    }

    @Test
    @DisplayName("stat lists every file with its relative path")
    void statListsFiles() {
        BannerLayout layout = layout("acme");
        storage.write(layout, skeleton);

        BannerLayoutStorage.LayoutFiles files = storage.stat("acme");

        assertThat(files.present()).isTrue();
        assertThat(files.readable()).isTrue();
        assertThat(files.names()).contains("index.html", "css/banner.css", "js/banner.js");
        assertThat(files.bytes()).isPositive();
        assertThat(files.modifiedOn()).isNotNull();
    }

    @Test
    @DisplayName("deleting a layout removes its whole tree and nothing beside it")
    void deletesTheFolder() {
        storage.write(layout("acme"), skeleton);
        storage.write(layout("globex"), skeleton);

        storage.deleteFolder("acme");

        assertThat(folder.resolve("acme")).doesNotExist();
        assertThat(folder.resolve("globex/index.html")).exists();
    }

    @Test
    @DisplayName("a layout id that resolves outside the root is refused, not walked")
    void refusesToLeaveTheRoot() {
        // The id comes from a document, and a document can be written by hand in mongosh.
        assertThatThrownBy(() -> storage.deleteFolder("../.."))
                .isInstanceOf(MgmtOperationException.class);

        assertThatThrownBy(() -> storage.deleteFolder("."))
                .isInstanceOf(MgmtOperationException.class);

        assertThat(storage.stat("../..").present()).isFalse();
    }

    @Test
    @DisplayName("deleting an absent folder is not an error")
    void toleratesAnAbsentFolder() {
        storage.deleteFolder("never-created");
        assertThat(List.of()).isEmpty();
    }

    // ------------------------------------------------------------------- discovery, files, backup

    @Test
    @DisplayName("folders with no document are discovered, and their entry file worked out")
    void discoversOrphanFolders() throws IOException {
        storage.importArchive("acme2", zip("index.html", "<html></html>", "css/banner.css", ".x{}"));
        storage.importArchive("sample", zip("Globex_300_250.html", "<html></html>"));
        // A folder with nothing to open cannot be created through import, which refuses one --
        // but a volume can hold such folders, so discovery has to cope with them.
        Files.createDirectories(folder.resolve("no-html"));
        Files.writeString(folder.resolve("no-html/readme.txt"), "x");

        List<BannerLayoutStorage.Orphan> orphans = storage.orphanFolders(Set.of("acme2"));

        // The one already described by a document is not offered again.
        assertThat(orphans).extracting(BannerLayoutStorage.Orphan::id)
                .containsExactlyInAnyOrder("sample", "no-html");
        assertThat(orphans).filteredOn(o -> o.id().equals("sample")).first()
                .satisfies(o -> {
                    assertThat(o.entryFile()).isEqualTo("Globex_300_250.html");
                    assertThat(o.adoptable()).isTrue();
                });
        // A folder with nothing to open is listed but cannot be adopted -- the screen says so
        // rather than the console guessing a file.
        assertThat(orphans).filteredOn(o -> o.id().equals("no-html")).first()
                .satisfies(o -> assertThat(o.adoptable()).isFalse());
    }

    @Test
    @DisplayName("a single creative file in the same tree is not mistaken for a layout")
    void ignoresLooseFiles() throws IOException {
        // BannerCreativeService writes `{id}-{file}.gif` into this very directory.
        Files.write(folder.resolve("12-leaderboard.gif"), new byte[]{1});

        assertThat(storage.orphanFolders(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("a backup is taken outside the served tree, and never overwritten")
    void backsUpBeforeAdoption() throws IOException {
        storage.importArchive("acme2", zip("index.html", "<html>ORIGINAL</html>"));

        storage.backup("acme2");

        Path copy = folder.resolveSibling("banner-backups").resolve("acme2/index.html");
        assertThat(copy).exists();
        assertThat(Files.readString(copy)).contains("ORIGINAL");
        // Outside the banner root, so the site never publishes it and discovery never offers it.
        assertThat(storage.orphanFolders(Set.of())).extracting(BannerLayoutStorage.Orphan::id)
                .containsExactly("acme2");

        // A second adoption must not overwrite what the advertiser originally supplied.
        Files.writeString(folder.resolve("acme2/index.html"), "<html>REWRITTEN</html>");
        storage.backup("acme2");
        assertThat(Files.readString(copy)).contains("ORIGINAL");
    }

    @Test
    @DisplayName("the file list marks what can be edited and what can be swapped")
    void listsFilesWithWhatCanBeDoneToThem() throws IOException {
        storage.importArchive("sample", zip(
                "Globex_300_250.html", "<html></html>",
                "Globex_300_250.js", "var clickTag = \"https://old.example\";",
                "images/logo.png", "binary",
                "fonts/x.woff2", "binary"));

        List<BannerLayoutStorage.LayoutFile> files = storage.listFiles("sample");

        assertThat(files).extracting(BannerLayoutStorage.LayoutFile::path)
                .contains("Globex_300_250.js", "images/logo.png", "fonts/x.woff2");
        assertThat(files).filteredOn(f -> f.path().endsWith(".js")).first()
                .satisfies(f -> assertThat(f.text()).isTrue());
        assertThat(files).filteredOn(f -> f.path().endsWith(".png")).first()
                .satisfies(f -> {
                    assertThat(f.image()).isTrue();
                    assertThat(f.text()).isFalse();
                });
        // A font belongs in the folder but is neither editable as text nor swappable as an image;
        // offering either would corrupt it silently.
        assertThat(files).filteredOn(f -> f.path().endsWith(".woff2")).first()
                .satisfies(f -> assertThat(f.text() || f.image()).isFalse());
    }

    @Test
    @DisplayName("editing a text file rewrites it, which is how a clickTag URL changes")
    void editsATextFile() throws IOException {
        storage.importArchive("sample", zip(
                "index.html", "<html></html>",
                "banner.js", "var clickTag = \"https://old.example\";"));

        storage.writeTextFile("sample", "banner.js", "var clickTag = \"https://new.example\";");

        assertThat(Files.readString(folder.resolve("sample/banner.js"))).contains("new.example");
    }

    @Test
    @DisplayName("the editor refuses a binary, a path outside the folder and an absent file")
    void guardsTheEditor() throws IOException {
        storage.importArchive("sample", zip("index.html", "<html></html>", "images/logo.png", "b"));

        assertThatThrownBy(() -> storage.writeTextFile("sample", "images/logo.png", "x"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("is not a text file");

        // Containment is against the LAYOUT's folder, not merely the banner root: this normalises
        // to a path still inside the tree, and would name a neighbouring booking's creative.
        assertThatThrownBy(() -> storage.writeTextFile("sample", "../escaped.html", "x"))
                .isInstanceOf(MgmtOperationException.class);

        storage.importArchive("neighbour", zip("index.html", "<html>NEIGHBOUR</html>"));
        assertThatThrownBy(() -> storage.writeTextFile("sample", "../neighbour/index.html", "HIJACKED"))
                .isInstanceOf(MgmtOperationException.class);
        assertThat(Files.readString(folder.resolve("neighbour/index.html"))).contains("NEIGHBOUR");

        assertThatThrownBy(() -> storage.writeTextFile("sample", "nothing.js", "x"))
                .isInstanceOf(MgmtNotFoundException.class);

        assertThat(folder.resolve("escaped.html")).doesNotExist();
    }

    @Test
    @DisplayName("a replaced file keeps its exact name, because a script names it")
    void replacesAFileInPlace() throws IOException, MediaException {
        storage.importArchive("sample", zip("index.html", "<html></html>", "images/logo.png", "old"));

        storage.replaceFile("sample", "images/logo.png",
                new MockMultipartFile("file", "something-else.png", "image/png", new byte[]{9, 9}));

        // The name the export's own JavaScript refers to, not the uploaded one.
        assertThat(folder.resolve("sample/images/logo.png")).exists();
        assertThat(folder.resolve("sample/images/something-else.png")).doesNotExist();
        assertThat(Files.readAllBytes(folder.resolve("sample/images/logo.png"))).containsExactly(9, 9);
    }

    @Test
    @DisplayName("a replacement of a different kind is refused: the site would serve the wrong type")
    void refusesAKindMismatch() throws IOException {
        storage.importArchive("sample", zip("index.html", "<html></html>", "images/logo.png", "old"));

        assertThatThrownBy(() -> storage.replaceFile("sample", "images/logo.png",
                new MockMultipartFile("file", "logo.webp", "image/webp", new byte[]{1})))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("same kind");

        assertThatThrownBy(() -> storage.replaceFile("sample", "index.html",
                new MockMultipartFile("file", "index.html", "text/html", new byte[]{1})))
                .isInstanceOf(MediaException.class);
    }
}
