package io.pacod.cms.studio;

import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtMonitoringService.BannerReport;
import io.pacod.cms.studio.MgmtMonitoringService.ContentTotals;
import io.pacod.cms.studio.MgmtMonitoringService.LiveCount;
import io.pacod.cms.studio.MgmtMonitoringService.TopArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything {@code /mgmt/dashboard} shows: who is here now, and what the last 24 hours did.
 *
 * <p>Almost nothing here is new arithmetic. Each section is the 24-hour reading of a screen that
 * already exists -- monitoring, crawlers, the social page, the jobs -- through that screen's
 * own service, so a number on the dashboard and the same number one click away cannot disagree.
 * The two exceptions are the administrators, from {@link MgmtPresence}, and the comparison with
 * the 24 hours before, which reads the hourly 7-day series once and sums the slice before the
 * window.
 *
 * <h2>Every section fails on its own</h2>
 * This screen is where every operator lands after signing in. A section whose query throws is
 * therefore replaced by its empty value and an ERROR log rather than taking the page down with it
 * -- a console whose front door is a 500 has no front door. The underlying services already
 * degrade most {@code DataAccessException}s themselves; the guard here is for everything else.
 * The live reader count keeps its own rule and is never degraded to zero: see
 * {@link MgmtMonitoringService#liveVisitors()}.
 */
@Slf4j
@Service
public class MgmtDashboardService {

    /** The hourly 7-day series; its last 24 buckets are the window, the 24 before are "yesterday". */
    private static final int HOURS = 24;

    private final MgmtMonitoringService monitoringService;
    private final MgmtCrawlerService crawlerService;
    private final MgmtSocialService socialService;
    private final MgmtSocialStatsService socialStatsService;
    private final JobQueue jobs;
    private final MgmtImageService imageService;
    private final MgmtPresence presence;

    public MgmtDashboardService(MgmtMonitoringService monitoringService,
                                MgmtCrawlerService crawlerService,
                                MgmtSocialService socialService,
                                MgmtSocialStatsService socialStatsService,
                                JobQueue jobs,
                                MgmtImageService imageService,
                                MgmtPresence presence) {
        this.monitoringService = monitoringService;
        this.crawlerService = crawlerService;
        this.socialService = socialService;
        this.socialStatsService = socialStatsService;
        this.jobs = jobs;
        this.imageService = imageService;
        this.presence = presence;
    }

    /* ------------------------------------------------------------------ what the screen reads */

    /** Operators active in the console, and the window that "active" means. */
    public record Admins(List<String> names, int windowMinutes) {

        public int count() {
            return names.size();
        }
    }

    /**
     * The traffic tiles and chart. {@code viewsChange} and {@code peakChange} are percentages
     * against the 24 hours before, or {@code null} when those held nothing: a change from zero is
     * not a number anybody can act on, and "+∞ %" would be the flat-zero-line mistake in another
     * shape.
     */
    public record Traffic(long views, long peakVisitors, Double viewsChange, Double peakChange,
                          MgmtChart.Chart chart) {
    }

    public record Advertising(BannerReport report, MgmtChart.Chart chart) {
    }

    public record Crawlers(MgmtCrawlerService.Totals totals, MgmtChart.Chart chart,
                           MgmtCrawlerService.Watch watch) {
    }

    /** {@code runner} is null when it could not be read. */
    /** The website's jobs queued in the last 24 hours, by where they are now. */
    public record Jobs(long queued, long running, long failed) {
    }

    public record Content(ContentTotals totals, long newImages) {
    }

    /**
     * The whole screen. {@code social} is null when the link-in-bio page has never been saved or
     * is unpublished -- its card is left out rather than showing zeroes for a page nobody can
     * reach.
     */
    public record Dashboard(LiveCount readers, Admins admins, Traffic traffic, Advertising advertising,
                            Crawlers crawlers, MgmtSocialStatsService.Totals social, Jobs jobs,
                            Content content, List<TopArticle> topArticles) {
    }

    /** What {@code GET /mgmt/dashboard/live} ships: the two "right now" tiles. */
    public record Live(LiveCount readers, Admins admins) {
    }

    /* ------------------------------------------------------------------ assembling it */

    public Live live() {
        return new Live(monitoringService.liveVisitors(), admins());
    }

    public Admins admins() {
        return new Admins(presence.active(), presence.windowMinutes());
    }

    public Dashboard dashboard() {
        List<MgmtMonitoringService.Bucket> buckets =
                guard("traffic series", () -> monitoringService.series(MonitoringRange.H24), List.of());

        return new Dashboard(
                monitoringService.liveVisitors(),
                admins(),
                guard("traffic", () -> traffic(buckets), emptyTraffic()),
                guard("advertising", () -> advertising(buckets), emptyAdvertising()),
                guard("crawlers", this::crawlers, emptyCrawlers()),
                guard("social page", this::social, null),
                guard("jobs", this::jobs, new Jobs(0, 0, 0)),
                guard("content", this::content, new Content(new ContentTotals(0, 0, 0, 0, 0), 0)),
                guard("most read articles", () -> monitoringService.topArticles(5), List.of()));
    }

    private Traffic traffic(List<MgmtMonitoringService.Bucket> window) {
        MgmtMonitoringService.Totals now = monitoringService.totals(window);

        // The slice before the window, out of the hourly week. Both series end at the bucket open
        // now, so the last 24 of the week ARE the window and the 24 before them are yesterday.
        List<MgmtMonitoringService.Bucket> week = monitoringService.series(MonitoringRange.D7);
        MgmtMonitoringService.Totals before = week.size() >= 2 * HOURS
                ? monitoringService.totals(week.subList(week.size() - 2 * HOURS, week.size() - HOURS))
                : null;

        return new Traffic(now.views(), now.peakVisitors(),
                before == null ? null : change(now.views(), before.views()),
                before == null ? null : change(now.peakVisitors(), before.peakVisitors()),
                monitoringService.trafficChart(window));
    }

    private Advertising advertising(List<MgmtMonitoringService.Bucket> window) {
        return new Advertising(
                monitoringService.bannerReport(window, monitoringService.bookings(), MonitoringRange.H24),
                monitoringService.bannerChart(window, null, MonitoringRange.H24));
    }

    private Crawlers crawlers() {
        MgmtCrawlerService.Report report = crawlerService.report(
                crawlerService.series(MonitoringRange.H24), crawlerService.lastSeen());
        return new Crawlers(report.totals(), report.chart(), crawlerService.watch(Instant.now()));
    }

    private MgmtSocialStatsService.Totals social() {
        if (!socialService.exists()) {
            return null;
        }
        SocialPage page = socialService.load();
        if (page == null || !page.isPublished()) {
            return null;
        }
        // No all-time map: the totals do not read it, and it is a query per render for nothing.
        return socialStatsService.report(socialStatsService.series(MonitoringRange.H24), page,
                null, null, Map.of()).totals();
    }

    private Jobs jobs() {
        Map<JobStatus, Long> counts = jobs.counts(TenantContext.current().siteId(),
                Instant.now().minus(java.time.Duration.ofHours(HOURS)));
        return new Jobs(counts.getOrDefault(JobStatus.QUEUED, 0L), counts.getOrDefault(JobStatus.RUNNING, 0L),
                counts.getOrDefault(JobStatus.FAILED, 0L));
    }

    private Content content() {
        // Images, not articles: an article carries no creation date, so "new articles today" is
        // a number this platform cannot produce honestly.
        long newImages = imageService.countCreatedSince(LocalDateTime.now().minusHours(HOURS));
        return new Content(monitoringService.contentTotals(), newImages);
    }

    /** Percentage change, or null when there is nothing to compare against. */
    static Double change(long now, long before) {
        if (before <= 0) {
            return null;
        }
        return (now - before) * 100.0 / before;
    }

    private static Traffic emptyTraffic() {
        return new Traffic(0, 0, null, null, emptyChart());
    }

    private static Advertising emptyAdvertising() {
        return new Advertising(new BannerReport(List.of(), 0, 0, 0, null, null, null, false), emptyChart());
    }

    private static Crawlers emptyCrawlers() {
        return new Crawlers(new MgmtCrawlerService.Totals(0, 0, 0, 0), emptyChart(),
                new MgmtCrawlerService.Watch(false, null, List.of()));
    }

    private static MgmtChart.Chart emptyChart() {
        return MgmtChart.of(List.of(), List.of(), List.of());
    }

    private static <T> T guard(String section, Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            log.error("Dashboard: could not read the {} section; showing it empty: {}", section, e.toString());
            return fallback;
        }
    }
}
