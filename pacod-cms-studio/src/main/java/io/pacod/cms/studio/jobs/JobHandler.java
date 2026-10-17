package io.pacod.cms.studio.jobs;

import io.pacod.cms.core.control.data.JobType;

/**
 * Runs one {@link JobType}. Called by {@link JobRunner} with the job's website bound.
 *
 * <p><b>A handler must be safe to run again from the start</b>: a restart or a stall past the
 * lease hands the job to the next claim, which begins at the beginning. It calls
 * {@link JobContext#checkpoint()} between items -- where a cancel, a lost lease or a deleted
 * website stops it -- and writes nothing into the website after a failed checkpoint.
 */
public interface JobHandler {

    JobType type();

    void run(JobContext context) throws Exception;
}
