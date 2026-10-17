package io.pacod.cms.site.services;

import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.CrawlerVisit;
import io.pacod.cms.core.util.CrawlerClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verify, count, buffer -- and the buffer's bounds. The verifier is a mock that answers at once,
 * which is the path a crawler with no DNS scheme takes for real.
 */
class CrawlerRecorderTest {

    private static final CrawlerClassifier.Identity GOOGLEBOT =
            CrawlerClassifier.classify("Mozilla/5.0 (compatible; Googlebot/2.1)");
    private static final Instant ARRIVED = Instant.parse("2026-10-11T08:15:30Z");

    private MongoTemplate mongoTemplate;
    private CrawlerVerifier verifier;
    private SiteMetricCollector metrics;
    private CrawlerRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        verifier = mock(CrawlerVerifier.class);
        metrics = mock(SiteMetricCollector.class);
        doAnswer(invocation -> {
            ((Consumer<CrawlerVerification>) invocation.getArgument(2)).accept(CrawlerVerification.VERIFIED);
            return null;
        }).when(verifier).verify(any(), any(), any());

        recorder = new CrawlerRecorder(mongoTemplate, verifier, metrics, true);
    }

    @Test
    @DisplayName("a hit is counted at once and its row waits for the flush")
    void hitIsCountedAndBuffered() {
        recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot/2.1", "GET", "/article/5", 404, ARRIVED);

        verify(metrics).recordCrawlerHit("googlebot", 404, CrawlerVerification.VERIFIED);
        verifyNoInteractions(mongoTemplate);
        assertThat(recorder.pending()).isEqualTo(1);

        recorder.flush();

        CrawlerVisit row = flushedRows().getFirst();
        assertThat(row.getBot()).isEqualTo("googlebot");
        assertThat(row.getCategory()).isEqualTo(CrawlerCategory.SEARCH);
        assertThat(row.getVerification()).isEqualTo(CrawlerVerification.VERIFIED);
        assertThat(row.getIp()).isEqualTo("66.249.66.1");
        assertThat(row.getPath()).isEqualTo("/article/5");
        assertThat(row.getStatus()).isEqualTo(404);
        assertThat(row.getMethod()).isEqualTo("GET");
        // Taken before the request ran, not after the lookup, so a row never lags by the DNS time.
        assertThat(row.getOccurredAt()).isEqualTo(ARRIVED);
        assertThat(recorder.pending()).isZero();
    }

    @Test
    @DisplayName("long User-Agents and paths are truncated, not refused")
    void longFieldsAreTruncated() {
        recorder.record(GOOGLEBOT, "66.249.66.1", "x".repeat(2000), "GET", "/" + "p".repeat(5000), 200, ARRIVED);
        recorder.flush();

        CrawlerVisit row = flushedRows().getFirst();
        assertThat(row.getUserAgent()).hasSize(CrawlerVisit.MAX_USER_AGENT);
        assertThat(row.getPath()).hasSize(CrawlerVisit.MAX_PATH);
    }

    @Test
    @DisplayName("the buffer is bounded and drops the oldest rows, never the heap")
    void bufferIsBounded() {
        for (int i = 0; i < CrawlerRecorder.MAX_BUFFERED + 50; i++) {
            recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot", "GET", "/p/" + i, 200, ARRIVED);
        }

        assertThat(recorder.pending()).isEqualTo(CrawlerRecorder.MAX_BUFFERED);
        // The counts are not bounded with it: the buckets saw every hit.
        verify(metrics, times(CrawlerRecorder.MAX_BUFFERED + 50))
                .recordCrawlerHit(eq("googlebot"), eq(200), eq(CrawlerVerification.VERIFIED));
    }

    @Test
    @DisplayName("a failed write is dropped rather than retried -- the counts already went to the buckets")
    void failedWriteIsDropped() {
        doThrow(new IllegalStateException("no server")).when(mongoTemplate).insert(anyList(), eq(CrawlerVisit.class));

        recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot", "GET", "/", 200, ARRIVED);
        recorder.flush();
        recorder.flush();

        verify(mongoTemplate, times(1)).insert(anyList(), eq(CrawlerVisit.class));
        assertThat(recorder.pending()).isZero();
    }

    @Test
    @DisplayName("shutdown flushes the last interval's rows")
    void shutdownFlushes() {
        recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot", "GET", "/", 200, ARRIVED);
        recorder.flushOnShutdown();

        assertThat(flushedRows()).hasSize(1);
    }

    @Test
    @DisplayName("switched off, nothing is verified, counted or buffered")
    void disabledRecordsNothing() {
        CrawlerRecorder off = new CrawlerRecorder(mongoTemplate, verifier, metrics, false);

        off.record(GOOGLEBOT, "66.249.66.1", "Googlebot", "GET", "/", 200, ARRIVED);
        off.flush();

        verifyNoInteractions(verifier, metrics, mongoTemplate);
    }

    @Test
    @DisplayName("an idle flush writes nothing")
    void idleFlushIsSilent() {
        recorder.flush();

        verify(mongoTemplate, never()).insert(anyList(), eq(CrawlerVisit.class));
    }

    @SuppressWarnings("unchecked")
    private List<CrawlerVisit> flushedRows() {
        ArgumentCaptor<List<CrawlerVisit>> rows = ArgumentCaptor.forClass(List.class);
        verify(mongoTemplate).insert(rows.capture(), eq(CrawlerVisit.class));
        return rows.getValue();
    }

    private static final io.pacod.cms.core.tenant.Tenant SITE_A =
            new io.pacod.cms.core.tenant.Tenant("site-a", "org", "site_site-a", null, java.time.ZoneId.of("UTC"));
    private static final io.pacod.cms.core.tenant.Tenant SITE_B =
            new io.pacod.cms.core.tenant.Tenant("site-b", "org", "site_site-b", null, java.time.ZoneId.of("UTC"));

    /** Runs {@code work} on a thread of its own, where no website is bound -- as a scheduled flush is. */
    private static void offRequestThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }

    @Test
    @DisplayName("a hit verified on another thread is stored, and flushed, under the website it came to")
    void verdictOnAnotherThreadKeepsTheWebsite() throws Exception {
        // The verifier answers on a thread of its own, as a DNS lookup does.
        doAnswer(invocation -> {
            Thread dns = new Thread(() -> ((Consumer<CrawlerVerification>) invocation.getArgument(2))
                    .accept(CrawlerVerification.VERIFIED));
            dns.start();
            dns.join();
            return null;
        }).when(verifier).verify(any(), any(), any());

        List<String> inserts = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            inserts.add(io.pacod.cms.core.tenant.TenantContext.current().siteId() + "="
                    + ((java.util.Collection<?>) invocation.getArgument(0)).size());
            return null;
        }).when(mongoTemplate).insert(any(java.util.Collection.class), eq(CrawlerVisit.class));

        io.pacod.cms.core.tenant.TenantContext.runAs(SITE_A, () -> {
            recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot/2.1", "GET", "/a", 200, ARRIVED);
            recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot/2.1", "GET", "/b", 200, ARRIVED);
        });
        io.pacod.cms.core.tenant.TenantContext.runAs(SITE_B,
                () -> recorder.record(GOOGLEBOT, "66.249.66.1", "Googlebot/2.1", "GET", "/c", 200, ARRIVED));

        offRequestThread(recorder::flush);

        assertThat(inserts).containsExactlyInAnyOrder("site-a=2", "site-b=1");
    }
}
