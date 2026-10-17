package io.pacod.cms.core.control.data;

/** Where a {@link Job} is. {@code DONE}, {@code FAILED} and {@code CANCELLED} never change again. */
public enum JobStatus {

    QUEUED("draft"),
    RUNNING("published"),
    DONE("published"),
    FAILED("off"),
    CANCELLED("plain");

    private final String badgeClass;

    JobStatus(String badgeClass) {
        this.badgeClass = badgeClass;
    }

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    public boolean isCancellable() {
        return this == QUEUED || this == RUNNING;
    }

    /** The studio's badge for it. */
    public String getBadgeClass() {
        return badgeClass;
    }
}
