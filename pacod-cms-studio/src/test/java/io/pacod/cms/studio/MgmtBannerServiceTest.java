package io.pacod.cms.studio;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.studio.form.BannerForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a console save does and, more importantly, what it must leave alone.
 *
 * <p>Two of the assertions here pin omissions rather than behaviour. Both would fail silently: a
 * form that carried the counters would reset them on every save, and a save that copied
 * {@code source} unconditionally would blank an uploaded creative -- neither raises anything, both
 * just quietly lose data.
 */
class MgmtBannerServiceTest {

    private BannerRepository repository;
    private BannerCreativeService creativeService;
    private MgmtBannerService service;

    @BeforeEach
    void setUp() {
        repository = mock(BannerRepository.class);
        creativeService = mock(BannerCreativeService.class);
        service = new MgmtBannerService(repository, creativeService);

        when(repository.save(any(Banner.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Banner stored(int id, BannerType type) {
        Banner banner = new Banner(id);
        banner.setName("Партньор");
        banner.setType(type);
        banner.setSource(type == BannerType.IMAGE
                ? "/resources/banners/5-partner.gif"
                : "/resources/banners/acme2/index.html");
        banner.setTargetUrl("https://partner.example.net/");
        banner.setPlacement(BannerPlacement.SIDEBAR);
        banner.setSize(BannerSize.AUTO);
        banner.setSortOrder(20);
        banner.setImpressions(4711);
        banner.setClicks(23);
        return banner;
    }

    private static BannerForm imageForm() {
        BannerForm form = new BannerForm();
        form.setName("Партньор, есен");
        form.setType(BannerType.IMAGE);
        form.setTargetUrl("https://partner.example.net/autumn");
        form.setPlacement(BannerPlacement.SIDEBAR);
        form.setSize(BannerSize.AUTO);
        form.setSortOrder(25);
        return form;
    }

    private Banner saved() {
        ArgumentCaptor<Banner> captor = ArgumentCaptor.forClass(Banner.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * The counters are written by an atomic {@code $inc} outside any {@code save()} on this
     * document. Spring Data writes the whole document, so binding them to the form would restore
     * whatever was read when the operator opened the page.
     */
    @Test
    @DisplayName("A save does not touch the impression and click counters")
    void saveLeavesCountersAlone() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));

        service.save(5, imageForm());

        assertThat(saved().getImpressions()).isEqualTo(4711);
        assertThat(saved().getClicks()).isEqualTo(23);
    }

    /**
     * An image banner's source comes from the upload endpoint and is deliberately not on the form.
     * Copying the form's (blank) value over it would remove the creative on the next save of any
     * other field.
     */
    @Test
    @DisplayName("A save does not blank an uploaded creative")
    void saveKeepsTheUploadedSource() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));

        service.save(5, imageForm());

        assertThat(saved().getSource()).isEqualTo("/resources/banners/5-partner.gif");
    }

    @Test
    @DisplayName("An HTML5 banner's path is taken from the form, because there is nothing to upload")
    void saveTakesTheHtml5PathFromTheForm() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.HTML5)));

        BannerForm form = new BannerForm();
        form.setName("Acme");
        form.setType(BannerType.HTML5);
        form.setSource("/resources/banners/acme3/index.html");
        form.setPlacement(BannerPlacement.SIDEBAR);
        form.setSize(BannerSize.SQUARE);
        form.setSortOrder(10);

        service.save(5, form);

        assertThat(saved().getSource()).isEqualTo("/resources/banners/acme3/index.html");
    }

    @Test
    @DisplayName("The page filter round-trips, and an empty one means everywhere")
    void savesThePageFilter() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));

        BannerForm form = imageForm();
        form.setPages(List.of(BannerPage.LIST.name()));

        service.save(5, form);

        assertThat(saved().getPages()).containsExactly("LIST");
        assertThat(saved().coversPage(BannerPage.LIST)).isTrue();
        assertThat(saved().coversPage(BannerPage.ARTICLE)).isFalse();
    }

    @Test
    @DisplayName("The section filter round-trips without repeats; a section that does not exist is refused")
    void savesTheSectionFilter() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));
        var categories = mock(io.pacod.cms.core.repository.CategoryRepository.class);
        when(categories.existsById(3)).thenReturn(true);
        when(categories.existsById(4)).thenReturn(true);
        service.useCategories(categories);

        BannerForm form = imageForm();
        form.setCategories(List.of(3, 4, 3));
        service.save(5, form);
        assertThat(saved().getCategories()).containsExactly(3, 4);
        assertThat(BannerForm.of(saved()).getCategories()).containsExactly(3, 4);

        BannerForm unknown = imageForm();
        unknown.setCategories(List.of(9));
        assertThatThrownBy(() -> service.save(5, unknown)).isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("9");
    }

    /**
     * The value goes straight into a {@code Location} header at {@code /b/{id}}, so refusing it
     * here is what keeps a {@code javascript:} URL from ever being stored.
     */
    @Test
    @DisplayName("An image banner without an http(s) target is refused")
    void refusesAHostileTarget() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));

        BannerForm form = imageForm();
        form.setTargetUrl("javascript:alert(1)");

        assertThatThrownBy(() -> service.save(5, form))
                .isInstanceOf(MgmtOperationException.class);

        verify(repository, never()).save(any(Banner.class));
    }

    @Test
    @DisplayName("An HTML5 banner without a path is refused")
    void refusesAnHtml5BannerWithNoPath() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.HTML5)));

        BannerForm form = new BannerForm();
        form.setName("Acme");
        form.setType(BannerType.HTML5);
        form.setPlacement(BannerPlacement.SIDEBAR);
        form.setSize(BannerSize.SQUARE);
        form.setSortOrder(10);

        assertThatThrownBy(() -> service.save(5, form))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("An HTML5 banner sized AUTO is refused: the frame would have no height at all")
    void refusesAnHtml5BannerSizedAuto() {
        // .partner-card__frame--auto is `height: auto` and the iframe inside it is `height: 100%`, so
        // this combination renders a box of nothing -- a blank ad column with a 200 response and
        // no log line. An image creative on AUTO is the normal case and stays legal.
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.HTML5)));

        BannerForm form = new BannerForm();
        form.setName("Acme");
        form.setType(BannerType.HTML5);
        form.setSource("/resources/banners/acme/index.html");
        form.setPlacement(BannerPlacement.SIDEBAR);
        form.setSize(BannerSize.AUTO);
        form.setSortOrder(10);

        assertThatThrownBy(() -> service.save(5, form))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("no height");

        verify(repository, never()).save(any(Banner.class));
    }

    @Test
    @DisplayName("A run window that ends before it starts is refused")
    void refusesAnInvertedWindow() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));

        BannerForm form = imageForm();
        form.setActiveFrom(LocalDateTime.of(2026, 9, 1, 0, 0));
        form.setActiveTo(LocalDateTime.of(2026, 8, 1, 0, 0));

        assertThatThrownBy(() -> service.save(5, form))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("Creating allocates the next id off the index")
    void createAllocatesTheNextId() {
        Banner highest = new Banner(8);
        when(repository.getMaxId(any())).thenReturn(List.of(highest));

        service.create(imageForm());

        assertThat(saved().getId()).isEqualTo(9);
    }

    @Test
    @DisplayName("The first banner gets id 1")
    void createStartsAtOne() {
        when(repository.getMaxId(any())).thenReturn(List.of());

        service.create(imageForm());

        assertThat(saved().getId()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deleting removes the creative file as well as the document")
    void deleteRemovesTheCreative() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));

        service.delete(5);

        verify(creativeService).delete("/resources/banners/5-partner.gif");
        verify(repository).deleteById(5);
    }

    /**
     * The old file goes only after the document points at the new one, so a replacement that fails
     * halfway still leaves the banner with a creative rather than none.
     */
    @Test
    @DisplayName("Replacing a creative deletes the previous file, after the document is saved")
    void uploadReplacesAndCleansUp() throws MediaException {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.IMAGE)));
        when(creativeService.store(anyInt(), any(MultipartFile.class)))
                .thenReturn("/resources/banners/5-new.gif");

        MockMultipartFile file = new MockMultipartFile("file", "new.gif", "image/gif", new byte[]{1});
        Banner result = service.uploadCreative(5, file);

        assertThat(result.getSource()).isEqualTo("/resources/banners/5-new.gif");
        verify(creativeService).delete("/resources/banners/5-partner.gif");
    }

    @Test
    @DisplayName("An HTML5 banner has nothing to upload")
    void uploadRefusesHtml5Banners() {
        when(repository.findById(5)).thenReturn(Optional.of(stored(5, BannerType.HTML5)));

        MockMultipartFile file = new MockMultipartFile("file", "new.gif", "image/gif", new byte[]{1});

        assertThatThrownBy(() -> service.uploadCreative(5, file))
                .isInstanceOf(MediaException.class);
    }

    @Test
    @DisplayName("A missing banner is a 404, not a null")
    void missingBannerIsNotFound() {
        when(repository.findById(anyInt())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(404, imageForm()))
                .isInstanceOf(MgmtNotFoundException.class);
    }

    /**
     * Filtered in the JVM for the same reason the author and category lists are: ten documents
     * already read and sorted here, so a repository query would add a round trip and remove no
     * work. Every term must match some field, which is what lets "партньор 20" find this row.
     */
    @Test
    @DisplayName("The search filters in memory across every field the row shows")
    void searchMatchesAcrossFields() {
        when(repository.findAll()).thenReturn(List.of(stored(5, BannerType.IMAGE)));

        assertThat(service.findAll(null)).hasSize(1);
        assertThat(service.findAll("  ")).hasSize(1);
        assertThat(service.findAll("партньор")).hasSize(1);
        assertThat(service.findAll("IMAGE")).hasSize(1);
        assertThat(service.findAll("sidebar")).hasSize(1);
        assertThat(service.findAll("partner.example.net")).hasSize(1);
        assertThat(service.findAll("партньор image")).hasSize(1);
        assertThat(service.findAll("acme")).isEmpty();
    }

    @Test
    @DisplayName("The list is grouped by surface, then by the order the column renders in")
    void listIsSortedForDisplay() {
        Banner sidebarLate = stored(1, BannerType.IMAGE);
        sidebarLate.setSortOrder(60);

        Banner sidebarEarly = stored(2, BannerType.IMAGE);
        sidebarEarly.setSortOrder(10);

        Banner top = stored(3, BannerType.HTML5);
        top.setPlacement(BannerPlacement.TOP);
        top.setSortOrder(10);

        when(repository.findAll()).thenReturn(List.of(sidebarLate, sidebarEarly, top));

        assertThat(service.findAll(null))
                .extracting(Banner::getId)
                .containsExactly(2, 1, 3);
    }
}
