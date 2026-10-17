package io.pacod.cms.studio.jobs;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobRunnerTest {

    private final JobQueue queue = mock(JobQueue.class);
    private final TenantDirectory directory = mock(TenantDirectory.class);
    private final SiteLiveness liveness = mock(SiteLiveness.class);
    private final Tenant alpha = new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"));

    private JobRunner runner(JobHandler handler) {
        return new JobRunner(queue, List.of(handler), directory, liveness, 1, Duration.ofSeconds(60),
                Duration.ofSeconds(30), Duration.ofSeconds(1), 1);
    }

    private static Job job() {
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("alpha");
        job.setType(JobType.PDF_ARTICLES);
        job.setStatus(JobStatus.RUNNING);
        job.setWorker("w");
        job.setAttempt(1);
        return job;
    }

    private static JobHandler handler(ThrowingRun run) {
        return new JobHandler() {
            @Override
            public JobType type() {
                return JobType.PDF_ARTICLES;
            }

            @Override
            public void run(JobContext context) throws Exception {
                run.run(context);
            }
        };
    }

    interface ThrowingRun {
        void run(JobContext context) throws Exception;
    }

    @BeforeEach
    void site() {
        when(directory.bySite("alpha")).thenReturn(Optional.of(alpha));
        when(queue.finish(any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("a job runs with its own website bound, and finishes DONE")
    void runsBound() {
        AtomicReference<String> bound = new AtomicReference<>();
        runner(handler(context -> {
            bound.set(TenantContext.current().siteId());
            context.total(1);
            context.advance();
        })).execute(job());
        assertThat(bound.get()).isEqualTo("alpha");
        verify(queue).finish(eq(JobQueue.Fence.of(job())), eq(JobStatus.DONE), isNull(), any());
    }

    @Test
    @DisplayName("a job of a website that is gone is cancelled unrun: its first write would recreate the database")
    void goneWebsite() {
        when(liveness.isGone("alpha")).thenReturn(true);
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        runner(handler(context -> ran.set(true))).execute(job());
        assertThat(ran.get()).isFalse();
        verify(queue).finish(any(), eq(JobStatus.CANCELLED), anyString(), any());
    }

    @Test
    @DisplayName("an item that failed fails the job, naming how many")
    void itemFailure() {
        runner(handler(context -> {
            context.total(2);
            context.advance();
            context.fail("article 3 (de): boom");
        })).execute(job());
        verify(queue).finish(any(), eq(JobStatus.FAILED), eq("1 of 2 failed; the log says which"), any());
    }

    @Test
    @DisplayName("a handler that throws fails the job with its message")
    void handlerThrows() {
        runner(handler(context -> {
            throw new IllegalStateException("The website has no address");
        })).execute(job());
        verify(queue).finish(any(), eq(JobStatus.FAILED), eq("The website has no address"), any());
    }

    @Test
    @DisplayName("a website deleted mid-run stops the run at its checkpoint, cancelled")
    void deletedMidRun() {
        when(liveness.isGone("alpha")).thenReturn(false, true);
        runner(handler(JobContext::checkpoint)).execute(job());
        verify(queue).finish(any(), eq(JobStatus.CANCELLED), eq("The website is being deleted"), any());
    }

    @Test
    @DisplayName("a run whose job was taken over writes nothing at all")
    void lostLease() {
        runner(handler(context -> {
            context.observe(JobQueue.Beat.LOST);
            context.checkpoint();
        })).execute(job());
        verify(queue, never()).finish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a cancel seen by the heartbeat stops the run at its next checkpoint")
    void cancelled() {
        runner(handler(context -> {
            context.observe(JobQueue.Beat.CANCEL);
            context.checkpoint();
        })).execute(job());
        verify(queue).finish(any(), eq(JobStatus.CANCELLED), isNull(), any());
    }

    @Test
    @DisplayName("the log keeps its end once long, and counts all of it")
    void logWindow() {
        JobContext context = new JobContext(job(), liveness);
        String line = "x".repeat(1000);
        for (int i = 0; i < 100; i++) {
            context.log(line);
        }
        JobQueue.Progress progress = context.progress();
        assertThat(progress.log()).hasSize(Job.MAX_LOG);
        assertThat(progress.logChars()).isGreaterThan(Job.MAX_LOG);
    }
}
