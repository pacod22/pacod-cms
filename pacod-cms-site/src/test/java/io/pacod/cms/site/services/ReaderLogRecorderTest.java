package io.pacod.cms.site.services;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.ReaderVisit;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
import static org.mockito.Mockito.when;

/** The reader log's buffer: per website, bounded, written in batches, dropped on failure. */
class ReaderLogRecorderTest {

    private static final Tenant SITE_A = new Tenant("site-a", "org", "site_site-a", null, ZoneId.of("UTC"));
    private static final Tenant SITE_B = new Tenant("site-b", "org", "site_site-b", null, ZoneId.of("UTC"));

    private MongoTemplate mongoTemplate;
    private ReaderLogRecorder recorder;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        recorder = new ReaderLogRecorder(mongoTemplate, true);
    }

    @Test
    @DisplayName("a row waits for the flush; nothing reaches MongoDB from the request thread")
    void rowsAreBuffered() {
        recorder.record(row("/article/5"));

        verifyNoInteractions(mongoTemplate);
        assertThat(recorder.pending()).isEqualTo(1);

        recorder.flush();

        verify(mongoTemplate).insert(anyList(), eq(ReaderVisit.class));
        assertThat(recorder.pending()).isZero();
    }

    @Test
    @DisplayName("the buffer is bounded per website and drops the oldest rows, never the heap")
    void bufferIsBounded() {
        for (int i = 0; i < ReaderLogRecorder.MAX_BUFFERED + 50; i++) {
            recorder.record(row("/p/" + i));
        }

        assertThat(recorder.pending()).isEqualTo(ReaderLogRecorder.MAX_BUFFERED);
    }

    @Test
    @DisplayName("a failed write is dropped rather than retried -- the counts went to the buckets")
    void failedWriteIsDropped() {
        doThrow(new IllegalStateException("no server")).when(mongoTemplate).insert(anyList(), eq(ReaderVisit.class));

        recorder.record(row("/"));
        recorder.flush();
        recorder.flush();

        verify(mongoTemplate, times(1)).insert(anyList(), eq(ReaderVisit.class));
        assertThat(recorder.pending()).isZero();
    }

    @Test
    @DisplayName("shutdown flushes the last interval's rows")
    void shutdownFlushes() {
        recorder.record(row("/"));
        recorder.flushOnShutdown();

        verify(mongoTemplate).insert(anyList(), eq(ReaderVisit.class));
    }

    @Test
    @DisplayName("switched off (READER_LOG=false), nothing is kept or written")
    void disabledRecordsNothing() {
        ReaderLogRecorder off = new ReaderLogRecorder(mongoTemplate, false);

        off.record(row("/"));
        off.flush();

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.pending()).isZero();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("an idle flush writes nothing")
    void idleFlushIsSilent() {
        recorder.flush();

        verify(mongoTemplate, never()).insert(anyList(), eq(ReaderVisit.class));
    }

    @Test
    @DisplayName("each website's rows are written with that website bound")
    void websitesAreFlushedSeparately() throws Exception {
        List<String> inserts = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            inserts.add(TenantContext.current().siteId() + "=" + ((Collection<?>) invocation.getArgument(0)).size());
            return null;
        }).when(mongoTemplate).insert(any(Collection.class), eq(ReaderVisit.class));

        TenantContext.runAs(SITE_A, () -> {
            recorder.record(row("/a"));
            recorder.record(row("/b"));
        });
        TenantContext.runAs(SITE_B, () -> recorder.record(row("/c")));

        offRequestThread(recorder::flush);

        assertThat(inserts).containsExactlyInAnyOrder("site-a=2", "site-b=1");
    }

    @Test
    @DisplayName("a deleted website's rows are dropped, not flushed: the write would recreate its database")
    void deletedWebsitesAreSkipped() throws Exception {
        SiteLiveness liveness = mock(SiteLiveness.class);
        when(liveness.isGone("site-a")).thenReturn(true);
        recorder.useLiveness(liveness);

        TenantContext.runAs(SITE_A, () -> recorder.record(row("/gone")));
        offRequestThread(recorder::flush);

        verify(mongoTemplate, never()).insert(anyList(), eq(ReaderVisit.class));
    }

    private static ReaderVisit row(String path) {
        ReaderVisit visit = new ReaderVisit();
        visit.setOccurredAt(Instant.parse("2026-11-03T08:15:30Z"));
        visit.setPage(BannerPage.ARTICLE);
        visit.setPath(path);
        visit.setIp("198.51.100.7");
        return visit;
    }

    /** Runs {@code work} on a thread of its own, where no website is bound -- as a scheduled flush is. */
    private static void offRequestThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }
}
