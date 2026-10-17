package io.pacod.cms.studio;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.SiteMetricDayRepository;
import io.pacod.cms.core.repository.SiteMetricHourRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The parts of the monitoring service that fail silently if they are wrong.
 *
 * <p>No MongoDB: every repository is a mock, which is what the rest of this module's service
 * tests do. What is under test is the arithmetic and the degradation policy, not the queries.
 */
class MgmtMonitoringServiceTest {

    private MongoTemplate mongoTemplate;
    private SiteMetricHourRepository hourRepository;
    private SiteMetricDayRepository dayRepository;
    private BannerRepository bannerRepository;
    private MgmtMonitoringService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        hourRepository = mock(SiteMetricHourRepository.class);
        dayRepository = mock(SiteMetricDayRepository.class);
        bannerRepository = mock(BannerRepository.class);

        service = new MgmtMonitoringService(mongoTemplate, hourRepository, dayRepository,
                bannerRepository, mock(AuthorRepository.class), mock(CategoryRepository.class),
                mock(ImageMediaRepository.class), 5);
    }

    @Test
    @DisplayName("a window with no recorded buckets still yields one slot per interval, at zero")
    void gapsAreFilledWithZeroes() {
        when(hourRepository.findInWindow(any(), any())).thenReturn(List.of());

        List<MgmtMonitoringService.Bucket> buckets = service.series(MonitoringRange.H24);

        // Returning only the buckets that exist would let the chart draw a straight line across an
        // outage and call it traffic. The gap has to be present and has to be zero.
        assertThat(buckets).hasSize(24);
        assertThat(buckets).allMatch(b -> b.views() == 0 && b.visitors() == 0);
        assertThat(buckets.get(0).start()).isBefore(buckets.get(23).start());
    }

    @Test
    @DisplayName("a recorded bucket lands in its own slot and the ones around it stay empty")
    void recordedBucketsLandInTheirOwnSlot() {
        LocalDateTime hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(3);

        SiteMetricHour recorded = new SiteMetricHour();
        recorded.setId(SiteMetricHour.idFor(hour));
        recorded.setStart(hour);
        recorded.setViews(120);
        recorded.setVisitors(31);
        recorded.setViewsByPage(Map.of("ARTICLE", 100L, "LIST", 20L));

        when(hourRepository.findInWindow(any(), any())).thenReturn(List.of(recorded));

        List<MgmtMonitoringService.Bucket> buckets = service.series(MonitoringRange.H24);

        assertThat(buckets).filteredOn(b -> b.views() > 0)
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.start()).isEqualTo(hour);
                    assertThat(b.views()).isEqualTo(120);
                    assertThat(b.visitors()).isEqualTo(31);
                });
    }

    @Test
    @DisplayName("the window includes the bucket open right now, at both ends of the query")
    void theCurrentBucketIsInTheWindow() {
        LocalDateTime openNow = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        SiteMetricHour recorded = new SiteMetricHour();
        recorded.setId(SiteMetricHour.idFor(openNow));
        recorded.setStart(openNow);
        recorded.setViews(11);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        when(hourRepository.findInWindow(
                from.capture(), to.capture())).thenReturn(List.of(recorded));

        List<MgmtMonitoringService.Bucket> buckets = service.series(MonitoringRange.H24);

        // The finder is inclusive at both ends ($gte/$lte) and not a derived Between precisely for
        // this: on a Mongo repository Between renders as $gt/$lt, so the upper bound -- the hour
        // anyone looks at first -- would fall outside the query and the newest bucket chart as
        // zero while the data is written correctly. Nothing short of a live run would show it.
        assertThat(to.getValue()).isEqualTo(openNow);
        assertThat(from.getValue()).isEqualTo(openNow.minusHours(23));
        assertThat(buckets.get(buckets.size() - 1).views()).isEqualTo(11);
    }

    @Test
    @DisplayName("views sum over the window but readers do not")
    void readersAreAPeakRatherThanASum() {
        List<MgmtMonitoringService.Bucket> buckets = List.of(
                bucket(10, 4), bucket(30, 9), bucket(20, 6));

        MgmtMonitoringService.Totals totals = service.totals(buckets);

        assertThat(totals.views()).isEqualTo(60);
        // 4 + 9 + 6 would count anyone who came back twice. The busiest interval is the honest
        // single number; see SiteMetric.visitors for why these two do not add up.
        assertThat(totals.peakVisitors()).isEqualTo(9);
    }

    @Test
    @DisplayName("the window range picks the grain: 24h and 7d read hours, 30d and 12m read days")
    void theRangeChoosesTheCollection() {
        when(hourRepository.findInWindow(any(), any())).thenReturn(List.of());
        when(dayRepository.findInWindow(any(), any())).thenReturn(List.of());

        assertThat(service.series(MonitoringRange.D7)).hasSize(24 * 7);
        assertThat(service.series(MonitoringRange.D30)).hasSize(30);
        assertThat(service.series(MonitoringRange.M12)).hasSize(365);

        // Seven days is well inside the hourly TTL of thirty; a year is not, which is the whole
        // reason the boundary sits where it does.
        assertThat(MonitoringRange.D7.isHourly()).isTrue();
        assertThat(MonitoringRange.D30.isHourly()).isFalse();
    }

    /* ------------------------------------------------------------------ the advertising report */

    @Test
    @DisplayName("each booking's counts are its map entries summed over the window")
    void perBookingCountsAreSummedOverTheWindow() {
        List<MgmtMonitoringService.Bucket> buckets = List.of(
                bannerBucket(HOUR.minusHours(1), Map.of("3", 600L, "4", 300L), Map.of("3", 400L, "4", 100L),
                        Map.of("3", 9L, "4", 2L)),
                bannerBucket(HOUR, Map.of("3", 400L), Map.of("3", 100L), Map.of("3", 1L)));

        MgmtMonitoringService.BannerReport report = service.bannerReport(buckets, List.of(
                banner(3, "Акме", BannerType.IMAGE, "/assets/img/partners/spring.gif"),
                banner(4, "Интерактивна", BannerType.HTML5, "/resources/banners/acme2/index.html")),
                MonitoringRange.H24);

        MgmtMonitoringService.BannerStat spring = report.rows().get(0);
        assertThat(spring.name()).isEqualTo("Акме");
        assertThat(spring.impressions()).isEqualTo(1000);
        assertThat(spring.viewable()).isEqualTo(500);
        assertThat(spring.clicks()).isEqualTo(10);
        assertThat(spring.clickThroughRate()).isEqualTo(1.0d);
        assertThat(spring.viewableClickThroughRate()).isEqualTo(2.0d);
        assertThat(spring.viewability()).isEqualTo(50.0d);

        // A same-origin HTML5 booking is click-tracked, and gets a rate like any other.
        MgmtMonitoringService.BannerStat html5 = report.rows().get(1);
        assertThat(html5.clickTracked()).isTrue();
        assertThat(html5.clicks()).isEqualTo(2);
        assertThat(html5.clickThroughRate()).isEqualTo(2d / 3);

        // The tiles add up to the rows, and their rate is the rows' clicks over the rows' serves.
        assertThat(report.impressions()).isEqualTo(1300);
        assertThat(report.viewable()).isEqualTo(600);
        assertThat(report.clicks()).isEqualTo(12);
        assertThat(report.clickThroughRate()).isEqualTo(12 * 100d / 1300);
        assertThat(report.partial()).isFalse();
    }

    /**
     * Banner ids are max-plus-one, so deleting the newest booking hands its id to the next one.
     * Without the guard, a booking created today would inherit last month's traffic under its id.
     */
    @Test
    @DisplayName("a bucket that opened before the booking existed is not attributed to it")
    void reusedIdsDoNotInheritHistory() {
        Banner fresh = banner(3, "Нова", BannerType.IMAGE, "/assets/img/partners/new.gif");
        fresh.setCreatedOn(HOUR.plusMinutes(30));

        MgmtMonitoringService.BannerReport report = service.bannerReport(List.of(
                bannerBucket(HOUR.minusHours(1), Map.of("3", 900L), Map.of(), Map.of("3", 50L)),
                // The bucket the booking was created in is its own: it straddles the creation.
                bannerBucket(HOUR, Map.of("3", 100L), Map.of(), Map.of("3", 1L))),
                List.of(fresh), MonitoringRange.H24);

        assertThat(report.rows().get(0).impressions()).isEqualTo(100);
        assertThat(report.rows().get(0).clicks()).isEqualTo(1);
    }

    @Test
    @DisplayName("an off-origin HTML5 booking reports no rate, and stays out of the site's rates")
    void offOriginHtml5HasNoRate() {
        MgmtMonitoringService.BannerReport report = service.bannerReport(List.of(
                bannerBucket(HOUR, Map.of("3", 1000L, "5", 1000L), Map.of(), Map.of("3", 25L))),
                List.of(banner(3, "Акме", BannerType.IMAGE, "/assets/img/partners/spring.gif"),
                        banner(5, "Чужд сървър", BannerType.HTML5, "https://ads.example.com/b/index.html")),
                MonitoringRange.H24);

        // Not 0.0. The public page cannot listen inside an off-origin frame, so a rate of zero
        // would be a measurement nobody took, presented as a measurement of no clicks.
        assertThat(report.rows()).extracting(MgmtMonitoringService.BannerStat::name,
                        MgmtMonitoringService.BannerStat::clickThroughRate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Акме", 2.5d),
                        org.assertj.core.groups.Tuple.tuple("Чужд сървър", null));

        // Served counts it; the rate does not, or one booking nobody can measure would halve it.
        assertThat(report.impressions()).isEqualTo(2000);
        assertThat(report.clickThroughRate()).isEqualTo(2.5d);
        assertThat(report.partial()).isTrue();
    }

    @Test
    @DisplayName("narrowed to one booking, the chart draws that booking's entries only")
    void theChartCanBeNarrowedToOneBooking() {
        Banner spring = banner(3, "Акме", BannerType.IMAGE, "/assets/img/partners/spring.gif");
        List<MgmtMonitoringService.Bucket> buckets = List.of(
                bannerBucket(HOUR, Map.of("3", 600L, "4", 300L), Map.of("3", 200L, "4", 50L),
                        Map.of("3", 6L)));

        MgmtChart.Chart all = service.bannerChart(buckets, null, MonitoringRange.H24);
        MgmtChart.Chart one = service.bannerChart(buckets, spring, MonitoringRange.H24);

        // Unfiltered, served and clicked are the bucket scalars; viewable is the map's sum.
        assertThat(all.series()).extracting(MgmtChart.Series::key, MgmtChart.Series::total)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("impressions", 900L),
                        org.assertj.core.groups.Tuple.tuple("viewable", 250L),
                        org.assertj.core.groups.Tuple.tuple("clicks", 6L));
        assertThat(one.series()).extracting(MgmtChart.Series::key, MgmtChart.Series::total)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("impressions", 600L),
                        org.assertj.core.groups.Tuple.tuple("viewable", 200L),
                        org.assertj.core.groups.Tuple.tuple("clicks", 6L));
    }

    @Test
    @DisplayName("a booking with nothing in the window draws the empty state, not a flat zero line")
    void aQuietBookingDrawsTheEmptyState() {
        MgmtChart.Chart chart = service.bannerChart(
                List.of(bannerBucket(HOUR, Map.of("3", 600L), Map.of(), Map.of())),
                banner(9, "Тиха", BannerType.IMAGE, "/assets/img/partners/quiet.gif"), MonitoringRange.H24);

        assertThat(chart.empty()).isTrue();
    }

    @Test
    @DisplayName("the ?banner= filter resolves leniently: anything that names nothing is no filter")
    void theFilterIsLenient() {
        List<Banner> bookings = List.of(banner(3, "Акме", BannerType.IMAGE, "/x.gif"));

        assertThat(MgmtMonitoringService.selected(bookings, "3")).isSameAs(bookings.get(0));
        assertThat(MgmtMonitoringService.selected(bookings, " 3 ")).isSameAs(bookings.get(0));
        assertThat(MgmtMonitoringService.selected(bookings, "4")).isNull();
        assertThat(MgmtMonitoringService.selected(bookings, "abc")).isNull();
        assertThat(MgmtMonitoringService.selected(bookings, "")).isNull();
        assertThat(MgmtMonitoringService.selected(bookings, null)).isNull();
    }

    @Test
    @DisplayName("a failed booking read empties the table rather than failing the screen")
    void aFailedBookingReadDegrades() {
        when(bannerRepository.findAll()).thenThrow(new DataAccessResourceFailureException("no server"));

        assertThat(service.bookings()).isEmpty();
    }

    @Test
    @DisplayName("no impressions means no rate, not a division by zero")
    void aBookingNobodySawHasNoRate() {
        assertThat(MgmtMonitoringService.ctr(0, 0)).isNull();
        assertThat(MgmtMonitoringService.ctr(200, 4)).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("a failed live count reports itself as unavailable, never as zero readers")
    void liveCountFailsLoudly() {
        when(mongoTemplate.findDistinct(any(), anyString(), eq(io.pacod.cms.core.data.SiteVisitor.class),
                eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("no server"));

        MgmtMonitoringService.LiveCount live = service.liveVisitors();

        // This is the one number on the screen that must not degrade. Zero readers is a plausible
        // Tuesday; a screen that shows it after a failed query is lying quietly.
        assertThat(live.available()).isFalse();
        assertThat(live.active()).isZero();
    }

    @Test
    @DisplayName("a failed series read empties the chart rather than failing the screen")
    void aFailedSeriesReadDegrades() {
        when(hourRepository.findInWindow(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("no server"));

        // Same policy as MgmtCategoryService.countArticlesByCategory: a number in a column is not
        // worth failing a whole screen for.
        assertThat(service.series(MonitoringRange.H24)).hasSize(24);
        assertThat(service.series(MonitoringRange.H24)).allMatch(b -> b.views() == 0);
    }

    @Test
    @DisplayName("page traffic sums the window and shares add up to a hundred")
    void pageTrafficSharesTheWindow() {
        List<MgmtMonitoringService.Bucket> buckets = List.of(
                bucketWithPages(Map.of("ARTICLE", 60L, "LIST", 20L)),
                bucketWithPages(Map.of("ARTICLE", 20L)));

        List<MgmtMonitoringService.PageTraffic> traffic = service.trafficByPage(buckets);

        // EVERY page type is present even at zero, so the table does not change shape with the
        // data, and the busiest is first. Counted off the enum rather than pinned to a number:
        // adding a page type is a routine change, and a magic 4 turns it into a failing test that
        // says nothing about what broke.
        assertThat(traffic).hasSize(io.pacod.cms.core.data.BannerPage.values().length);
        assertThat(traffic.get(0).views()).isEqualTo(80);
        assertThat(traffic.get(0).share()).isEqualTo(80d);
        assertThat(traffic).filteredOn(p -> p.views() == 0).allMatch(p -> p.share() == 0d);
    }

    /** An hour well after every fixture booking's creation, so the id-reuse guard keeps it. */
    private static final LocalDateTime HOUR = LocalDateTime.of(2026, 8, 9, 14, 0);

    private static MgmtMonitoringService.Bucket bucket(long views, long visitors) {
        return new MgmtMonitoringService.Bucket(LocalDateTime.now(), "14:00", "09.08 14:00",
                views, visitors, 0, 0, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static MgmtMonitoringService.Bucket bucketWithPages(Map<String, Long> pages) {
        return new MgmtMonitoringService.Bucket(LocalDateTime.now(), "14:00", "09.08 14:00",
                0, 0, 0, 0, pages, Map.of(), Map.of(), Map.of());
    }

    /** The site-wide scalars are the maps' sums, as the collector writes them. */
    private static MgmtMonitoringService.Bucket bannerBucket(LocalDateTime start, Map<String, Long> served,
                                                             Map<String, Long> viewable,
                                                             Map<String, Long> clicks) {
        return new MgmtMonitoringService.Bucket(start, "14:00", "09.08 14:00", 0, 0,
                served.values().stream().mapToLong(Long::longValue).sum(),
                clicks.values().stream().mapToLong(Long::longValue).sum(),
                Map.of(), served, viewable, clicks);
    }

    private static Banner banner(int id, String name, BannerType type, String source) {
        Banner banner = new Banner(id);
        banner.setName(name);
        banner.setType(type);
        banner.setSource(source);
        banner.setCreatedOn(LocalDateTime.of(2026, 1, 1, 0, 0));
        return banner;
    }
}
