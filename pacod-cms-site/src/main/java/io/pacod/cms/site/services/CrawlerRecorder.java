package io.pacod.cms.site.services;

import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.CrawlerVisit;
import io.pacod.cms.core.tenant.PerSite;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.util.CrawlerClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns one automated request into the two records the crawlers screen reads: a count in the
 * traffic buckets and a row in {@code crawlerVisit}.
 *
 * <p>Verify, then count, then buffer the row. The verification is asynchronous ({@link CrawlerVerifier}), so both
 * records are made from whichever thread it completes on -- the request thread when nothing needed
 * looking up, a DNS thread when something did. {@link CrawlerVisit#getOccurredAt()} is taken by the
 * filter before the request ran, so a slow lookup never moves a row in time.
 *
 * <h2>The buffer</h2>
 * Rows are kept in memory and written in batches every {@code app.crawlers.flush.ms} (ten seconds)
 * and on shutdown, per website. Bounded at {@link #MAX_BUFFERED} rows per website, dropping the oldest -- a crawler flood
 * during a database outage costs log rows, never the heap. A failed batch is logged and dropped
 * rather than retried: the counts went into the buckets, which <em>are</em>
 * retried, so what is lost is detail, not totals.
 *
 * <p>Writes go through {@code MongoTemplate.insert}, through the mapper, unlike the counters'
 * dotted {@code $inc}s: an insert has no property path to keep verbatim, and the mapper writes the
 * {@link Instant} as a plain UTC date.
 */
@Slf4j
@Component
public class CrawlerRecorder {

    static final int MAX_BUFFERED = 5000;
    private static final int BATCH = 500;

    /** One website's rows waiting for the next flush; the cap is per website. */
    private static final class Buffer {
        private final ConcurrentLinkedQueue<CrawlerVisit> rows = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
    }

    private final PerSite<Buffer> buffers = new PerSite<>(Buffer::new);

    private final MongoTemplate mongoTemplate;
    private final CrawlerVerifier verifier;
    private final SiteMetricCollector metrics;
    private final boolean enabled;

    /**
     * A deleted website's counts are discarded rather than flushed: the first write would recreate
     * the database the studio just dropped. Optional so a test can build this class by hand.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useLiveness(io.pacod.cms.core.tenant.SiteLiveness liveness) {
        buffers.skipWhen(liveness::isGone);
    }

    public CrawlerRecorder(MongoTemplate mongoTemplate,
                           CrawlerVerifier verifier,
                           SiteMetricCollector metrics,
                           @Value("${app.crawlers.tracking.enabled:true}") boolean enabled) {
        this.mongoTemplate = mongoTemplate;
        this.verifier = verifier;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /** Whether hits are being recorded at all. Classification and exclusion run either way. */
    public boolean isEnabled() {
        return enabled;
    }

    /** One automated request, already answered. Never throws, never blocks on the network. */
    public void record(CrawlerClassifier.Identity identity, String ip, String userAgent, String method,
                       String path, int status, Instant occurredAt) {
        if (!enabled || identity == null) {
            return;
        }
        // The verdict may arrive on a DNS thread, where nothing is bound: the website the request
        // was resolved to is captured here and bound again around the store.
        Tenant tenant = TenantContext.find().orElse(null);
        if (tenant == null) {
            return;
        }
        try {
            verifier.verify(ip, identity, verification -> TenantContext.runAs(tenant, () -> store(identity, ip,
                    userAgent, method, path, status, occurredAt, verification)));
        } catch (RuntimeException e) {
            log.warn("Failed to record a crawler hit from {}: {}", identity.bot(), e.toString());
        }
    }

    private void store(CrawlerClassifier.Identity identity, String ip, String userAgent, String method,
                       String path, int status, Instant occurredAt, CrawlerVerification verification) {
        metrics.recordCrawlerHit(identity.bot(), status, verification);

        CrawlerVisit visit = new CrawlerVisit();
        visit.setOccurredAt(occurredAt);
        visit.setBot(identity.bot());
        visit.setCategory(identity.category());
        visit.setVerification(verification);
        visit.setIp(ip);
        visit.setUserAgent(truncate(userAgent, CrawlerVisit.MAX_USER_AGENT));
        visit.setMethod(method);
        visit.setPath(truncate(path, CrawlerVisit.MAX_PATH));
        visit.setStatus(status);

        Buffer buffer = buffers.current().orElseThrow();
        buffer.rows.add(visit);
        if (buffer.size.incrementAndGet() > MAX_BUFFERED && buffer.rows.poll() != null) {
            buffer.size.decrementAndGet();
        }
    }

    @Scheduled(fixedDelayString = "${app.crawlers.flush.ms:10000}",
               initialDelayString = "${app.crawlers.flush.ms:10000}")
    public void flush() {
        flushPending();
    }

    /** So an orderly restart does not drop the last interval's rows. */
    @EventListener(ContextClosedEvent.class)
    public void flushOnShutdown() {
        flushPending();
    }

    private void flushPending() {
        buffers.forEach(this::flushSite);
    }

    private void flushSite(Buffer buffer) {
        List<CrawlerVisit> batch = new ArrayList<>(BATCH);
        CrawlerVisit visit;
        while ((visit = buffer.rows.poll()) != null) {
            buffer.size.decrementAndGet();
            batch.add(visit);
            if (batch.size() == BATCH) {
                write(batch);
                batch = new ArrayList<>(BATCH);
            }
        }
        if (!batch.isEmpty()) {
            write(batch);
        }
    }

    private void write(List<CrawlerVisit> batch) {
        try {
            mongoTemplate.insert(batch, CrawlerVisit.class);
            log.debug("Flushed {} crawler visits", batch.size());
        } catch (RuntimeException e) {
            log.warn("Failed to write {} crawler visits; dropping them (the counts are kept): {}",
                    batch.size(), e.getMessage());
        }
    }

    /** The bound website's rows waiting for the next flush. For the tests. */
    int pending() {
        return buffers.current().map(buffer -> buffer.size.get()).orElse(0);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
