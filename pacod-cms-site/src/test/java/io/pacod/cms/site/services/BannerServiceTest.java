package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What each page renders, given the whole collection.
 *
 * <p>All of this is data, not a template flag: these are the rules, the active flag, the run
 * window, and the page filter.
 */
class BannerServiceTest {

    private BannerReader reader;
    private BannerImpressionCounter counter;
    private BannerService service;

    @BeforeEach
    void setUp() {
        reader = mock(BannerReader.class);
        counter = mock(BannerImpressionCounter.class);
        service = new BannerService(reader, counter);
    }

    private static Banner banner(int id, BannerPlacement placement) {
        Banner banner = new Banner(id);
        banner.setName("Banner " + id);
        banner.setType(BannerType.HTML5);
        banner.setSource("/resources/banners/" + id + "/index.html");
        banner.setPlacement(placement);
        banner.setSortOrder(id);
        return banner;
    }

    @Test
    @DisplayName("Only the requested surface is returned")
    void filtersByPlacement() {
        when(reader.findAll()).thenReturn(List.of(
                banner(1, BannerPlacement.SIDEBAR),
                banner(2, BannerPlacement.TOP)));

        assertThat(service.running(BannerPlacement.TOP, BannerPage.ARTICLE))
                .extracting(Banner::getId).containsExactly(2);
        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE))
                .extracting(Banner::getId).containsExactly(1);
    }

    @Test
    @DisplayName("An inactive banner is not rendered")
    void skipsInactive() {
        Banner off = banner(1, BannerPlacement.SIDEBAR);
        off.setActive(false);
        when(reader.findAll()).thenReturn(List.of(off));

        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE)).isEmpty();
    }

    /**
     * The whole point of filtering after the cache rather than caching a filtered list: a booking
     * starts and ends on the clock, not on the TTL.
     */
    @Test
    @DisplayName("The run window is applied, inclusively at both ends")
    void appliesTheRunWindow() {
        LocalDateTime now = LocalDateTime.now();

        Banner notYet = banner(1, BannerPlacement.SIDEBAR);
        notYet.setActiveFrom(now.plusDays(1));

        Banner expired = banner(2, BannerPlacement.SIDEBAR);
        expired.setActiveTo(now.minusDays(1));

        Banner running = banner(3, BannerPlacement.SIDEBAR);
        running.setActiveFrom(now.minusDays(1));
        running.setActiveTo(now.plusDays(1));

        Banner openEnded = banner(4, BannerPlacement.SIDEBAR);
        openEnded.setActiveFrom(now.minusDays(1));

        when(reader.findAll()).thenReturn(List.of(notYet, expired, running, openEnded));

        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE))
                .extracting(Banner::getId).containsExactly(3, 4);
    }

    @Test
    @DisplayName("A boundary instant is inside the window, not outside it")
    void windowBoundariesAreInclusive() {
        LocalDateTime now = LocalDateTime.now();

        Banner banner = banner(1, BannerPlacement.SIDEBAR);
        banner.setActiveFrom(now.minusSeconds(1));
        banner.setActiveTo(now.plusSeconds(1));

        assertThat(banner.withinWindow(banner.getActiveFrom())).isTrue();
        assertThat(banner.withinWindow(banner.getActiveTo())).isTrue();
        assertThat(banner.withinWindow(banner.getActiveFrom().minusNanos(1))).isFalse();
        assertThat(banner.withinWindow(banner.getActiveTo().plusNanos(1))).isFalse();
    }

    /**
     * One booking on the list page and nowhere else.
     */
    @Test
    @DisplayName("A page filter restricts a banner to the pages it names")
    void filtersByPage() {
        Banner listOnly = banner(1, BannerPlacement.SIDEBAR);
        listOnly.setPages(List.of(BannerPage.LIST.name()));
        when(reader.findAll()).thenReturn(List.of(listOnly));

        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.LIST)).hasSize(1);
        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE)).isEmpty();
        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.LANDING)).isEmpty();
    }

    @Test
    @DisplayName("An empty page list means every page, not no page")
    void anEmptyPageListRunsEverywhere() {
        Banner everywhere = banner(1, BannerPlacement.SIDEBAR);
        Banner alsoEverywhere = banner(2, BannerPlacement.SIDEBAR);
        alsoEverywhere.setPages(null);

        when(reader.findAll()).thenReturn(List.of(everywhere, alsoEverywhere));

        for (BannerPage page : BannerPage.values()) {
            assertThat(service.running(BannerPlacement.SIDEBAR, page)).hasSize(2);
        }
    }

    @Test
    @DisplayName("Rendering records one impression per banner shown, and none for those skipped")
    void recordsImpressionsForWhatIsShown() {
        Banner shown = banner(1, BannerPlacement.SIDEBAR);
        Banner hidden = banner(2, BannerPlacement.SIDEBAR);
        hidden.setActive(false);

        when(reader.findAll()).thenReturn(List.of(shown, hidden));

        service.forPage(BannerPlacement.SIDEBAR, BannerPage.ARTICLE);

        verify(counter, times(1)).recordImpression(1);
        verify(counter, never()).recordImpression(2);
    }

    /**
     * The selection is also what the studio previews, so it must be available without the write
     * that {@code forPage} performs.
     */
    @Test
    @DisplayName("running() has no impression side effect")
    void runningDoesNotCount() {
        when(reader.findAll()).thenReturn(List.of(banner(1, BannerPlacement.SIDEBAR)));

        service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE);

        verify(counter, never()).recordImpression(any());
    }

    @Test
    @DisplayName("An unreachable database costs the ad column, not the page")
    void survivesAReadFailure() {
        when(reader.findAll()).thenThrow(new IllegalStateException("no mongo"));

        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE)).isEmpty();
    }

    private static Banner forSections(int id, BannerPlacement placement, Integer... categories) {
        Banner banner = banner(id, placement);
        banner.setCategories(List.of(categories));
        return banner;
    }

    @Test
    @DisplayName("specific beats general: on a section's article its own banner takes the placement, the general ones step aside")
    void aSectionsBannerTakesThePlacement() {
        when(reader.findAll()).thenReturn(List.of(
                banner(1, BannerPlacement.SIDEBAR),
                forSections(2, BannerPlacement.SIDEBAR, 4),
                forSections(3, BannerPlacement.SIDEBAR, 5, 6),
                banner(4, BannerPlacement.TOP)));

        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE, List.of(4)))
                .extracting(Banner::getId).containsExactly(2);
        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.LIST, List.of(6, 4)))
                .extracting(Banner::getId).containsExactly(2, 3);
        // Per placement: the leaderboard has no section's banner, so the general one stays.
        assertThat(service.running(BannerPlacement.TOP, BannerPage.ARTICLE, List.of(4)))
                .extracting(Banner::getId).containsExactly(4);
        // A section with no banner of its own, and a page with no section, get the general ones.
        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.ARTICLE, List.of(9)))
                .extracting(Banner::getId).containsExactly(1);
        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.LANDING))
                .extracting(Banner::getId).containsExactly(1);
    }

    @Test
    @DisplayName("a section's banner still keeps its pages, its run window and its active flag")
    void aSectionsBannerKeepsItsOtherRules() {
        Banner articlesOnly = forSections(2, BannerPlacement.SIDEBAR, 4);
        articlesOnly.setPages(List.of(BannerPage.ARTICLE.name()));
        Banner off = forSections(3, BannerPlacement.SIDEBAR, 4);
        off.setActive(false);
        when(reader.findAll()).thenReturn(List.of(banner(1, BannerPlacement.SIDEBAR), articlesOnly, off));

        assertThat(service.running(BannerPlacement.SIDEBAR, BannerPage.LIST, List.of(4)))
                .extracting(Banner::getId).containsExactly(1);
        assertThat(service.forPage(BannerPlacement.SIDEBAR, BannerPage.ARTICLE, List.of(4)))
                .extracting(Banner::getId).containsExactly(2);
        verify(counter, times(1)).recordImpression(2);
        verify(counter, never()).recordImpression(1);
    }
}
