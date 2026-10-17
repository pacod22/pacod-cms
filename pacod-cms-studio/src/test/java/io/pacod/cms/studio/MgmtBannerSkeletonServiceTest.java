package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.repository.BannerSkeletonRepository;
import io.pacod.cms.studio.form.BannerSkeletonForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Skeleton administration: reading a folder in, and the two refusals that keep the list honest.
 */
class MgmtBannerSkeletonServiceTest {

    private BannerSkeletonRepository repository;
    private MgmtBannerLayoutService layoutService;
    private MgmtBannerSkeletonService service;

    private static final String GOOD_HTML =
            "<html><head><link rel=\"stylesheet\" href=\"css/banner.css\"></head><body>"
                    + "<div class=\"banner-container-inline\"></div>"
                    + "<script src=\"js/banner.js\"></script><script>banner();</script></body></html>";

    @BeforeEach
    void setUp() {
        repository = mock(BannerSkeletonRepository.class);
        layoutService = mock(MgmtBannerLayoutService.class);
        service = new MgmtBannerSkeletonService(repository, new BannerSkeletonParser(), layoutService);

        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

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

    private BannerSkeleton stored(String id, boolean builtIn) {
        BannerSkeleton skeleton = new BannerSkeleton();
        skeleton.setId(id);
        skeleton.setTitle(id);
        skeleton.setBuiltIn(builtIn);
        when(repository.findById(id)).thenReturn(Optional.of(skeleton));
        return skeleton;
    }

    @Test
    @DisplayName("a zipped folder is read even though every entry sits one level down")
    void readsAZippedFolder() throws IOException {
        // What an operator actually uploads: `zip -r x.zip banner-top-template` puts a shared
        // leading directory in front of every entry.
        BannerSkeletonForm form = service.readArchive(zip(
                "banner-top-template/index.html", GOOD_HTML,
                "banner-top-template/css/banner.css", ".banner-img { height: 100% }",
                "banner-top-template/js/banner.js", "function banner(){}",
                "banner-top-template/globex-1.webp", "binary"));

        assertThat(form.getHtml()).contains("banner-container-inline");
        assertThat(form.getCss()).contains(".banner-img");
        // The images and the script are not a skeleton's business; see BannerSkeleton's javadoc.
        assertThat(form.getId()).isNull();
        assertThat(form.getTitle()).isNull();
    }

    @Test
    @DisplayName("the shallowest copy wins, so a backup folder inside the archive cannot shadow it")
    void prefersTheShallowestCopy() throws IOException {
        // A banner folder collects `old/` and `backup/` copies; taking whichever the archive
        // happens to list first would pick between them by accident.
        BannerSkeletonForm form = service.readArchive(zip(
                "old/index.html", "<html>THE OLD ONE</html>",
                "old/css/banner.css", ".old {}",
                "index.html", GOOD_HTML,
                "css/banner.css", ".current {}"));

        assertThat(form.getHtml()).doesNotContain("THE OLD ONE");
        assertThat(form.getCss()).contains(".current");
    }

    @Test
    @DisplayName("an archive missing either file is refused by name")
    void refusesAnIncompleteArchive() throws IOException {
        assertThatThrownBy(() -> service.readArchive(zip("css/banner.css", ".x{}")))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("index.html");

        assertThatThrownBy(() -> service.readArchive(zip("index.html", GOOD_HTML)))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("banner.css");
    }

    @Test
    @DisplayName("saving splits the markup and lifts the GTM id out of it, never off a field")
    void splitsOnSave() {
        stored("sidebar", true);

        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setTitle("Странично");
        form.setWidth(300);
        form.setHeight(250);
        form.setCss(".x {}");
        form.setHtml(GOOD_HTML.replace("<body>", "<body><!-- GTM-ABCD1234 -->"));

        BannerSkeleton saved = service.save("sidebar", form);

        assertThat(saved.getGtmId()).isEqualTo("GTM-ABCD1234");
        assertThat(saved.getHtmlHead()).contains(BannerSkeletonParser.GTM_TOKEN);
        assertThat(saved.getContainerTag()).isEqualTo("<div class=\"banner-container-inline\"");
        assertThat(saved.getHtmlTail()).startsWith("</div>");
    }

    @Test
    @DisplayName("markup the generator could not drive is refused before it is stored")
    void refusesUnusableMarkup() {
        stored("sidebar", true);

        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setTitle("Странично");
        form.setCss(".x {}");
        form.setHtml("<html><body><div class=\"other\"></div></body></html>");

        assertThatThrownBy(() -> service.save("sidebar", form))
                .isInstanceOf(MgmtOperationException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a built-in is refused deletion outright; the seed would never bring it back")
    void refusesToDeleteABuiltIn() {
        stored("sidebar", true);

        assertThatThrownBy(() -> service.delete("sidebar"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("built-in");

        verify(repository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("a skeleton still carrying layouts is refused; nothing cascades")
    void refusesToDeleteASkeletonInUse() {
        stored("custom", false);
        when(layoutService.countUsingSkeleton("custom")).thenReturn(3L);

        assertThatThrownBy(() -> service.delete("custom"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("3");

        verify(repository, never()).deleteById(anyString());

        when(layoutService.countUsingSkeleton("custom")).thenReturn(0L);
        service.delete("custom");
        verify(repository).deleteById("custom");
    }

    @Test
    @DisplayName("assembling and re-parsing a skeleton is lossless, GTM id included")
    void roundTripsThroughTheEditor() {
        stored("sidebar", true);

        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setTitle("Странично");
        form.setCss(".x {}");
        form.setHtml(GOOD_HTML.replace("<body>", "<body><!-- GTM-ABCD1234 -->"));
        BannerSkeleton saved = service.save("sidebar", form);

        // What the edit screen shows is what a save would accept back. Without the token being
        // substituted out, the round trip would drop the container id silently.
        String shown = service.sourceOf(saved);
        assertThat(shown).contains("GTM-ABCD1234").doesNotContain(BannerSkeletonParser.GTM_TOKEN);

        BannerSkeletonForm again = new BannerSkeletonForm();
        again.setTitle("Странично");
        again.setCss(".x {}");
        again.setHtml(shown);
        assertThat(service.save("sidebar", again).getGtmId()).isEqualTo("GTM-ABCD1234");
    }

    @Test
    @DisplayName("a layout id is not a skeleton id: the picker's ids are validated on create")
    void refusesUnusableIds() {
        when(repository.existsById(anyString())).thenReturn(false);

        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setTitle("x");
        form.setCss(".x {}");
        form.setHtml(GOOD_HTML);

        for (String bad : new String[]{"", "a", "2026-top", "With Space", "с-кирилица"}) {
            form.setId(bad);
            assertThatThrownBy(() -> service.create(form))
                    .as("should refuse %s", bad)
                    .isInstanceOf(MgmtOperationException.class);
        }

        form.setId("Sidebar-Wide");
        assertThat(service.create(form).getId()).isEqualTo("sidebar-wide");
        assertThat(service.create(form).isBuiltIn()).isFalse();
    }

    @Test
    @DisplayName("the layout document is never touched by a skeleton save")
    void doesNotReachIntoLayouts() {
        stored("sidebar", true);

        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setTitle("Странично");
        form.setCss(".x {}");
        form.setHtml(GOOD_HTML);
        service.save("sidebar", form);

        // Editing a skeleton does not regenerate the folders built on it -- they are rewritten on
        // their own next save, which is what the console tells the operator.
        verify(layoutService, never()).save(anyString(), any());
        assertThat(new BannerLayout("x").isBuilt()).isTrue();
    }

    // -------------------------------------------------------------------- deriving from a folder

    @Test
    @DisplayName("markup with no container returns null rather than throwing: that decides IMPORTED")
    void returnsNullForAnAnimationExport() {
        // The branch every advertiser export takes. A thrown exception here would break adoption of
        // all of them, and the caller has no way to tell "not skeleton-shaped" from "broken".
        String export = "<html><body><canvas id=\"canvas\"></canvas>"
                + "<script>var clickTag=\"https://x\";</script></body></html>";

        assertThat(service.adoptFrom("Sample_300_250", export, null)).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an identical chrome reuses the existing skeleton instead of deriving a second")
    void reusesAMatchingSkeleton() {
        BannerSkeleton seeded = stored("sidebar", true);
        BannerSkeletonParser.Split split = new BannerSkeletonParser().parse(GOOD_HTML);
        seeded.setHtmlHead(split.htmlHead());
        seeded.setContainerTag(split.containerTag());
        seeded.setHtmlTail(split.htmlTail());
        seeded.setCss(".x {}");
        when(repository.findAll()).thenReturn(List.of(seeded));

        assertThat(service.adoptFrom("acme2", GOOD_HTML, ".x {}")).isSameAs(seeded);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("two blank lines do not make a second skeleton")
    void matchesAcrossWhitespace() {
        // Not hypothetical: a hand-copied folder can differ from the template it was copied from
        // by exactly this and nothing else. Under byte equality every such folder would derive its
        // own skeleton, which is the noise dedupe exists to stop.
        // The template as it ships: a newline and two spaces between the container and the script.
        String template = GOOD_HTML.replace("</div><script", "</div>\n  <script");
        // The same folder after hand-copying: two blank lines more, and nothing else.
        String copied = GOOD_HTML.replace("</div><script", "</div>\n\n\n  <script");

        BannerSkeleton seeded = stored("sidebar", true);
        BannerSkeletonParser.Split split = new BannerSkeletonParser().parse(template);
        seeded.setHtmlHead(split.htmlHead());
        seeded.setContainerTag(split.containerTag());
        seeded.setHtmlTail(split.htmlTail());
        seeded.setCss(".x {}");
        when(repository.findAll()).thenReturn(List.of(seeded));

        assertThat(service.adoptFrom("acme2", copied, ".x  {}\n")).isSameAs(seeded);
        verify(repository, never()).save(any());

        // Collapsing runs is not the same as deleting whitespace: markup with none where the
        // template has some is a different look, and is allowed to derive its own.
        when(repository.existsById(anyString())).thenReturn(false);
        assertThat(service.adoptFrom("other", GOOD_HTML, ".x {}")).isNotSameAs(seeded);
    }

    @Test
    @DisplayName("a genuinely different look derives its own, with an id the create rules would accept")
    void derivesAnIdFromTheFolderName() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.existsById(anyString())).thenReturn(false);

        assertThat(service.adoptFrom("Globex_300_250", GOOD_HTML, ".x {}").getId())
                .isEqualTo("globex-300-250");

        // A folder name of the kind an export leaves: non-ASCII, digits first, far too long. The
        // derived id still has to satisfy the pattern `create` enforces, because nothing on disk
        // is named after it.
        String derived = service.adoptFrom("2031_ACME_webBanner_300х250px_DJ_SpringOffer_10rot",
                GOOD_HTML, ".x {}").getId();
        assertThat(derived).matches("[a-z][a-z0-9-]{1,39}");

        assertThat(service.adoptFrom("---", GOOD_HTML, ".x {}").getId()).startsWith("skeleton-");
    }

    @Test
    @DisplayName("a derived id already taken gets a suffix rather than colliding")
    void suffixesAClashingDerivedId() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.existsById("acme2")).thenReturn(true);
        when(repository.existsById("acme2-2")).thenReturn(false);

        assertThat(service.adoptFrom("acme2", GOOD_HTML, ".x {}").getId()).isEqualTo("acme2-2");
    }

    @Test
    @DisplayName("a derived skeleton is not built-in, so it can be deleted once nothing uses it")
    void derivedSkeletonsAreNotBuiltIn() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.existsById(anyString())).thenReturn(false);

        BannerSkeleton derived = service.adoptFrom("acme2", GOOD_HTML, ".x {}");

        assertThat(derived.isBuiltIn()).isFalse();
        assertThat(derived.getTitle()).contains("acme2");
    }
}
