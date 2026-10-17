package io.pacod.cms.studio.jobs;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.SiteLiveness;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a {@link JobHandler} sees of its run: the job's parameters, a log, progress, the cost of
 * its AI calls, and {@link #checkpoint()}. The runner's heartbeat writes the progress and reads
 * back whether to stop.
 */
public class JobContext {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Job job;
    private final SiteLiveness liveness;
    private final StringBuilder log = new StringBuilder();
    private long logChars;
    private volatile int done;
    private volatile int total;
    private volatile int failed;
    private final AtomicLong costMicros = new AtomicLong();
    private volatile JobQueue.Beat beat = JobQueue.Beat.OK;

    /** For the runner, and for a handler's test. */
    public JobContext(Job job, SiteLiveness liveness) {
        this.job = job;
        this.liveness = liveness;
    }

    public Job job() {
        return job;
    }

    public Map<String, String> params() {
        return job.getParams() == null ? Map.of() : job.getParams();
    }

    public String param(String name) {
        String value = params().get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** One line of the log, time-stamped (UTC). */
    public synchronized void log(String line) {
        String entry = LocalTime.now(ZoneOffset.UTC).format(TIME) + "  " + line + "\n";
        logChars += entry.length();
        log.append(entry);
        if (log.length() > Job.MAX_LOG) {
            log.delete(0, log.length() - Job.MAX_LOG);
        }
    }

    public void total(int total) {
        this.total = total;
    }

    /** One item finished well. */
    public void advance() {
        done++;
    }

    /** One item failed: said in the log, counted, and the run ends {@code FAILED}. */
    public void fail(String line) {
        failed++;
        done++;
        log("FAILED " + line);
    }

    public int failed() {
        return failed;
    }

    public void addCostMicros(long micros) {
        costMicros.addAndGet(micros);
    }

    /**
     * Between items: throws {@link JobStopped} when the run was cancelled, is no longer this
     * worker's, or its website is being deleted. Nothing may be written into the website after.
     */
    public void checkpoint() {
        JobQueue.Beat current = beat;
        if (current == JobQueue.Beat.LOST) {
            throw new JobStopped(JobStopped.Reason.LOST);
        }
        if (current == JobQueue.Beat.CANCEL) {
            throw new JobStopped(JobStopped.Reason.CANCELLED);
        }
        if (liveness.isGone(job.getSiteId())) {
            throw new JobStopped(JobStopped.Reason.GONE);
        }
    }

    void observe(JobQueue.Beat beat) {
        if (this.beat != JobQueue.Beat.LOST) {
            this.beat = beat;
        }
    }

    public synchronized JobQueue.Progress progress() {
        return new JobQueue.Progress(done, total, log.toString(), logChars, costMicros.get());
    }
}
