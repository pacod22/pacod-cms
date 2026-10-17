package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One bucket of site traffic: everything the monitoring screen charts, for one span of time.
 *
 * <p>This is the only dated record of page views the platform keeps. {@code Article.counter},
 * {@code Banner.impressions} and {@code Banner.clicks} are all-time scalars with no date attached,
 * and nothing else records a view by time, so <b>none of this can be back-filled</b>. A website's
 * series starts when the site first counts it and fills forward.
 *
 * <h2>Two grains, one shape</h2>
 * {@link SiteMetricHour} keeps thirty days and expires; {@link SiteMetricDay} is kept forever. The
 * fields live here so the two cannot drift, which matters because a flush writes <em>both</em> in
 * the same cycle -- that is why there is no nightly rollup job, and why the two grains cannot
 * disagree about a day's totals.
 *
 * <h2>The id is computed from the clock, never assigned</h2>
 * Most documents in this project take their id from a max-plus-one read ({@code getMaxId}),
 * defensible only where creation is rare. A traffic bucket is
 * written every flush interval forever, so the id is a deterministic string derived from
 * {@link #start} and a flush is a plain upsert on it. No read, no collision.
 *
 * <h2>The writer does not use this class</h2>
 * {@code SiteMetricCollector} in the public site writes through the raw driver with the
 * {@code FIELD_*} constants below, exactly as {@code BannerImpressionCounter} and
 * {@code ArticleViewCounter} do -- a buffered {@code $inc}, never a {@code save()} of a whole
 * document. This class exists for the console to <em>read</em> into. Keeping the field names here
 * is what stops the two halves, which live in different applications, from drifting apart.
 */
@Data
public abstract class SiteMetric {

    public static final String FIELD_START = "start";
    public static final String FIELD_VIEWS = "views";
    public static final String FIELD_VISITORS = "visitors";
    public static final String FIELD_BANNER_IMPRESSIONS = "bannerImpressions";
    public static final String FIELD_BANNER_CLICKS = "bannerClicks";
    public static final String FIELD_VIEWS_BY_PAGE = "viewsByPage";
    public static final String FIELD_BANNER_IMPRESSIONS_BY_ID = "bannerImpressionsById";
    public static final String FIELD_BANNER_VIEWABLE_BY_ID = "bannerViewableById";
    public static final String FIELD_BANNER_CLICKS_BY_ID = "bannerClicksById";
    public static final String FIELD_SOCIAL_VIEWS_BY_SOURCE = "socialViewsBySource";
    public static final String FIELD_SOCIAL_CLICKS = "socialClicks";
    public static final String FIELD_SOCIAL_ARTICLE_CLICKS = "socialArticleClicks";
    public static final String FIELD_SOCIAL_VISITORS = "socialVisitors";
    public static final String FIELD_CRAWLER_HITS_BY_BOT = "crawlerHitsByBot";
    public static final String FIELD_CRAWLER_ERRORS_BY_BOT = "crawlerErrorsByBot";
    public static final String FIELD_CRAWLER_VERIFIED_BY_BOT = "crawlerVerifiedByBot";
    public static final String FIELD_CRAWLER_UNVERIFIED_BY_BOT = "crawlerUnverifiedByBot";

    /** Bucket key: {@code "2026-08-09T14"} for an hour, {@code "2026-08-09"} for a day. */
    @Id
    private String id;

    /**
     * The instant the bucket opens, truncated to its grain.
     *
     * <p>Declared by each subclass rather than here, and this is the one place the two shapes are
     * deliberately allowed to differ: {@link SiteMetricHour} needs a TTL index on it and
     * {@link SiteMetricDay} must not have one, and {@code @Indexed(expireAfter)} is a field
     * annotation with no class-level equivalent. Redeclaring the field in one subclass and
     * inheriting it in the other would shadow it and silently write two.
     */
    public abstract LocalDateTime getStart();

    /**
     * Public page renders. Not {@code Article.counter}, which is all-time. Renders for crawlers
     * and other automated clients are not in here; they are counted in the crawler maps below.
     */
    private long views;

    /**
     * Distinct readers in this bucket.
     *
     * <p>Deliberately <b>not</b> additive: summing the twenty-four hours of a day double-counts
     * anyone who read twice. The hourly figure is a {@code $max} of what the site has seen
     * since the hour opened; the daily figure is counted straight off the {@code siteVisitor}
     * collection. See {@code SiteMetricCollector} for why those two mechanisms differ.
     */
    private long visitors;

    /**
     * Every banner served in this bucket, summed: what the unfiltered chart draws, kept beside the
     * per-booking maps below.
     */
    private long bannerImpressions;

    /** Every banner click in this bucket, summed. See {@link #bannerImpressions}. */
    private long bannerClicks;

    /**
     * {@link BannerPage} name to render count.
     *
     * <p>A map rather than four columns because {@code BannerPage} is the classification
     * {@code ArticleTemplateController} already applies to every public route, and adding a page
     * type should not be a schema change. Absent keys are zero; a bucket written before a page
     * type existed simply does not mention it.
     */
    private Map<String, Long> viewsByPage = new LinkedHashMap<>();

    /**
     * Banner id, as a decimal string, to the times it was <em>served</em> -- selected for a
     * rendered page, the definition {@link #bannerImpressions} has.
     *
     * <p>The three per-booking maps live inside the buckets rather than in a collection of their
     * own, the way {@link #viewsByPage} does. That buys the hourly TTL, the
     * day-equals-sum-of-hours property and all four of the monitoring screen's ranges for nothing,
     * and adds no index -- one more index to build for every website, and one more that could
     * fail to build.
     *
     * <p>Keyed by string because MongoDB field names are strings: a dotted
     * {@code $inc} on {@code bannerImpressionsById.12} creates an embedded <em>document</em> with
     * the key {@code "12"}, never an array, because the parent is always created by that same
     * {@code $inc}. Ids are reused (max-plus-one), so a reader must not attribute a bucket written
     * before a banner's {@code createdOn} to that banner.
     */
    private Map<String, Long> bannerImpressionsById = new LinkedHashMap<>();

    /**
     * Banner id to the times it was <em>viewable</em>: at least half of the creative on screen,
     * in a visible tab, for a continuous second, counted at most once per page view. Reported by
     * the reader's browser ({@code site.js}), so a reader without JavaScript -- and nearly every
     * bot -- contributes nothing here.
     */
    private Map<String, Long> bannerViewableById = new LinkedHashMap<>();

    /** Banner id to clicks: image banners through {@code /b/{id}}, HTML5 ones by beacon. */
    private Map<String, Long> bannerClicksById = new LinkedHashMap<>();

    /**
     * {@code /social} renders by {@link SocialSource} name -- which network the visit came from.
     *
     * <p>Lives in the buckets for the reason the per-banner maps do: the TTL, the
     * day-equals-sum-of-hours property and every range come for nothing, and no index is added.
     * The sum of this map is the page's views; it agrees with {@code viewsByPage.SOCIAL} because the
     * collector writes both from the same call.
     */
    private Map<String, Long> socialViewsBySource = new LinkedHashMap<>();

    /**
     * {@code /social} link clicks: link id to {@link SocialSource} name to count -- a nested dotted
     * {@code $inc} on {@code socialClicks.k3x9a2bq.INSTAGRAM}. Link ids are generated and never
     * reused (see {@code SocialLink}), so unlike the banner maps a reader needs no
     * {@code createdOn} guard. The per-link and per-source totals are both marginals of this one
     * matrix, so they cannot disagree with each other.
     */
    private Map<String, Map<String, Long>> socialClicks = new LinkedHashMap<>();

    /** The latest-articles block's clicks: article id, as a string, to source name to count. */
    private Map<String, Map<String, Long>> socialArticleClicks = new LinkedHashMap<>();

    /**
     * Distinct readers of {@code /social} in this bucket. A {@code $max} of what the site
     * has seen since the bucket opened, on <em>both</em> grains -- unlike {@link #visitors}, whose
     * daily figure is counted off {@code siteVisitor}, a collection that deliberately stores no
     * path. So a restart mid-day undercounts the day; it never overcounts.
     */
    private long socialVisitors;

    /**
     * Requests from crawlers and other automated clients, by {@code CrawlerClassifier} bot id --
     * every status, every path the public site handles. None of them is in {@link #views}: an
     * automated request is counted here and only here.
     *
     * <p>There is no per-category map. Each bot id belongs to exactly one category, so the
     * console derives the category totals through {@code CrawlerClassifier.categoryOf}, which is
     * total -- an id removed from the table in some later edit still lands in
     * {@link CrawlerCategory#OTHER} rather than vanishing from a twelve-month range.
     */
    private Map<String, Long> crawlerHitsByBot = new LinkedHashMap<>();

    /** Of {@link #crawlerHitsByBot}, the ones answered with a status of 400 or more. */
    private Map<String, Long> crawlerErrorsByBot = new LinkedHashMap<>();

    /** Of {@link #crawlerHitsByBot}, the ones whose address reverse- and forward-resolved to the operator. */
    private Map<String, Long> crawlerVerifiedByBot = new LinkedHashMap<>();

    /** Of {@link #crawlerHitsByBot}, the ones whose address was shown <em>not</em> to belong to it: forged. */
    private Map<String, Long> crawlerUnverifiedByBot = new LinkedHashMap<>();

    /** Never null, so the console can sum without guarding every bucket. */
    public Map<String, Long> getViewsByPage() {
        return viewsByPage == null ? Map.of() : viewsByPage;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getBannerImpressionsById() {
        return bannerImpressionsById == null ? Map.of() : bannerImpressionsById;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getBannerViewableById() {
        return bannerViewableById == null ? Map.of() : bannerViewableById;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getBannerClicksById() {
        return bannerClicksById == null ? Map.of() : bannerClicksById;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getSocialViewsBySource() {
        return socialViewsBySource == null ? Map.of() : socialViewsBySource;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Map<String, Long>> getSocialClicks() {
        return socialClicks == null ? Map.of() : socialClicks;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Map<String, Long>> getSocialArticleClicks() {
        return socialArticleClicks == null ? Map.of() : socialArticleClicks;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getCrawlerHitsByBot() {
        return crawlerHitsByBot == null ? Map.of() : crawlerHitsByBot;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getCrawlerErrorsByBot() {
        return crawlerErrorsByBot == null ? Map.of() : crawlerErrorsByBot;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getCrawlerVerifiedByBot() {
        return crawlerVerifiedByBot == null ? Map.of() : crawlerVerifiedByBot;
    }

    /** Never null; see {@link #getViewsByPage()}. */
    public Map<String, Long> getCrawlerUnverifiedByBot() {
        return crawlerUnverifiedByBot == null ? Map.of() : crawlerUnverifiedByBot;
    }

    /** Automated requests in this bucket, every bot. */
    public long crawlerHitTotal() {
        return sum(getCrawlerHitsByBot());
    }

    /** {@code /social} renders in this bucket, all sources. */
    public long socialViewTotal() {
        return sum(getSocialViewsBySource());
    }

    /** Clicks on every link and every article tile in this bucket, all sources. */
    public long socialClickTotal() {
        long total = 0;
        for (Map<String, Long> bySource : getSocialClicks().values()) {
            total += sum(bySource);
        }
        for (Map<String, Long> bySource : getSocialArticleClicks().values()) {
            total += sum(bySource);
        }
        return total;
    }

    private static long sum(Map<String, Long> counts) {
        long total = 0;
        if (counts != null) {
            for (Long count : counts.values()) {
                total += count == null ? 0 : count;
            }
        }
        return total;
    }

    public long viewsOn(BannerPage page) {
        return getViewsByPage().getOrDefault(page.name(), 0L);
    }
}
