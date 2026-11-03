package io.pacod.cms.site.services;

import io.pacod.cms.core.data.ReaderVisit;
import io.pacod.cms.core.tenant.PerSite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the reader log: one {@link ReaderVisit} row per page view {@link SiteMetricCollector}
 * counts, which hands each one over from the request thread.
 *
 * <p>{@link CrawlerRecorder}'s buffer, for readers. Rows are kept in memory and written in batches
 * every {@code app.metrics.reader-log.flush.ms} (ten seconds) and on shutdown, per website, bounded
 * at {@link #MAX_BUFFERED} rows per website, dropping the oldest -- a traffic spike during a
 * database outage costs log rows, never the heap. A failed batch is logged and dropped rather than
 * retried: the counts went into the buckets, which <em>are</em> retried, so what is lost is detail,
 * not totals.
 *
 * <p>Off with {@code app.metrics.reader-log.enabled=false} ({@code READER_LOG}): the rows hold the
 * reader's address and User-Agent, which an installation may not want to keep at all. The counts in
 * the buckets do not depend on it.
 */
@Slf4j
@Component
public class ReaderLogRecorder {

    static final int MAX_BUFFERED = 5000;
    private static final int BATCH = 500;

    /** One website's rows waiting for the next flush; the cap is per website. */
    private static final class Buffer {
        private final ConcurrentLinkedQueue<ReaderVisit> rows = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
    }

    private final PerSite<Buffer> buffers = new PerSite<>(Buffer::new);

    private final MongoTemplate mongoTemplate;
    private final boolean enabled;

    /**
     * A deleted website's rows are discarded rather than flushed: the first write would recreate
     * the database the studio just dropped. Optional so a test can build this class by hand.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useLiveness(io.pacod.cms.core.tenant.SiteLiveness liveness) {
        buffers.skipWhen(liveness::isGone);
    }

    public ReaderLogRecorder(MongoTemplate mongoTemplate,
                             @Value("${app.metrics.reader-log.enabled:true}") boolean enabled) {
        this.mongoTemplate = mongoTemplate;
        this.enabled = enabled;
    }

    /** Whether rows are being kept at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** One counted view, for the bound website. Never throws, never touches the network. */
    public void record(ReaderVisit visit) {
        if (!enabled || visit == null) {
            return;
        }
        buffers.current().ifPresent(buffer -> {
            buffer.rows.add(visit);
            if (buffer.size.incrementAndGet() > MAX_BUFFERED && buffer.rows.poll() != null) {
                buffer.size.decrementAndGet();
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.metrics.reader-log.flush.ms:10000}",
               initialDelayString = "${app.metrics.reader-log.flush.ms:10000}")
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
        List<ReaderVisit> batch = new ArrayList<>(BATCH);
        ReaderVisit visit;
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

    private void write(List<ReaderVisit> batch) {
        try {
            mongoTemplate.insert(batch, ReaderVisit.class);
            log.debug("Flushed {} reader visits", batch.size());
        } catch (RuntimeException e) {
            log.warn("Failed to write {} reader visits; dropping them (the counts are kept): {}",
                    batch.size(), e.getMessage());
        }
    }

    /** The bound website's rows waiting for the next flush. For the tests. */
    int pending() {
        return buffers.current().map(buffer -> buffer.size.get()).orElse(0);
    }
}
