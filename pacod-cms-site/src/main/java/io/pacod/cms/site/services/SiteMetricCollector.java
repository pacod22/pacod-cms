package io.pacod.cms.site.services;

import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.SiteMetric;
import io.pacod.cms.core.data.SiteMetricDay;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.data.SiteVisitor;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.repository.SiteVisitorRepository;
import io.pacod.cms.core.tenant.PerSite;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Records what the monitoring screen charts: page views, readers, and banner activity over time.
 *
 * <p>Modelled on {@link BannerImpressionCounter}, which sits beside it and whose javadoc argues the
 * case in full. The short version: a write per page render is the anti-pattern
 * {@code ArticleViewCounter}'s javadoc exists to kill, so everything accumulates in memory and a
 * scheduled flush turns it into a handful of upserts. Steady-state cost is a constant number of
 * writes per interval regardless of traffic.
 *
 * <h2>Why this class is in the site application and the screen is not</h2>
 * The public site and the studio are two JVMs sharing one MongoDB -- the same topology fact that
 * makes {@code CacheConfig} use TTLs instead of {@code @CacheEvict}. Nothing held here is visible
 * to {@code pacod-cms-studio}, so MongoDB is not an implementation choice, it is the only channel.
 * That is also why {@code SiteMetricHour} and friends live in {@code core.data}: the studio
 * enumerates exactly that package and would not map a document declared anywhere else.
 *
 * <h2>One set of buffers per website</h2>
 * Everything below is kept per website ({@link PerSite}): filled under the website the request
 * was resolved to, and flushed with that website bound again, so each site's buckets land in its
 * own database. Bucket ids still follow this JVM's zone, as the studio's reader does.
 *
 * <h2>What a flush writes, and why in that order</h2>
 * <ol>
 *   <li>the drained visitor heartbeats, one bulk write -- at most one row touched per active
 *       reader per interval;</li>
 *   <li>the additive counters, {@code $inc}ed into the hour bucket <b>and</b> the day bucket in the
 *       same cycle. That is why there is no nightly rollup job and why the two grains cannot
 *       disagree;</li>
 *   <li>the reader counts, which are <b>not</b> additive and so are not {@code $inc}ed at all --
 *       see below.</li>
 * </ol>
 * The heartbeats go first because step 3 counts the rows step 1 has just written.
 *
 * <h2>Two different mechanisms for one word, "visitors"</h2>
 * Distinct readers do not sum: someone reading at 09:15 and again at 14:40 is one visitor that
 * day, not two. So:
 * <ul>
 *   <li><b>Hourly</b> is a {@code $max} of the hash set this process has accumulated since the
 *       hour opened. {@code $max} rather than {@code $set} because the set lives in memory: a
 *       restart mid-hour rebuilds it from empty, and {@code $set} would overwrite a real count
 *       with a small one. With {@code $max}, a restart is an undercount at worst.</li>
 *   <li><b>Daily</b> is exact -- a {@code countDocuments} over {@code siteVisitor}, which holds one
 *       row per reader per day precisely so this can be a count rather than a guess. It survives a
 *       restart because it is derived from the collection, not from memory.</li>
 * </ul>
 * {@code hourlyHashes} is keyed by hour and therefore a <em>separate</em> structure from
 * {@code visitorBuffer}, which step 1 drains. Reading a drained buffer in step 3 would silently
 * turn the hourly figure into "readers since the last flush".
 *
 * <h2>Accepted imprecision, stated plainly</h2>
 * Counts are attributed to the bucket open at <em>flush</em> time, so a flush straddling an hour
 * boundary credits up to one interval of the previous hour to the next one. Counts since the last
 * flush are lost on an unclean exit; a clean shutdown flushes on {@link ContextClosedEvent}. Both
 * are the same trade {@code BannerImpressionCounter} already makes: these numbers are for
 * reporting, not billing.
 */
@Slf4j
@Component
public class SiteMetricCollector {

    /**
     * One buffer per grain, holding the same numbers twice.
     *
     * <p>Deliberately not one shared buffer drained into both collections. Two collections cannot
     * be written in one bulk write, so a flush that succeeded on the hour and failed on the day
     * would have to choose between losing the day's interval and returning the counts to a buffer
     * the <em>hour</em> has already consumed -- which would double-count it on the retry, forever,
     * with nothing on any screen to show for it. Separate buffers make each grain's retry its own.
     *
     * <p>The cost is two {@code LongAdder} increments per event instead of one, which is nothing
     * next to the render that produced it.
     */
    /**
     * A bot id becomes a key in a dotted {@code $inc}, so anything that is not the shape
     * {@code CrawlerClassifier} produces is dropped -- the rule {@code SocialPage.isLinkId} applies
     * to link ids.
     */
    private static final Pattern BOT_ID = Pattern.compile("[a-z0-9-]{1,64}");

    /**
     * Everything one website has pending. A flush drains each website's buffers with that
     * website bound, so the routing template writes them into its own database -- see
     * {@link PerSite}.
     */
    private static final class Buffers {

        private final Counters hourly = new Counters();
        private final Counters daily = new Counters();

        /** Reader hash to when they were last seen. Drained every flush. */
        private final Map<String, LocalDateTime> visitorBuffer = new ConcurrentHashMap<>();

        /**
         * Hour bucket id to the reader hashes seen in it.
         *
         * <p>Keyed by hour rather than cleared on rollover, so the last few seconds of an hour still
         * reach that hour's bucket. Holds two entries at an hour boundary and one otherwise; a flush
         * writes every entry and then drops all but the current hour.
         */
        private final Map<String, Set<String>> hourlyHashes = new ConcurrentHashMap<>();

        /**
         * {@code /social}'s readers, by hour id and by day id -- the same in-memory shape as
         * {@code hourlyHashes}, kept apart from it because they count a different page, and written
         * with {@code $max} on <em>both</em> grains. The site-wide daily figure is counted off
         * {@code siteVisitor} instead, but that collection deliberately stores no path, so it cannot
         * say who read this one page. The price is the one the hourly figure already pays: a restart
         * mid-day undercounts the day, and never overcounts it.
         */
        private final Map<String, Set<String>> socialHourlyHashes = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> socialDailyHashes = new ConcurrentHashMap<>();
    }

    private final PerSite<Buffers> sites = new PerSite<>(Buffers::new);

    private final MongoTemplate mongoTemplate;
    private final SiteVisitorRepository visitorRepository;
    private final VisitorIdentity visitorIdentity;
    private final boolean enabled;

    /**
     * A deleted website's counts are discarded rather than flushed: the first write would recreate
     * the database the studio just dropped. Optional so a test can build this class by hand.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useLiveness(io.pacod.cms.core.tenant.SiteLiveness liveness) {
        sites.skipWhen(liveness::isGone);
    }

    public SiteMetricCollector(MongoTemplate mongoTemplate,
                               SiteVisitorRepository visitorRepository,
                               VisitorIdentity visitorIdentity,
                               @Value("${app.metrics.enabled:true}") boolean enabled) {
        this.mongoTemplate = mongoTemplate;
        this.visitorRepository = visitorRepository;
        this.visitorIdentity = visitorIdentity;
        this.enabled = enabled;
    }

    /**
     * Records one render of a public page. Called from the request thread, so it never touches the
     * network and never throws.
     *
     * @param page    which of the four public views rendered; {@code null} is counted as a view but
     *                lands in no page column
     * @param request the current request, read only to derive an opaque reader key -- and to see
     *                whether {@code CrawlerTrafficFilter} marked it as automated, in which case it is
     *                not a reader and nothing is counted. The check is here, at the one choke point,
     *                rather than in the four controllers that call this.
     */
    public void recordPageView(BannerPage page, HttpServletRequest request) {
        if (enabled && !CrawlerTraffic.isUncounted(request)) {
            sites.current().ifPresent(b -> recordView(b, page, request));
        }
    }

    /**
     * One request from a crawler or another automated client, with what it was answered and
     * whether its address checked out. Called from {@code CrawlerRecorder}, after the DNS check --
     * so off the request thread, which these buffers are safe for.
     *
     * @param bot the {@code CrawlerClassifier} id; anything not of that shape is dropped
     */
    public void recordCrawlerHit(String bot, int status, CrawlerVerification verification) {
        if (enabled && bot != null && BOT_ID.matcher(bot).matches()) {
            sites.current().ifPresent(b -> {
                b.hourly.recordCrawlerHit(bot, status, verification);
                b.daily.recordCrawlerHit(bot, status, verification);
            });
        }
    }

    /**
     * One render of {@code /social}: a page view like any other -- under
     * {@link BannerPage#SOCIAL}, so the site's totals and the monitoring screen's page table
     * include it -- plus the network it came from and the reader, for the page's own statistics.
     *
     * <p>Both go through this one call so the per-source views always sum to
     * {@code viewsByPage.SOCIAL}. The caller has already left out bots and self-referrals; this
     * method counts what it is given.
     */
    public void recordSocialView(SocialSource source, HttpServletRequest request) {
        if (!enabled) {
            return;
        }

        sites.current().ifPresent(b -> {
            SocialSource from = source == null ? SocialSource.DIRECT : source;
            b.hourly.recordSocialView(from);
            b.daily.recordSocialView(from);

            String hash = recordView(b, BannerPage.SOCIAL, request);
            if (hash != null) {
                LocalDateTime now = LocalDateTime.now();
                b.socialHourlyHashes.computeIfAbsent(SiteMetricHour.idFor(now), h -> ConcurrentHashMap.newKeySet())
                        .add(hash);
                b.socialDailyHashes.computeIfAbsent(SiteMetricDay.idFor(now), d -> ConcurrentHashMap.newKeySet())
                        .add(hash);
            }
        });
    }

    /**
     * A click on a {@code /social} link. The id becomes a key in a dotted {@code $inc}, so
     * anything that is not the generated shape -- see {@code SocialLink} -- is dropped here
     * rather than trusted to the caller.
     */
    public void recordSocialClick(String linkId, SocialSource source) {
        if (enabled && SocialPage.isLinkId(linkId)) {
            SocialSource from = source == null ? SocialSource.DIRECT : source;
            sites.current().ifPresent(b -> {
                b.hourly.recordSocialClick(linkId, from);
                b.daily.recordSocialClick(linkId, from);
            });
        }
    }

    /** A click on one of the latest-articles tiles on {@code /social}. */
    public void recordSocialArticleClick(int articleId, SocialSource source) {
        if (enabled && articleId > 0) {
            SocialSource from = source == null ? SocialSource.DIRECT : source;
            sites.current().ifPresent(b -> {
                b.hourly.recordSocialArticleClick(articleId, from);
                b.daily.recordSocialArticleClick(articleId, from);
            });
        }
    }

    /** Counts the render and remembers the reader; returns the reader's hash, or {@code null}. */
    private String recordView(Buffers b, BannerPage page, HttpServletRequest request) {
        b.hourly.recordView(page);
        b.daily.recordView(page);

        String hash = visitorIdentity.hashOf(request);
        if (hash == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        b.visitorBuffer.put(hash, now);
        b.hourlyHashes.computeIfAbsent(SiteMetricHour.idFor(now), h -> ConcurrentHashMap.newKeySet())
                .add(hash);
        return hash;
    }

    /**
     * One banner served: the site-wide total and that booking's own entry in the bucket.
     *
     * <p>Called from {@link BannerImpressionCounter} rather than from {@code BannerService},
     * because that class is the single choke point for impressions, viewable impressions and
     * clicks -- so the aggregate on the monitoring screen cannot drift from the per-booking numbers
     * on {@code /mgmt/banners}.
     */
    public void recordBannerImpression(Integer bannerId) {
        if (enabled) {
            sites.current().ifPresent(b -> {
                b.hourly.recordImpression(bannerId);
                b.daily.recordImpression(bannerId);
            });
        }
    }

    /**
     * One banner seen: at least half of it on screen for a second, reported by the reader's
     * browser. Per booking only -- there is no site-wide scalar for it, because there are no
     * buckets from before it to keep readable. Same choke point as
     * {@link #recordBannerImpression(Integer)}.
     */
    public void recordBannerViewable(Integer bannerId) {
        if (enabled && bannerId != null) {
            sites.current().ifPresent(b -> {
                b.hourly.recordViewable(bannerId);
                b.daily.recordViewable(bannerId);
            });
        }
    }

    /** One banner click. Same choke point as {@link #recordBannerImpression(Integer)}. */
    public void recordBannerClick(Integer bannerId) {
        if (enabled) {
            sites.current().ifPresent(b -> {
                b.hourly.recordClick(bannerId);
                b.daily.recordClick(bannerId);
            });
        }
    }

    @Scheduled(fixedDelayString = "${app.metrics.flush.ms:60000}",
               initialDelayString = "${app.metrics.flush.ms:60000}")
    public void flush() {
        flushPending();
    }

    /** Best-effort flush so an orderly restart does not drop the last interval's traffic. */
    @EventListener(ContextClosedEvent.class)
    public void flushOnShutdown() {
        flushPending();
    }

    private void flushPending() {
        if (!enabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        sites.forEach(b -> {
            flushVisitors(b);
            flushCounters(b, now);
            flushVisitorCounts(b, now);
            flushSocialVisitorCounts(b, now);
        });
    }

    /**
     * Writes one heartbeat row per reader seen since the last flush.
     *
     * <p>{@code $setOnInsert} for the day and the hash, {@code $set} for the timestamp: the TTL is
     * anchored on {@code day}, so rewriting it would let a reader who never leaves keep their row
     * alive indefinitely. See {@link SiteVisitor}.
     */
    private void flushVisitors(Buffers b) {
        if (b.visitorBuffer.isEmpty()) {
            return;
        }

        List<WriteModel<Document>> writes = new ArrayList<>();
        Map<String, LocalDateTime> drained = new HashMap<>();

        for (Iterator<Map.Entry<String, LocalDateTime>> it = b.visitorBuffer.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LocalDateTime> entry = it.next();
            it.remove();

            String hash = entry.getKey();
            LocalDateTime seen = entry.getValue();
            drained.put(hash, seen);

            writes.add(new UpdateOneModel<>(
                    new Document("_id", SiteVisitor.idFor(hash, seen)),
                    new Document("$set", new Document(SiteVisitor.FIELD_LAST_SEEN, toDate(seen)))
                            .append("$setOnInsert", new Document(SiteVisitor.FIELD_HASH, hash)
                                    .append(SiteVisitor.FIELD_DAY, toDate(seen.truncatedTo(ChronoUnit.DAYS)))),
                    new UpdateOptions().upsert(true)));
        }

        if (writes.isEmpty()) {
            return;
        }

        try {
            mongoTemplate.getCollection(SiteVisitor.COLLECTION).bulkWrite(writes);
            log.debug("Flushed {} visitor heartbeats", writes.size());
        } catch (RuntimeException e) {
            log.warn("Failed to flush {} visitor heartbeats; returning them to the buffer: {}",
                    writes.size(), e.getMessage());
            // Newer sightings win: an entry that arrived while the write was in flight is later
            // than the one being restored, and lastSeen is what the live count reads.
            drained.forEach((hash, seen) -> b.visitorBuffer.merge(hash, seen,
                    (existing, restored) -> existing.isAfter(restored) ? existing : restored));
        }
    }

    /**
     * Applies the additive counters to both grains.
     *
     * <p>The two updates carry identical numbers and differ only in collection and bucket key,
     * which is the whole reason a day cannot disagree with the sum of its hours -- and why there
     * is no nightly rollup job. Each is drained and retried independently; see {@code Buffers.hourly}.
     */
    private void flushCounters(Buffers b, LocalDateTime now) {
        flushGrain(b.hourly, SiteMetricHour.COLLECTION, SiteMetricHour.idFor(now),
                now.truncatedTo(ChronoUnit.HOURS));
        flushGrain(b.daily, SiteMetricDay.COLLECTION, SiteMetricDay.idFor(now),
                now.truncatedTo(ChronoUnit.DAYS));
    }

    private void flushGrain(Counters counters, String collection, String id, LocalDateTime start) {
        Drained drained = counters.drain();

        if (drained.increments().isEmpty()) {
            return;
        }

        try {
            upsert(collection, id, new Document("$inc", drained.increments()), start);
            log.debug("Flushed {} into {}", drained.increments().toJson(), collection);
        } catch (RuntimeException e) {
            // A failed flush costs a retry, not the traffic -- the contract
            // BannerImpressionCounter.flushPending already keeps.
            log.warn("Failed to flush site metrics into {}; returning them to the buffer: {}",
                    collection, e.getMessage());
            counters.restore(drained);
        }
    }

    /**
     * Writes the two reader counts, neither of which is an {@code $inc}.
     *
     * <p>A failure here is logged and dropped rather than buffered: both figures are recomputed
     * from scratch on the next flush -- the hourly one from a set that is still in memory, the
     * daily one from the collection -- so there is nothing a retry would recover that the next
     * cycle does not.
     */
    private void flushVisitorCounts(Buffers b, LocalDateTime now) {
        String currentHour = SiteMetricHour.idFor(now);

        for (Map.Entry<String, Set<String>> entry : b.hourlyHashes.entrySet()) {
            String hourId = entry.getKey();
            long distinct = entry.getValue().size();

            if (distinct > 0) {
                try {
                    upsert(SiteMetricHour.COLLECTION, hourId,
                            new Document("$max", new Document(SiteMetric.FIELD_VISITORS, distinct)),
                            hourStartOf(hourId, now));
                } catch (RuntimeException e) {
                    log.warn("Failed to write the hourly reader count for {}: {}", hourId, e.getMessage());
                }
            }

            // Past hours are complete once written; only the open one keeps accumulating.
            if (!hourId.equals(currentHour)) {
                b.hourlyHashes.remove(hourId);
            }
        }

        try {
            LocalDateTime day = now.truncatedTo(ChronoUnit.DAYS);
            long distinctToday = visitorRepository.countByDay(day);
            if (distinctToday > 0) {
                upsert(SiteMetricDay.COLLECTION, SiteMetricDay.idFor(now),
                        new Document("$set", new Document(SiteMetric.FIELD_VISITORS, distinctToday)), day);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to write the daily reader count: {}", e.getMessage());
        }
    }

    /**
     * One upsert on a bucket, adding {@code start} only when the document is created.
     *
     * <p>{@code $setOnInsert} rather than {@code $set} because {@code start} is the TTL anchor on
     * the hourly collection: rewriting it every flush would push a bucket's expiry forward for as
     * long as the site had traffic, and thirty days would quietly become forever.
     */
    /**
     * {@code $max socialVisitors} into every bucket with readers, then forgets the buckets that
     * have closed. See {@code socialHourlyHashes} for why both grains use {@code $max}.
     */
    private void flushSocialVisitorCounts(Buffers b, LocalDateTime now) {
        flushDistinct(b.socialHourlyHashes, SiteMetricHour.COLLECTION, SiteMetricHour.idFor(now),
                id -> hourStartOf(id, now));
        flushDistinct(b.socialDailyHashes, SiteMetricDay.COLLECTION, SiteMetricDay.idFor(now),
                id -> dayStartOf(id, now));
    }

    private void flushDistinct(Map<String, Set<String>> hashes, String collection, String openId,
                               java.util.function.Function<String, LocalDateTime> startOf) {
        for (Map.Entry<String, Set<String>> entry : hashes.entrySet()) {
            String bucketId = entry.getKey();
            long distinct = entry.getValue().size();

            if (distinct > 0) {
                try {
                    upsert(collection, bucketId,
                            new Document("$max", new Document(SiteMetric.FIELD_SOCIAL_VISITORS, distinct)),
                            startOf.apply(bucketId));
                } catch (RuntimeException e) {
                    log.warn("Failed to write the /social reader count for {}: {}", bucketId, e.getMessage());
                }
            }

            if (!bucketId.equals(openId)) {
                hashes.remove(bucketId);
            }
        }
    }

    private void upsert(String collection, String id, Document operation, LocalDateTime start) {
        Document update = new Document(operation)
                .append("$setOnInsert", new Document(SiteMetric.FIELD_START, toDate(start)));

        mongoTemplate.getCollection(collection)
                .updateOne(new Document("_id", id), update, new UpdateOptions().upsert(true));
    }

    private static void appendIfPositive(Document target, String field, long count) {
        if (count > 0) {
            target.append(field, count);
        }
    }

    /** What one drain produced: the update to send, and the counts to put back if it fails. */
    private record Drained(Document increments, long views, long impressions, long clicks,
                           Map<BannerPage, Long> pages, Map<Integer, Long> impressionsById,
                           Map<Integer, Long> viewableById, Map<Integer, Long> clicksById,
                           Map<SocialSource, Long> socialViews, Map<SocialKey, Long> socialClicks,
                           Map<SocialKey, Long> socialArticleClicks, Map<String, Long> crawlerHits,
                           Map<String, Long> crawlerErrors, Map<String, Long> crawlerVerified,
                           Map<String, Long> crawlerUnverified) {
    }

    /** One cell of a {@code /social} click matrix: a link id or an article id, and a source. */
    private record SocialKey(String target, SocialSource source) {
    }

    /**
     * One grain's pending counts.
     *
     * <p>{@code LongAdder} rather than {@code AtomicLong} throughout, for the reason
     * {@code BannerImpressionCounter} gives: these are written far more often than they are read.
     */
    private static final class Counters {

        private final LongAdder views = new LongAdder();
        private final LongAdder bannerImpressions = new LongAdder();
        private final LongAdder bannerClicks = new LongAdder();
        private final Map<BannerPage, LongAdder> viewsByPage = new ConcurrentHashMap<>();
        private final Map<Integer, LongAdder> impressionsById = new ConcurrentHashMap<>();
        private final Map<Integer, LongAdder> viewableById = new ConcurrentHashMap<>();
        private final Map<Integer, LongAdder> clicksById = new ConcurrentHashMap<>();
        private final Map<SocialSource, LongAdder> socialViews = new ConcurrentHashMap<>();
        private final Map<SocialKey, LongAdder> socialClicks = new ConcurrentHashMap<>();
        private final Map<SocialKey, LongAdder> socialArticleClicks = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> crawlerHits = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> crawlerErrors = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> crawlerVerified = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> crawlerUnverified = new ConcurrentHashMap<>();

        void recordCrawlerHit(String bot, int status, CrawlerVerification verification) {
            crawlerHits.computeIfAbsent(bot, b -> new LongAdder()).increment();
            if (status >= 400) {
                crawlerErrors.computeIfAbsent(bot, b -> new LongAdder()).increment();
            }
            if (verification == CrawlerVerification.VERIFIED) {
                crawlerVerified.computeIfAbsent(bot, b -> new LongAdder()).increment();
            } else if (verification == CrawlerVerification.UNVERIFIED) {
                crawlerUnverified.computeIfAbsent(bot, b -> new LongAdder()).increment();
            }
        }

        void recordSocialView(SocialSource source) {
            socialViews.computeIfAbsent(source, s -> new LongAdder()).increment();
        }

        void recordSocialClick(String linkId, SocialSource source) {
            socialClicks.computeIfAbsent(new SocialKey(linkId, source), k -> new LongAdder()).increment();
        }

        void recordSocialArticleClick(int articleId, SocialSource source) {
            socialArticleClicks.computeIfAbsent(new SocialKey(Integer.toString(articleId), source),
                    k -> new LongAdder()).increment();
        }

        void recordView(BannerPage page) {
            views.increment();

            if (page != null) {
                viewsByPage.computeIfAbsent(page, p -> new LongAdder()).increment();
            }
        }

        void recordImpression(Integer bannerId) {
            bannerImpressions.increment();
            incrementFor(impressionsById, bannerId);
        }

        void recordViewable(Integer bannerId) {
            incrementFor(viewableById, bannerId);
        }

        void recordClick(Integer bannerId) {
            bannerClicks.increment();
            incrementFor(clicksById, bannerId);
        }

        private static void incrementFor(Map<Integer, LongAdder> counts, Integer bannerId) {
            if (bannerId != null) {
                counts.computeIfAbsent(bannerId, id -> new LongAdder()).increment();
            }
        }

        /**
         * Empties the buffer and builds the update it becomes.
         *
         * <p>{@code sumThenReset} rather than remove-then-write, so a view arriving mid-drain
         * lands in the next interval rather than being lost. The per-page entries stay in the map
         * at zero, which is what keeps the hot path free of repeated map insertions.
         */
        Drained drain() {
            long viewCount = views.sumThenReset();
            long impressionCount = bannerImpressions.sumThenReset();
            long clickCount = bannerClicks.sumThenReset();

            Map<BannerPage, Long> pages = new EnumMap<>(BannerPage.class);
            viewsByPage.forEach((page, adder) -> {
                long count = adder.sumThenReset();
                if (count > 0) {
                    pages.put(page, count);
                }
            });

            Map<Integer, Long> impressions = drainEach(impressionsById);
            Map<Integer, Long> viewable = drainEach(viewableById);
            Map<Integer, Long> clicks = drainEach(clicksById);
            Map<SocialSource, Long> social = drainEach(socialViews);
            Map<SocialKey, Long> linkClicks = drainEach(socialClicks);
            Map<SocialKey, Long> articleClicks = drainEach(socialArticleClicks);
            Map<String, Long> botHits = drainEach(crawlerHits);
            Map<String, Long> botErrors = drainEach(crawlerErrors);
            Map<String, Long> botVerified = drainEach(crawlerVerified);
            Map<String, Long> botUnverified = drainEach(crawlerUnverified);

            Document increments = new Document();
            appendIfPositive(increments, SiteMetric.FIELD_VIEWS, viewCount);
            appendIfPositive(increments, SiteMetric.FIELD_BANNER_IMPRESSIONS, impressionCount);
            appendIfPositive(increments, SiteMetric.FIELD_BANNER_CLICKS, clickCount);
            // A dotted path, sent through the raw driver so it reaches MongoDB verbatim rather
            // than through Spring Data's property-path mapping.
            pages.forEach((page, count) ->
                    increments.append(SiteMetric.FIELD_VIEWS_BY_PAGE + "." + page.name(), count));
            // The same for the per-booking maps. The id becomes a string key inside an embedded
            // document -- never an array index, because this $inc is what creates the parent.
            impressions.forEach((id, count) ->
                    increments.append(SiteMetric.FIELD_BANNER_IMPRESSIONS_BY_ID + "." + id, count));
            viewable.forEach((id, count) ->
                    increments.append(SiteMetric.FIELD_BANNER_VIEWABLE_BY_ID + "." + id, count));
            clicks.forEach((id, count) ->
                    increments.append(SiteMetric.FIELD_BANNER_CLICKS_BY_ID + "." + id, count));

            // /social: one level deeper for the click matrices -- socialClicks.<link>.<SOURCE>.
            // Link ids are generated in a shape that is safe as a key (see SocialLink); article
            // ids are numbers, which inside an embedded document are keys like any other.
            social.forEach((source, count) ->
                    increments.append(SiteMetric.FIELD_SOCIAL_VIEWS_BY_SOURCE + "." + source.name(), count));
            linkClicks.forEach((key, count) -> increments.append(SiteMetric.FIELD_SOCIAL_CLICKS
                    + "." + key.target() + "." + key.source().name(), count));
            articleClicks.forEach((key, count) -> increments.append(SiteMetric.FIELD_SOCIAL_ARTICLE_CLICKS
                    + "." + key.target() + "." + key.source().name(), count));

            // Crawlers: crawlerHitsByBot.googlebot. Bot ids are checked against BOT_ID on the way
            // in, so every one is a legal key.
            botHits.forEach((bot, count) ->
                    increments.append(SiteMetric.FIELD_CRAWLER_HITS_BY_BOT + "." + bot, count));
            botErrors.forEach((bot, count) ->
                    increments.append(SiteMetric.FIELD_CRAWLER_ERRORS_BY_BOT + "." + bot, count));
            botVerified.forEach((bot, count) ->
                    increments.append(SiteMetric.FIELD_CRAWLER_VERIFIED_BY_BOT + "." + bot, count));
            botUnverified.forEach((bot, count) ->
                    increments.append(SiteMetric.FIELD_CRAWLER_UNVERIFIED_BY_BOT + "." + bot, count));

            return new Drained(increments, viewCount, impressionCount, clickCount, pages,
                    impressions, viewable, clicks, social, linkClicks, articleClicks,
                    botHits, botErrors, botVerified, botUnverified);
        }

        /** Adds a failed drain back, summing into whatever arrived while the write was in flight. */
        void restore(Drained drained) {
            views.add(drained.views());
            bannerImpressions.add(drained.impressions());
            bannerClicks.add(drained.clicks());
            drained.pages().forEach((page, count) ->
                    viewsByPage.computeIfAbsent(page, p -> new LongAdder()).add(count));
            restoreEach(impressionsById, drained.impressionsById());
            restoreEach(viewableById, drained.viewableById());
            restoreEach(clicksById, drained.clicksById());
            restoreEach(socialViews, drained.socialViews());
            restoreEach(socialClicks, drained.socialClicks());
            restoreEach(socialArticleClicks, drained.socialArticleClicks());
            restoreEach(crawlerHits, drained.crawlerHits());
            restoreEach(crawlerErrors, drained.crawlerErrors());
            restoreEach(crawlerVerified, drained.crawlerVerified());
            restoreEach(crawlerUnverified, drained.crawlerUnverified());
        }

        /** {@code sumThenReset} over a per-booking map, keeping only the ids that moved. */
        private static <K> Map<K, Long> drainEach(Map<K, LongAdder> counts) {
            Map<K, Long> drained = new HashMap<>();
            counts.forEach((id, adder) -> {
                long count = adder.sumThenReset();
                if (count > 0) {
                    drained.put(id, count);
                }
            });
            return drained;
        }

        private static <K> void restoreEach(Map<K, LongAdder> counts, Map<K, Long> drained) {
            drained.forEach((id, count) -> counts.computeIfAbsent(id, i -> new LongAdder()).add(count));
        }
    }

    /** Reconstructs a bucket's start from its id, falling back to the current hour if it is unreadable. */
    private static LocalDateTime hourStartOf(String hourId, LocalDateTime fallback) {
        try {
            return LocalDateTime.parse(hourId + ":00:00");
        } catch (RuntimeException e) {
            return fallback.truncatedTo(ChronoUnit.HOURS);
        }
    }

    /**
     * The encoding Spring Data uses for a {@code LocalDateTime}, applied by hand.
     *
     * <p>These writes go through the raw driver -- as every counter write in this project does, to
     * keep property-path mapping out of a dotted {@code $inc} like
     * {@code viewsByPage.ARTICLE} -- so nothing converts the value on the way out. The studio
     * reads the same field back <em>through</em> the mapper, and it interprets a BSON date in the
     * system zone. Writing a raw UTC instant here instead would put every bucket's {@code start}
     * an offset away from its own id.
     */
    private static LocalDateTime dayStartOf(String dayId, LocalDateTime fallback) {
        try {
            return java.time.LocalDate.parse(dayId).atStartOfDay();
        } catch (RuntimeException e) {
            return fallback.truncatedTo(ChronoUnit.DAYS);
        }
    }

    private static Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }
}
