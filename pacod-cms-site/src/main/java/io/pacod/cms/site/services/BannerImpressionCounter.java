package io.pacod.cms.site.services;

import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import lombok.extern.slf4j.Slf4j;
import io.pacod.cms.core.tenant.PerSite;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts banner impressions in memory and flushes them to MongoDB in one bulk write, and is the
 * single choke point for every other banner number too: clicks, and the viewable impressions the
 * reader's browser reports. Each also reaches {@link SiteMetricCollector}'s time buckets from here.
 *
 * <h2>Why this does not look like {@code ArticleViewCounter}</h2>
 * That class does one atomic {@code $inc} per request, which is right when there is exactly one
 * counter per page view. A page here carries up to eight banners, so the same design would mean
 * eight round trips on <em>every</em> render of the busiest routes on the site -- a worse version
 * of the read-modify-write that {@code ArticleViewCounter}'s javadoc warns against.
 *
 * <p>So impressions accumulate in a {@link ConcurrentHashMap} of {@link LongAdder}s -- lock-free
 * under concurrent renders -- and a scheduled flush turns whatever has piled up into a single
 * bulk write, one {@code $inc} per banner that was actually shown. Steady-state cost is one write
 * per flush interval regardless of traffic.
 *
 * <p><b>The tradeoff, stated plainly:</b> impressions recorded since the last flush are lost if
 * the process dies uncleanly. That is the right trade for an advertising metric -- the numbers
 * are for reporting, not billing reconciliation -- and a clean shutdown flushes on
 * {@link ContextClosedEvent}. A view counter that cost the site its latency would be the worse
 * bargain.
 *
 * <p>Note also that a studio save writes the whole document and would restore whatever
 * {@code impressions} was read at the start of that request, discarding anything this class has
 * flushed in between. Keeping the counters off {@code BannerForm} bounds that loss to a single
 * flush interval; the codebase already tolerates the identical race for article view counts.
 */
@Slf4j
@Component
public class BannerImpressionCounter {

    private static final String COLLECTION = "banner";
    private static final String IMPRESSIONS_FIELD = "impressions";
    private static final String CLICKS_FIELD = "clicks";

    /**
     * Pending counts, per website and then by banner id. A {@code LongAdder} per entry rather than
     * an {@code AtomicLong} because this is written far more often than it is read. Banner ids are
     * only unique inside one website, which is one reason the buffer is kept per site; the other
     * is that the flush must know whose database to write -- see {@link PerSite}.
     */
    private final PerSite<Map<Integer, LongAdder>> pending = new PerSite<>(ConcurrentHashMap::new);

    private final MongoTemplate mongoTemplate;
    private final SiteMetricCollector metrics;
    private final boolean enabled;

    /**
     * A deleted website's counts are discarded rather than flushed: the first write would recreate
     * the database the studio just dropped. Optional so a test can build this class by hand.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useLiveness(io.pacod.cms.core.tenant.SiteLiveness liveness) {
        pending.skipWhen(liveness::isGone);
    }

    public BannerImpressionCounter(MongoTemplate mongoTemplate,
                                   SiteMetricCollector metrics,
                                   @Value("${app.banner.impression-counting.enabled:true}") boolean enabled) {
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /**
     * Records one impression. Cheap enough to call inside a render loop; never touches the network.
     *
     * <p>Not for a crawler: a page rendered for one still carries its banners, but nobody saw them.
     * This, {@link #recordViewable} and {@link #recordClick} are the one choke point for every banner
     * number, so the check is made here rather than in each of the controllers that render a page.
     */
    public void recordImpression(Integer bannerId) {
        if (!enabled || bannerId == null || CrawlerTraffic.currentIsUncounted()) {
            return;
        }
        pending.current().ifPresent(counts -> counts.computeIfAbsent(bannerId, id -> new LongAdder()).increment());

        // The site-wide total and the per-booking series the monitoring screen charts. Here rather
        // than in BannerService because this class is the one choke point every banner number
        // passes through, so the aggregate cannot drift from the per-booking counts on /mgmt/banners.
        metrics.recordBannerImpression(bannerId);
    }

    /**
     * Records one viewable impression, reported by the reader's browser through {@code /b/v}.
     *
     * <p>Goes to the time-bucketed series only. There is no all-time scalar on {@code Banner} for
     * it: an all-time figure starting at this deploy would sit beside {@code impressions}, which
     * goes back years, and invite exactly the division that cannot be made.
     */
    public void recordViewable(Integer bannerId) {
        if (!enabled || bannerId == null || CrawlerTraffic.currentIsUncounted()) {
            return;
        }
        metrics.recordBannerViewable(bannerId);
    }

    /**
     * Records one click as an immediate atomic {@code $inc}.
     *
     * <p>Not buffered, unlike impressions: a click is one write per user action rather than eight
     * per page render, so there is nothing to amortise, and the redirect that follows is a good
     * moment to have already persisted it.
     *
     * <p>A crawler following an image banner's link still reaches the advertiser -- the redirect
     * is not ours to withhold -- but is not a click anybody made.
     *
     * @return {@code true} if the increment was applied
     */
    public boolean recordClick(Integer bannerId) {
        if (bannerId == null || CrawlerTraffic.currentIsUncounted()) {
            return false;
        }
        try {
            long modified = mongoTemplate.getCollection(COLLECTION)
                    .updateOne(new Document("_id", bannerId),
                            new Document("$inc", new Document(CLICKS_FIELD, 1L)))
                    .getModifiedCount();

            if (modified > 0) {
                metrics.recordBannerClick(bannerId);
            }
            return modified > 0;
        } catch (RuntimeException e) {
            // A click counter must never be the reason the reader fails to reach the advertiser.
            log.warn("Failed to record click for banner id={}: {}", bannerId, e.getMessage());
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${app.banner.impression-flush.ms:60000}",
               initialDelayString = "${app.banner.impression-flush.ms:60000}")
    public void flush() {
        flushPending();
    }

    /** Best-effort flush so an orderly restart does not drop the last interval's counts. */
    @EventListener(ContextClosedEvent.class)
    public void flushOnShutdown() {
        flushPending();
    }

    /**
     * Drains the buffer and applies it as one bulk write.
     *
     * <p>Entries are removed before the write, and put back by summing into whatever has arrived
     * since if the write fails -- so a failed flush costs a retry, not the counts. Removing after
     * the write instead would discard every impression recorded while it was in flight.
     */
    private void flushPending() {
        pending.forEach(this::flushSite);
    }

    private void flushSite(Map<Integer, LongAdder> pending) {
        if (pending.isEmpty()) {
            return;
        }

        List<WriteModel<Document>> writes = new ArrayList<>();
        Map<Integer, Long> drained = new java.util.HashMap<>();

        for (Iterator<Map.Entry<Integer, LongAdder>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, LongAdder> entry = it.next();
            it.remove();
            long count = entry.getValue().sum();
            if (count <= 0) {
                continue;
            }
            drained.put(entry.getKey(), count);
            writes.add(new UpdateOneModel<>(
                    new Document("_id", entry.getKey()),
                    new Document("$inc", new Document(IMPRESSIONS_FIELD, count))));
        }

        if (writes.isEmpty()) {
            return;
        }

        try {
            mongoTemplate.getCollection(COLLECTION).bulkWrite(writes);
            log.debug("Flushed impressions for {} banners", writes.size());
        } catch (RuntimeException e) {
            log.warn("Failed to flush banner impressions ({} banners); returning them to the buffer: {}",
                    writes.size(), e.getMessage());
            drained.forEach((id, count) -> pending.computeIfAbsent(id, k -> new LongAdder()).add(count));
        }
    }
}
