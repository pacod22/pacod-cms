package io.pacod.cms.studio;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.data.BannerSlide;
import io.pacod.cms.core.data.LayoutOrigin;
import io.pacod.cms.core.repository.BannerLayoutRepository;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.studio.form.BannerLayoutForm;
import io.pacod.cms.studio.form.BannerSlideForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules a layout save and delete enforce.
 *
 * <p>Three of these pin refusals that would otherwise fail silently and off-screen: a folder name
 * shadowing a route, a {@code javascript:} URL written into markup the console publishes, and a
 * delete that removes a folder a live booking is still pointing at.
 */
class MgmtBannerLayoutServiceTest {

    private BannerLayoutRepository layoutRepository;
    private BannerRepository bannerRepository;
    private MgmtBannerSkeletonService skeletonService;
    private BannerLayoutStorage storage;
    private MgmtBannerLayoutService service;

    @BeforeEach
    void setUp() {
        layoutRepository = mock(BannerLayoutRepository.class);
        bannerRepository = mock(BannerRepository.class);
        skeletonService = mock(MgmtBannerSkeletonService.class);
        storage = mock(BannerLayoutStorage.class);
        service = new MgmtBannerLayoutService(layoutRepository, bannerRepository, skeletonService,
                storage, new BannerSkeletonParser());

        when(layoutRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(skeletonService.require(anyString())).thenReturn(skeleton());
        when(storage.stat(anyString())).thenReturn(BannerLayoutStorage.LayoutFiles.missing());
    }

    private BannerSkeleton skeleton() {
        BannerSkeleton skeleton = new BannerSkeleton();
        skeleton.setId("sidebar");
        skeleton.setTitle("Странично 300x250");
        skeleton.setWidth(300);
        skeleton.setHeight(250);
        return skeleton;
    }

    private BannerLayout layout(String id, String... images) {
        BannerLayout layout = new BannerLayout(id);
        layout.setTitle(id);
        layout.setSkeleton("sidebar");
        for (String image : images) {
            layout.getSlides().add(new BannerSlide(image));
        }
        when(layoutRepository.findById(id)).thenReturn(Optional.of(layout));
        return layout;
    }

    private BannerLayoutForm form(BannerLayout layout) {
        BannerLayoutForm form = BannerLayoutForm.of(layout);
        form.setTitle(layout.getTitle() == null ? "x" : layout.getTitle());
        form.setRotationMs(10000);
        return form;
    }

    private Banner booking(int id, String name, String source) {
        Banner banner = new Banner(id);
        banner.setName(name);
        banner.setSource(source);
        return banner;
    }

    // --------------------------------------------------------------------------- folder names

    @Test
    @DisplayName("a folder name is narrowed to what is safe as a path, an id and a URL segment")
    void refusesUnusableFolderNames() {
        when(layoutRepository.existsById(anyString())).thenReturn(false);

        assertThat(service.normaliseId("  Acme-2026 ")).isEqualTo("acme-2026");

        for (String bad : List.of("", "a", "2026-acme", "акме", "with space",
                "with/slash", "with.dot", "../escape")) {
            assertThatThrownBy(() -> service.normaliseId(bad))
                    .as("should refuse %s", bad)
                    .isInstanceOf(MgmtOperationException.class);
        }
    }

    @Test
    @DisplayName("a name that would shadow one of this controller's own routes is refused")
    void refusesReservedNames() {
        // A literal path segment beats /{id} on specificity, so a layout called `skeletons` would
        // be unreachable -- and the failure is a 404 about a missing layout, not an error. Every
        // route of this controller that sits at the same level belongs in the list.
        for (String reserved : List.of("new", "import", "adopt", "skeletons")) {
            assertThatThrownBy(() -> service.normaliseId(reserved))
                    .as("creating %s", reserved)
                    .isInstanceOf(MgmtOperationException.class)
                    .hasMessageContaining("reserved name");

            // And on the adoption path, which is the ONE place an id comes off the filesystem
            // rather than a form -- so nothing else would catch a folder literally called `adopt`.
            onDisk(reserved, "index.html", SKELETON_SHAPED, null);
            assertThatThrownBy(() -> service.adopt(reserved))
                    .as("adopting %s", reserved)
                    .isInstanceOf(MgmtOperationException.class)
                    .hasMessageContaining("reserved name");
        }
    }

    @Test
    @DisplayName("a duplicate folder name is refused before anything is written to disk")
    void refusesADuplicate() {
        when(layoutRepository.existsById("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create("acme", "Acme", "sidebar"))
                .isInstanceOf(MgmtOperationException.class);

        verify(storage, never()).write(any(), any());
    }

    @Test
    @DisplayName("a new layout inherits the skeleton's dimensions and GTM id")
    void createsFromTheSkeleton() {
        when(layoutRepository.existsById(anyString())).thenReturn(false);

        BannerLayout created = service.create("acme", "Acme 2026", "sidebar");

        assertThat(created.getId()).isEqualTo("acme");
        assertThat(created.getOrigin()).isEqualTo(LayoutOrigin.BUILT);
        assertThat(created.getWidth()).isEqualTo(300);
        assertThat(created.getEntryFile()).isEqualTo("index.html");
        verify(storage).write(any(), any());
    }

    // ---------------------------------------------------------------------------------- saving

    @Test
    @DisplayName("a slide link that is not http(s) is refused, the same rule the redirect applies")
    void refusesAJavascriptUrl() {
        BannerLayout layout = layout("acme", "a.webp");
        BannerLayoutForm form = form(layout);
        form.getSlides().getFirst().setHref("javascript:alert(1)");

        assertThatThrownBy(() -> service.save("acme", form))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("http://");

        verify(storage, never()).write(any(), any());
    }

    @Test
    @DisplayName("a blank slide link is allowed: a frame may be shown without being clickable")
    void allowsABlankHref() {
        BannerLayout layout = layout("acme", "a.webp");
        BannerLayoutForm form = form(layout);
        form.getSlides().getFirst().setHref("  ");

        service.save("acme", form);

        assertThat(layout.getSlides().getFirst().getHref()).isNull();
    }

    @Test
    @DisplayName("one dimension without the other is refused: it is neither CSS nor a BannerSize")
    void refusesHalfADimension() {
        BannerLayout layout = layout("acme");
        BannerLayoutForm form = form(layout);
        form.setWidth(300);
        form.setHeight(null);

        assertThatThrownBy(() -> service.save("acme", form))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("set together");
    }

    @Test
    @DisplayName("a save never writes a slide's filename, so it cannot point at a file that is gone")
    void keepsSlideFilenames() {
        BannerLayout layout = layout("acme", "a.webp", "b.webp");
        BannerLayoutForm form = form(layout);
        form.getSlides().get(0).setHref("https://example.com/a");
        form.getSlides().get(1).setHref("https://example.com/b");

        service.save("acme", form);

        assertThat(layout.getSlides()).extracting(BannerSlide::getImage)
                .containsExactly("a.webp", "b.webp");
        assertThat(layout.getSlides().get(1).getHref()).isEqualTo("https://example.com/b");
    }

    @Test
    @DisplayName("a slide count that no longer matches is refused rather than applied by position")
    void refusesAStaleForm() {
        // The layout gained a slide in another tab. Applying by position across the shift would
        // put one advertiser's link under another's creative, with nothing to show for it.
        BannerLayout layout = layout("acme", "a.webp", "b.webp");
        BannerLayoutForm form = form(layout);
        form.getSlides().removeLast();

        assertThatThrownBy(() -> service.save("acme", form))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("Reload the page");
    }

    @Test
    @DisplayName("a skeleton that loads GTM makes the container id required")
    void requiresAGtmIdWhenTheSkeletonUsesOne() {
        BannerSkeleton withGtm = skeleton();
        withGtm.setGtmId("GTM-TEST4321");
        when(skeletonService.require(anyString())).thenReturn(withGtm);

        BannerLayout layout = layout("acme");
        BannerLayoutForm form = form(layout);
        form.setGtmId("  ");

        assertThatThrownBy(() -> service.save("acme", form))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("Google Tag Manager");
    }

    @Test
    @DisplayName("an imported layout is not editable: a save would overwrite a folder we never wrote")
    void refusesToEditAnImportedLayout() {
        BannerLayout layout = layout("globex");
        layout.setOrigin(LayoutOrigin.IMPORTED);

        assertThatThrownBy(() -> service.save("globex", form(layout)))
                .isInstanceOf(MgmtOperationException.class);

        verify(storage, never()).write(any(), any());
    }

    // -------------------------------------------------------------------------------- deleting

    @Test
    @DisplayName("deleting a booked layout is refused, and the refusal names the bookings")
    void refusesToDeleteABookedLayout() {
        layout("acme");
        when(bannerRepository.findAll()).thenReturn(List.of(
                booking(4, "Acme пролет", "/resources/banners/acme/index.html"),
                booking(9, "Друга реклама", "/resources/banners/globex/index.html")));

        assertThatThrownBy(() -> service.delete("acme"))
                .isInstanceOf(MgmtOperationException.class)
                // Names, not a count: a count says you cannot proceed, the names say what to do.
                .hasMessageContaining("Acme пролет")
                .hasMessageContaining("№ 4")
                // The booking on a different folder is not named -- it is not in the way.
                .hasMessageNotContaining("Друга реклама");

        verify(storage, never()).deleteFolder(anyString());
    }

    @Test
    @DisplayName("a booking naming another file in the same folder still counts as using it")
    void countsAnyFileInTheFolder() {
        layout("sample");
        when(bannerRepository.findAll()).thenReturn(List.of(
                booking(5, "Sample", "/resources/banners/sample/Globex_300_250.html")));

        assertThat(service.bookingsUsing("sample")).hasSize(1);
    }

    @Test
    @DisplayName("an unbooked layout is removed, document first and then the folder")
    void deletesAnUnbookedLayout() {
        layout("acme");
        when(bannerRepository.findAll()).thenReturn(List.of());

        service.delete("acme");

        verify(layoutRepository).deleteById("acme");
        verify(storage).deleteFolder("acme");
    }

    @Test
    @DisplayName("removing a slide keeps a file another slide still uses")
    void referenceCountsSlideFiles() {
        BannerLayout layout = layout("acme", "shared.webp", "shared.webp", "own.webp");

        service.removeSlide("acme", 0);
        verify(storage, never()).deleteSlideImage(anyString(), anyString());

        service.removeSlide("acme", 1);
        verify(storage).deleteSlideImage("acme", "own.webp");
    }

    @Test
    @DisplayName("moving a slide past either end changes nothing")
    void clampsSlideMoves() {
        BannerLayout layout = layout("acme", "a.webp", "b.webp");

        service.moveSlide("acme", 0, -1);
        service.moveSlide("acme", 1, 1);

        assertThat(layout.getSlides()).extracting(BannerSlide::getImage)
                .containsExactly("a.webp", "b.webp");

        service.moveSlide("acme", 1, -1);
        assertThat(layout.getSlides()).extracting(BannerSlide::getImage)
                .containsExactly("b.webp", "a.webp");
    }

    // ------------------------------------------------------------------- the booking hand-off

    @Test
    @DisplayName("the derived frame size is never AUTO, which would collapse the iframe to nothing")
    void neverSuggestsAuto() {
        BannerLayout square = layout("a");
        square.setWidth(300);
        square.setHeight(250);
        assertThat(service.suggestedSize(square)).contains(BannerSize.SQUARE);

        BannerLayout leaderboard = layout("b");
        leaderboard.setWidth(728);
        leaderboard.setHeight(90);
        assertThat(service.suggestedSize(leaderboard)).contains(BannerSize.TOP);
        assertThat(service.suggestedPlacement(leaderboard)).isEqualTo(BannerPlacement.TOP);

        BannerLayout tall = layout("c");
        tall.setWidth(300);
        tall.setHeight(600);
        assertThat(service.suggestedSize(tall)).contains(BannerSize.TALL);

        // Fluid means the top leaderboard, whose frame height governs on the site.
        assertThat(service.suggestedSize(layout("d"))).contains(BannerSize.TOP);

        // An unrecognised size is left to the operator rather than guessed -- but under no
        // circumstances is AUTO offered.
        BannerLayout odd = layout("e");
        odd.setWidth(468);
        odd.setHeight(60);
        assertThat(service.suggestedSize(odd)).isEmpty();

        for (BannerLayout each : List.of(square, leaderboard, tall, odd)) {
            assertThat(service.suggestedSize(each).orElse(null)).isNotEqualTo(BannerSize.AUTO);
        }
    }

    @Test
    @DisplayName("a booking's source resolves back to the layout it names, and nothing else does")
    void resolvesTheLayoutBehindASource() {
        layout("acme");

        assertThat(service.layoutOf("/resources/banners/acme/index.html")).isPresent();
        assertThat(service.layoutOf("/resources/banners/12-creative.gif")).isEmpty();
        assertThat(service.layoutOf("/assets/img/partners/partner.gif")).isEmpty();
        assertThat(service.layoutOf(null)).isEmpty();
    }

    // ------------------------------------------------------------------------------- adoption

    private static final String SKELETON_SHAPED =
            "<html><head><link rel=stylesheet href=\"css/banner.css\"></head><body>"
                    + "<div class=\"banner-container-inline\">"
                    + "<div id=\"banner-1\"><a href=\"https://a.example\" title=\"A\">"
                    + "<img src=\"a.webp\" alt=\"Alt\"></a></div>"
                    + "<div id=\"banner-2\"><a href=\"https://b.example\"><img src=\"b.webp\"></a></div>"
                    + "</div>"
                    + "<script src=\"js/banner.js\"></script><script>banner();</script></body></html>";

    private void onDisk(String id, String entry, String html, String js) {
        when(storage.orphanFolders(any())).thenReturn(List.of(
                new BannerLayoutStorage.Orphan(id, entry, 4, entry != null)));
        when(storage.readText(id, entry)).thenReturn(html);
        when(storage.readText(id, "js/banner.js")).thenReturn(js);
        when(storage.readText(id, "css/banner.css")).thenReturn(".x {}");
    }

    @Test
    @DisplayName("a skeleton-shaped folder is adopted as editable, with its own frames and interval")
    void adoptsAnExistingFolderAsBuilt() {
        onDisk("acme2", "index.html", SKELETON_SHAPED, "var speed = 4000; var modulo = 2;");
        when(skeletonService.adoptFrom(anyString(), anyString(), any())).thenReturn(skeleton());

        BannerLayout adopted = service.adopt("acme2");

        assertThat(adopted.getOrigin()).isEqualTo(LayoutOrigin.BUILT);
        assertThat(adopted.getSlides()).extracting(BannerSlide::getImage).containsExactly("a.webp", "b.webp");
        assertThat(adopted.getSlides().getFirst().getHref()).isEqualTo("https://a.example");
        // The folder's own interval, not the console default -- adopting must not change what the
        // advertiser booked. `modulo` is deliberately ignored: it is the hardcoded slide count.
        assertThat(adopted.getRotationMs()).isEqualTo(4000);
        // The copy is taken BEFORE the console can regenerate anything.
        verify(storage).backup("acme2");
    }

    @Test
    @DisplayName("an advertiser's export is adopted verbatim, and nothing is rewritten or backed up")
    void adoptsAnimationExportsAsImported() {
        onDisk("Sample_300_250", "Globex_300_250.html",
                "<html><body><canvas id=\"canvas\"></canvas><script>var clickTag=\"https://x\";</script></body></html>",
                null);
        // The parser cannot drive this shape, which is a fact about the folder rather than an error.
        when(skeletonService.adoptFrom(anyString(), anyString(), any())).thenReturn(null);

        BannerLayout adopted = service.adopt("Sample_300_250");

        assertThat(adopted.getOrigin()).isEqualTo(LayoutOrigin.IMPORTED);
        assertThat(adopted.getEntryFile()).isEqualTo("Globex_300_250.html");
        assertThat(adopted.getSlides()).isEmpty();
        // Nothing is written, so there is nothing to preserve.
        verify(storage, never()).backup(anyString());
        verify(storage, never()).write(any(), any());
    }

    @Test
    @DisplayName("a discovered folder keeps its exact name, underscores and capitals included")
    void takesTheFolderNameVerbatim() {
        // normaliseId would refuse every one of these, and lowercasing would resolve on a
        // case-insensitive Mac and 404 on the Linux host. The booking's source names the path as
        // it is, so adoption cannot rename it.
        for (String name : List.of("Globex_300_250", "SAmple_300_250_4", "acme2_", "Demo_Banner")) {
            onDisk(name, "index.html", SKELETON_SHAPED, null);
            when(skeletonService.adoptFrom(anyString(), anyString(), any())).thenReturn(skeleton());

            assertThat(service.adopt(name).getId()).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("a folder that is not on the volume, or already adopted, is refused")
    void refusesAnUnknownOrDuplicateFolder() {
        when(storage.orphanFolders(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.adopt("nothing-here"))
                .isInstanceOf(MgmtNotFoundException.class);

        when(layoutRepository.existsById("acme2")).thenReturn(true);
        assertThatThrownBy(() -> service.adopt("acme2"))
                .isInstanceOf(MgmtOperationException.class);

        assertThatThrownBy(() -> service.adopt("../escape"))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> service.adopt(""))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("discovery hides folders that already have a document")
    void discoveryExcludesKnownFolders() {
        BannerLayout known = layout("acme2");
        when(layoutRepository.findAll()).thenReturn(List.of(known));
        when(storage.orphanFolders(any())).thenReturn(List.of());

        service.discover();

        verify(storage).orphanFolders(argThat(ids -> ids.contains("acme2")));
    }

    // ------------------------------------------------------------------------- editing a frame

    @Test
    @DisplayName("replacing a frame's picture keeps its link and its position")
    void replacesASlideImage() throws Exception {
        BannerLayout layout = layout("acme", "old.webp", "other.webp");
        layout.getSlides().getFirst().setHref("https://example.com/campaign");
        when(storage.storeSlideImage(anyString(), any())).thenReturn("new.webp");

        service.replaceSlideImage("acme", 0, new MockMultipartFile("file", "x.webp", "image/webp", new byte[]{1}));

        assertThat(layout.getSlides().getFirst().getImage()).isEqualTo("new.webp");
        assertThat(layout.getSlides().getFirst().getHref()).isEqualTo("https://example.com/campaign");
        assertThat(layout.getSlides()).hasSize(2);
        // The old file goes only after the document points at the new one.
        verify(storage).deleteSlideImage("acme", "old.webp");
    }

    @Test
    @DisplayName("a picture two frames share survives replacing it on one of them")
    void keepsASharedPictureOnReplacement() throws Exception {
        BannerLayout layout = layout("acme", "shared.webp", "shared.webp");
        when(storage.storeSlideImage(anyString(), any())).thenReturn("new.webp");

        service.replaceSlideImage("acme", 0, new MockMultipartFile("file", "x.webp", "image/webp", new byte[]{1}));

        assertThat(layout.getSlides()).extracting(BannerSlide::getImage)
                .containsExactly("new.webp", "shared.webp");
        verify(storage, never()).deleteSlideImage(anyString(), eq("shared.webp"));
    }

    // -------------------------------------------------------------------------- the file editor

    @Test
    @DisplayName("a built layout's generated files are not hand-editable, because a save overwrites them")
    void refusesToEditGeneratedFiles() {
        BannerLayout layout = layout("acme");

        for (String generated : List.of("index.html", "css/banner.css", "js/banner.js")) {
            assertThatThrownBy(() -> service.saveFile("acme", generated, "x"))
                    .as("should refuse %s", generated)
                    .isInstanceOf(MgmtOperationException.class)
                    .hasMessageContaining("rewritten by the console");
        }

        // Anything else in the same folder is the operator's.
        service.saveFile("acme", "extra.js", "x");
        verify(storage).writeTextFile("acme", "extra.js", "x");
    }

    @Test
    @DisplayName("an imported layout's every file is editable, including the one it opens at")
    void allowsEditingAnImportedLayout() {
        BannerLayout layout = layout("sample");
        layout.setOrigin(LayoutOrigin.IMPORTED);
        layout.setEntryFile("Globex_300_250.html");

        // This is the whole point: the destination URL is a clickTag string inside the export.
        service.saveFile("sample", "Globex_300_250.js", "var clickTag = \"https://new.example\";");

        verify(storage).writeTextFile("sample", "Globex_300_250.js",
                "var clickTag = \"https://new.example\";");
    }
}
