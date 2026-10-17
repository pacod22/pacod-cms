package io.pacod.cms.studio;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.SiteMetric;
import io.pacod.cms.core.data.SiteVisitor;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.SiteMetricDayRepository;
import io.pacod.cms.core.repository.SiteMetricHourRepository;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything the monitoring screen reads.
 *
 * <h2>What this can and cannot answer</h2>
 * The traffic series comes from {@code siteMetricHour} / {@code siteMetricDay}, which the
 * <em>public site</em> writes from the moment it first counts. <b>Nothing is back-filled and
 * nothing could be</b>: {@code Article.counter}, {@code Banner.impressions} and
 * {@code Banner.clicks} are all-time scalars with no date on them. Everything charted here starts
 * when the stack first counted; everything counted from those three scalars is all-time. That is
 * why the screen labels the two kinds of number differently rather than putting them under one
 * word.
 *
 * <h2>Per-booking numbers come from the buckets, not from the booking</h2>
 * The advertising table, the tiles above it and the filtered chart sum the per-booking maps in
 * each bucket ({@code bannerImpressionsById} and its two siblings). They never divide anything by
 * {@code Banner.impressions}: that scalar is all-time and dateless, so a rate over it would be a
 * windowed numerator over an all-time denominator. Two consequences:
 * <ul>
 *   <li><b>Ids are reused</b> -- banners take max-plus-one -- so a bucket that opened before a
 *       booking's {@code createdOn} is not attributed to it. A bucket straddling the creation is,
 *       which leaks at most one bucket of the previous holder's traffic.</li>
 *   <li>The unfiltered chart draws each bucket's site-wide totals, which also hold what the
 *       tiles leave out -- deleted bookings, traffic before a booking's {@code createdOn} -- so
 *       the chart can show more than the tiles. Both are labelled for what they are.</li>
 * </ul>
 *
 * <h2>Reading counters, not caches</h2>
 * Banner bookings are read straight from {@link BannerRepository}. The public site
 * has a {@code BannerReader} that caches the whole collection under one key with a 60s TTL --
 * this application deliberately has no such bean and must not grow one, or the monitoring screen
 * would report numbers up to a minute old with nothing to show that it had.
 *
 * <h2>Failing loudly in exactly one place</h2>
 * A query that falls over degrades to zeroes and an ERROR log, the way
 * {@link MgmtCategoryService#countArticlesByCategory()} does -- a number in a column is not worth
 * failing a whole screen for. {@link #liveVisitors()} is the exception and returns
 * {@link LiveCount#unavailable()} instead, because zero readers is a completely plausible answer:
 * degrading to it would not look like a failure, it would look like a quiet Tuesday.
 */
@Slf4j
@Service
public class MgmtMonitoringService {

    private static final String ARTICLE_COLLECTION = "article";

    private static final String FIELD_ID = "_id";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_COUNTER = "counter";
    private static final String FIELD_DATE = "date";

    private final MongoTemplate mongoTemplate;
    private final SiteMetricHourRepository hourRepository;
    private final SiteMetricDayRepository dayRepository;
    private final BannerRepository bannerRepository;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final ImageMediaRepository imageRepository;
    private final int activeWindowMinutes;
    private MgmtText text;

    public MgmtMonitoringService(MongoTemplate mongoTemplate,
                                 SiteMetricHourRepository hourRepository,
                                 SiteMetricDayRepository dayRepository,
                                 BannerRepository bannerRepository,
                                 AuthorRepository authorRepository,
                                 CategoryRepository categoryRepository,
                                 ImageMediaRepository imageRepository,
                                 @Value("${app.mgmt.monitoring.active-window-minutes:5}") int activeWindowMinutes) {
        this.mongoTemplate = mongoTemplate;
        this.hourRepository = hourRepository;
        this.dayRepository = dayRepository;
        this.bannerRepository = bannerRepository;
        this.authorRepository = authorRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.activeWindowMinutes = activeWindowMinutes;
    }

    /**
     * Words the chart tooltips in the operator's language. A setter rather than a constructor
     * argument so that a service built without it -- a unit test, or a mock running the real
     * chart methods -- words them in English (the base bundle) instead of failing.
     */
    @Autowired(required = false)
    public void setText(MgmtText text) {
        this.text = text;
    }

    private String msg(String key, Object... args) {
        return text == null ? MgmtText.base(key, args) : text.msg(key, args);
    }

    /* ------------------------------------------------------------------ live readers */

    /**
     * Distinct readers seen in the last few minutes.
     *
     * <p>A {@code distinct} over the hash rather than a document count: {@code siteVisitor} holds
     * one row per reader <em>per day</em>, so a reader whose window straddles midnight owns two
     * rows and would otherwise count twice.
     *
     * <p>The window is a property because it is a judgement, not a fact -- five minutes calls
     * someone who has stopped reading "active" for five minutes, and calling it thirty would count
     * most of an evening's readers at once.
     */
    public LiveCount liveVisitors() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(activeWindowMinutes);

        try {
            List<String> hashes = mongoTemplate.findDistinct(
                    new Query(Criteria.where(SiteVisitor.FIELD_LAST_SEEN).gte(cutoff)),
                    SiteVisitor.FIELD_HASH, SiteVisitor.class, String.class);

            return new LiveCount(hashes.size(), activeWindowMinutes, true);
        } catch (DataAccessException e) {
            // Deliberately NOT degraded to zero: zero readers is a plausible answer, so a silent
            // failure here would read as a quiet moment rather than as a broken screen.
            log.error("Could not count active readers: {}", e.getMessage());
            return LiveCount.unavailable();
        }
    }

    /* ------------------------------------------------------------------ the series */

    /**
     * The window's buckets, gap-filled.
     *
     * <p>Every slot in the range is present even when nothing was recorded for it. Returning only
     * the buckets that exist would let the chart draw a straight line across an outage and call it
     * traffic -- the one shape that must never be inferred is the one that isn't there.
     */
    public List<Bucket> series(MonitoringRange range) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = range.from(now);
        LocalDateTime to = range.to(now);

        Map<LocalDateTime, SiteMetric> recorded = new HashMap<>();

        try {
            List<? extends SiteMetric> found = range.isHourly()
                    ? hourRepository.findInWindow(from, to)
                    : dayRepository.findInWindow(from, to);

            for (SiteMetric metric : found) {
                if (metric.getStart() != null) {
                    recorded.put(metric.getStart().truncatedTo(range.getGrain()), metric);
                }
            }
        } catch (DataAccessException e) {
            log.error("Could not read the traffic series; the chart will be empty: {}", e.getMessage());
        }

        List<Bucket> buckets = new ArrayList<>(range.getBuckets());

        for (int i = 0; i < range.getBuckets(); i++) {
            LocalDateTime start = from.plus(i, range.getGrain());
            SiteMetric metric = recorded.get(start);

            buckets.add(new Bucket(start,
                    range.formatAxis(start),
                    range.formatTooltip(start),
                    metric == null ? 0 : metric.getViews(),
                    metric == null ? 0 : metric.getVisitors(),
                    metric == null ? 0 : metric.getBannerImpressions(),
                    metric == null ? 0 : metric.getBannerClicks(),
                    metric == null ? Map.of() : metric.getViewsByPage(),
                    metric == null ? Map.of() : metric.getBannerImpressionsById(),
                    metric == null ? Map.of() : metric.getBannerViewableById(),
                    metric == null ? Map.of() : metric.getBannerClicksById()));
        }

        return buckets;
    }

    /**
     * Window totals for the traffic tiles. Readers are a peak rather than a sum -- see
     * {@link SiteMetric#getVisitors()}. The advertising tiles come from {@link #bannerReport}.
     */
    public Totals totals(List<Bucket> buckets) {
        long views = 0;
        long peakVisitors = 0;

        for (Bucket bucket : buckets) {
            views += bucket.views();
            peakVisitors = Math.max(peakVisitors, bucket.visitors());
        }

        return new Totals(views, peakVisitors);
    }

    /** Views and readers on one axis: the two quantities that answer "is the site being used". */
    public MgmtChart.Chart trafficChart(List<Bucket> buckets) {
        return MgmtChart.of(
                buckets.stream().map(Bucket::axisLabel).toList(),
                buckets.stream()
                        .map(b -> msg("ops.mon.tooltip.traffic", b.tooltipLabel(), b.views(), b.visitors()))
                        .toList(),
                // Series labels are bundle keys; mgmt/chart resolves them in the operator's language.
                List.of(
                        new MgmtChart.Input("views", "ops.mon.col.views", buckets.stream().map(Bucket::views).toList()),
                        new MgmtChart.Input("visitors", "ops.mon.series.visitors", buckets.stream().map(Bucket::visitors).toList())));
    }

    /**
     * Served, viewable and clicked, on a chart of their own -- for every booking, or for one.
     *
     * <p>Not more lines on {@link #trafficChart}: several banners render per page, so impressions
     * run an order of magnitude above views and clicks an order below, and one shared axis would
     * flatten most of the series into the baseline. Two charts with honest scales beat one chart
     * that has to be read with a caveat.
     *
     * <p>Unfiltered, served and clicked are each bucket's site-wide totals; viewable has no total
     * and is the sum of its map. Filtered to one booking, all three come from that booking's map
     * entries, under the {@code createdOn} guard. A booking with nothing in the window draws the
     * chart's empty state, never a flat zero line.
     */
    public MgmtChart.Chart bannerChart(List<Bucket> buckets, Banner selected, MonitoringRange range) {
        List<Long> served;
        List<Long> viewable;
        List<Long> clicks;

        if (selected == null) {
            served = buckets.stream().map(Bucket::bannerImpressions).toList();
            viewable = buckets.stream().map(b -> sum(b.viewableById())).toList();
            clicks = buckets.stream().map(Bucket::bannerClicks).toList();
        } else {
            ChronoUnit grain = range.getGrain();
            served = buckets.stream().map(b -> valueFor(b, Bucket::impressionsById, selected, grain)).toList();
            viewable = buckets.stream().map(b -> valueFor(b, Bucket::viewableById, selected, grain)).toList();
            clicks = buckets.stream().map(b -> valueFor(b, Bucket::clicksById, selected, grain)).toList();
        }

        List<String> tooltips = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            tooltips.add(msg("ops.mon.tooltip.banner", buckets.get(i).tooltipLabel(),
                    served.get(i), viewable.get(i), clicks.get(i)));
        }

        return MgmtChart.of(
                buckets.stream().map(Bucket::axisLabel).toList(),
                tooltips,
                List.of(
                        new MgmtChart.Input("impressions", "ops.mon.col.served", served),
                        new MgmtChart.Input("viewable", "ops.mon.col.viewable", viewable),
                        new MgmtChart.Input("clicks", "ops.mon.clicks", clicks)));
    }

    /** Views split across the four public views, summed over the window. */
    public List<PageTraffic> trafficByPage(List<Bucket> buckets) {
        Map<BannerPage, Long> counts = new HashMap<>();
        long total = 0;

        for (Bucket bucket : buckets) {
            for (BannerPage page : BannerPage.values()) {
                long count = bucket.viewsByPage().getOrDefault(page.name(), 0L);
                counts.merge(page, count, Long::sum);
                total += count;
            }
        }

        long finalTotal = total;
        return Arrays.stream(BannerPage.values())
                .map(page -> new PageTraffic(page, page.getLabel(), counts.getOrDefault(page, 0L),
                        share(counts.getOrDefault(page, 0L), finalTotal)))
                .sorted(Comparator.comparingLong(PageTraffic::views).reversed())
                .toList();
    }

    /* ------------------------------------------------------------------ overview tables */

    /**
     * The most-read articles, all-time.
     *
     * <p>Projected into a {@link org.bson.Document} rather than read as {@code Article}s on
     * purpose. That document eager-loads three {@code @DBRef} collections, so ten rows would be
     * thirty extra queries to fill four columns -- the cost {@code MgmtCategoryService} and the
     * image list already work around.
     *
     * <p>The number is {@code Article.counter}, which is all-time. It is not the window's views and
     * the screen must not present it as such.
     */
    public List<TopArticle> topArticles(int limit) {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, FIELD_COUNTER))
                .limit(limit);
        query.fields().include(FIELD_ID).include(FIELD_TITLE).include(FIELD_COUNTER).include(FIELD_DATE);

        try {
            return mongoTemplate.find(query, Document.class, ARTICLE_COLLECTION).stream()
                    .map(row -> new TopArticle(
                            asInt(row.get(FIELD_ID)),
                            row.getString(FIELD_TITLE),
                            asLong(row.get(FIELD_COUNTER))))
                    .toList();
        } catch (DataAccessException e) {
            log.error("Could not read the most-read articles; the table will be empty: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Every booking, read once per screen render and handed to both {@link #bannerReport} and
     * {@link #selected} -- the {@code MgmtCategoryService} arrangement, so the filter and the table
     * cannot disagree about which bookings exist.
     */
    public List<Banner> bookings() {
        try {
            return bannerRepository.findAll();
        } catch (DataAccessException e) {
            log.error("Could not read the banner bookings; the advertising table will be empty: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The booking a {@code ?banner=} parameter names, or {@code null} for none.
     *
     * <p>Lenient on purpose and normalised before it reaches the model: the value comes off a
     * query string, and a filter that echoed {@code ?banner=abc} -- or the id of a booking since
     * deleted -- would name a chart that is not drawing what it says. Read as a string so that a
     * non-number is no filter rather than a 400.
     */
    public static Banner selected(List<Banner> bookings, String banner) {
        if (banner == null || banner.isBlank()) {
            return null;
        }
        Integer id;
        try {
            id = Integer.valueOf(banner.strip());
        } catch (NumberFormatException e) {
            return null;
        }
        return bookings.stream().filter(b -> id.equals(b.getId())).findFirst().orElse(null);
    }

    /**
     * Every booking's served, viewable and clicked counts over the window, with the rates between
     * them, plus the window's totals for the tiles.
     *
     * <p>A booking that cannot be click-tracked -- an HTML5 one pointing off-origin, whose
     * document the public page cannot listen in -- reports no clicks and no rates, rendered as a
     * dash rather than a zero that would read as "nobody clicked". The site-wide rates are taken
     * over the click-tracked bookings only, so one such booking cannot dilute them, and
     * {@link BannerReport#partial()} says when that exclusion applied.
     */
    public BannerReport bannerReport(List<Bucket> buckets, List<Banner> bookings, MonitoringRange range) {
        ChronoUnit grain = range.getGrain();

        List<BannerStat> rows = new ArrayList<>(bookings.size());
        long served = 0;
        long viewable = 0;
        long clicks = 0;
        long trackedServed = 0;
        long trackedViewable = 0;
        long untrackedServed = 0;

        for (Banner banner : bookings) {
            if (banner.getId() == null) {
                continue;
            }
            long bannerServed = sumFor(buckets, Bucket::impressionsById, banner, grain);
            long bannerViewable = sumFor(buckets, Bucket::viewableById, banner, grain);
            boolean tracked = banner.isClickTracked();
            long bannerClicks = tracked ? sumFor(buckets, Bucket::clicksById, banner, grain) : 0;

            served += bannerServed;
            viewable += bannerViewable;
            clicks += bannerClicks;
            if (tracked) {
                trackedServed += bannerServed;
                trackedViewable += bannerViewable;
            } else {
                untrackedServed += bannerServed;
            }

            rows.add(new BannerStat(
                    banner.getId(),
                    banner.getName(),
                    banner.getType(),
                    banner.isActive(),
                    tracked,
                    bannerServed,
                    bannerViewable,
                    bannerClicks,
                    tracked ? ctr(bannerServed, bannerClicks) : null,
                    tracked ? ctr(bannerViewable, bannerClicks) : null,
                    share(bannerViewable, bannerServed)));
        }

        rows.sort(Comparator.comparingLong(BannerStat::impressions).reversed()
                .thenComparing(BannerStat::id));

        return new BannerReport(rows, served, viewable, clicks,
                ctr(trackedServed, clicks), ctr(trackedViewable, clicks), share(viewable, served),
                untrackedServed > 0);
    }

    /** What the platform holds. Cheap counts, no documents loaded. */
    public ContentTotals contentTotals() {
        try {
            // Every article, as the article list counts them: two totals that differ read as a bug
            // in whichever the operator trusts less.
            long articles = mongoTemplate.count(new Query(), ARTICLE_COLLECTION);

            return new ContentTotals(articles, authorRepository.count(), categoryRepository.count(),
                    imageRepository.count(), bannerRepository.count());
        } catch (DataAccessException e) {
            log.error("Could not read the content totals; they will show as zero: {}", e.getMessage());
            return new ContentTotals(0, 0, 0, 0, 0);
        }
    }

    /* ------------------------------------------------------------------ helpers */

    /** Click-through rate as a percentage, or {@code null} when there is nothing to divide by. */
    static Double ctr(long impressions, long clicks) {
        return impressions <= 0 ? null : (clicks * 100d) / impressions;
    }

    private static Double share(long part, long total) {
        return total <= 0 ? null : (part * 100d) / total;
    }

    /** One booking's entry in one bucket, or zero if the bucket opened before the booking existed. */
    private static long valueFor(Bucket bucket, Function<Bucket, Map<String, Long>> map, Banner banner,
                                 ChronoUnit grain) {
        LocalDateTime created = banner.getCreatedOn();
        // Ids are max-plus-one and therefore reused: what a bucket recorded under this id before
        // the booking was created belonged to whichever booking held the id then.
        if (created != null && bucket.start().isBefore(created.truncatedTo(grain))) {
            return 0;
        }
        Long value = map.apply(bucket).get(String.valueOf(banner.getId()));
        return value == null ? 0 : value;
    }

    private static long sumFor(List<Bucket> buckets, Function<Bucket, Map<String, Long>> map, Banner banner,
                               ChronoUnit grain) {
        long total = 0;
        for (Bucket bucket : buckets) {
            total += valueFor(bucket, map, banner, grain);
        }
        return total;
    }

    private static long sum(Map<String, Long> values) {
        long total = 0;
        for (Long value : values.values()) {
            total += value == null ? 0 : value;
        }
        return total;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /* ------------------------------------------------------------------ view models */

    /**
     * One point on the chart and one row behind it.
     *
     * @param axisLabel    short form for the axis
     * @param tooltipLabel long form for the hover band
     */
    public record Bucket(LocalDateTime start, String axisLabel, String tooltipLabel,
                         long views, long visitors, long bannerImpressions, long bannerClicks,
                         Map<String, Long> viewsByPage, Map<String, Long> impressionsById,
                         Map<String, Long> viewableById, Map<String, Long> clicksById) {
    }

    /** @param peakVisitors the busiest bucket, not a sum -- distinct readers do not add up. */
    public record Totals(long views, long peakVisitors) {
    }

    /**
     * @param available {@code false} when the query failed, which the screen must show as such
     *                  rather than as zero readers
     */
    public record LiveCount(int active, int windowMinutes, boolean available) {

        public static LiveCount unavailable() {
            return new LiveCount(0, 0, false);
        }
    }

    public record TopArticle(Integer id, String title, long counter) {
    }

    /**
     * One booking over the window.
     *
     * @param impressions              times served -- selected for a rendered page
     * @param viewable                 times at least half of it was on screen for a second
     * @param clickThroughRate         clicks per served, {@code null} when not click-tracked
     * @param viewableClickThroughRate clicks per viewable, {@code null} when not click-tracked
     * @param viewability              viewable per served, {@code null} when never served
     */
    public record BannerStat(Integer id, String name, BannerType type, boolean active, boolean clickTracked,
                             long impressions, long viewable, long clicks, Double clickThroughRate,
                             Double viewableClickThroughRate, Double viewability) {
    }

    /**
     * The advertising table and the tiles above it, over one window.
     *
     * @param partial whether a booking that cannot be click-tracked was served in the window, and
     *                was therefore left out of the two rates -- but not out of the counts
     */
    public record BannerReport(List<BannerStat> rows, long impressions, long viewable, long clicks,
                               Double clickThroughRate, Double viewableClickThroughRate, Double viewability,
                               boolean partial) {
    }

    public record PageTraffic(BannerPage page, String label, long views, Double share) {
    }

    public record ContentTotals(long articles, long authors, long categories, long images, long banners) {
    }
}
