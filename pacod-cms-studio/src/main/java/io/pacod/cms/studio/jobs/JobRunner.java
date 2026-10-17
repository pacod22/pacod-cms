package io.pacod.cms.studio.jobs;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the queued jobs, in the studio, on a few virtual threads ({@code pacod.jobs.*}; off in the
 * tests and the {@code --create-site} run).
 *
 * <p>Each worker claims a job ({@link JobQueue#claim}), binds its website, and runs its handler
 * while a heartbeat extends the lease every few seconds and reads back whether to stop. A job of
 * a website that is gone, or being deleted, is cancelled unrun: its first write would recreate the
 * database. Whatever a run ends with is written fenced, so a worker that lost its job writes
 * nothing over the one that took it.
 *
 * <p>A restart abandons the running jobs; their leases run out and the next claim runs them again
 * from the start, at most {@value JobQueue#MAX_ATTEMPTS} times. Handlers are written for that.
 */
@Slf4j
public class JobRunner implements SmartLifecycle {

    private final JobQueue queue;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final TenantDirectory directory;
    private final SiteLiveness liveness;
    private final int workers;
    private final Duration lease;
    private final Duration heartbeat;
    private final Duration poll;
    private final int perOrganisation;
    private final String name;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public JobRunner(JobQueue queue, List<JobHandler> handlers, TenantDirectory directory, SiteLiveness liveness,
                     int workers, Duration lease, Duration heartbeat, Duration poll, int perOrganisation) {
        this.queue = queue;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
        this.directory = directory;
        this.liveness = liveness;
        this.workers = Math.max(1, workers);
        this.lease = lease;
        this.heartbeat = heartbeat;
        this.poll = poll;
        this.perOrganisation = Math.max(1, perOrganisation);
        this.name = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < workers; i++) {
            String worker = name + "#" + i;
            threads.add(Thread.ofVirtual().name("job-" + i).start(() -> loop(worker)));
        }
        log.info("Job runner {} started: {} workers, handlers {}", name, workers, handlers.keySet());
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop(String worker) {
        Duration backoff = poll;
        while (running) {
            try {
                queue.expireAbandoned();
                var job = queue.claim(worker, lease, perOrganisation);
                if (job.isEmpty()) {
                    Thread.sleep(poll);
                    continue;
                }
                execute(job.get());
                backoff = poll;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Job worker {}: {}", worker, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : backoff.multipliedBy(2);
            }
        }
    }

    /** One claimed job, start to finish. */
    void execute(Job job) {
        JobQueue.Fence fence = JobQueue.Fence.of(job);
        JobContext context = new JobContext(job, liveness);
        var tenant = directory.bySite(job.getSiteId());
        if (tenant.isEmpty() || liveness.isGone(job.getSiteId())) {
            context.log("The website is gone; nothing was done.");
            queue.finish(fence, JobStatus.CANCELLED, "The website no longer exists", context.progress());
            return;
        }
        JobHandler handler = handlers.get(job.getType());
        if (handler == null) {
            queue.finish(fence, JobStatus.FAILED, "Nothing here runs " + job.getType() + " jobs", context.progress());
            return;
        }
        Thread beats = Thread.ofVirtual().name("job-beat-" + job.getId()).start(() -> beat(fence, context));
        JobStatus status;
        String error = null;
        try {
            context.log("Started (attempt " + job.getAttempt() + ").");
            run(tenant.get(), handler, context);
            status = context.failed() > 0 ? JobStatus.FAILED : JobStatus.DONE;
            if (context.failed() > 0) {
                error = context.failed() + " of " + context.progress().total() + " failed; the log says which";
            }
            context.log(status == JobStatus.DONE ? "Done." : "Finished with failures.");
        } catch (JobStopped stopped) {
            if (stopped.reason() == JobStopped.Reason.LOST) {
                log.warn("Job {} was taken over by another worker; this run stops without writing", job.getId());
                beats.interrupt();
                return;
            }
            status = JobStatus.CANCELLED;
            error = stopped.reason() == JobStopped.Reason.GONE ? "The website is being deleted" : null;
            context.log(stopped.reason() == JobStopped.Reason.GONE ? "Stopped: the website is being deleted." : "Cancelled.");
        } catch (Exception e) {
            status = JobStatus.FAILED;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            context.log("Failed: " + error);
            log.error("Job {} ({}) failed", job.getId(), job.getType(), e);
        } finally {
            beats.interrupt();
        }
        if (!queue.finish(fence, status, error, context.progress())) {
            log.warn("Job {} finished as {} but was no longer this worker's; nothing written", job.getId(), status);
        }
    }

    private static void run(Tenant tenant, JobHandler handler, JobContext context) throws Exception {
        TenantContext.callChecked(tenant, () -> {
            handler.run(context);
            return null;
        });
    }

    private void beat(JobQueue.Fence fence, JobContext context) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(heartbeat);
                context.observe(queue.heartbeat(fence, lease, context.progress()));
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.debug("Heartbeat for job {} failed: {}", fence.id(), e.getMessage());
            }
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "studio";
        }
    }
}
