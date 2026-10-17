package io.pacod.cms.studio.jobs;

/** Thrown by {@link JobContext#checkpoint()}: the run must end here, and why. */
public class JobStopped extends RuntimeException {

    public enum Reason {
        /** Someone asked. */
        CANCELLED,
        /** The lease ran out and another worker has the job: write nothing more. */
        LOST,
        /** The website is being deleted: its database must not be written again. */
        GONE
    }

    private final Reason reason;

    public JobStopped(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
