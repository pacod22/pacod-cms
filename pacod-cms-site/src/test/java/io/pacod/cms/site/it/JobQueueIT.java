package io.pacod.cms.site.it;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.jobs.JobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jobs queue against a real MongoDB: what one {@code findAndModify} claims, and that a worker
 * whose job was taken over can write nothing -- which a mocked template cannot show.
 */
class JobQueueIT extends TwoWebsitesIT {

    private static final Duration LEASE = Duration.ofMinutes(1);

    private JobQueue queue;

    @BeforeEach
    void emptyQueue() {
        controlPlane.remove(new Query(), Job.class);
        queue = new JobQueue(controlPlane);
    }

    private Job enqueue(String site, String org, boolean next) {
        return queue.enqueue(site, org, JobType.PDF_ARTICLES, Map.of("ids", "1"), "it", next);
    }

    private void expireLease(String id) {
        controlPlane.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("leaseUntil", Instant.now().minusSeconds(5)), Job.class);
    }

    @Test
    @DisplayName("a raised priority is claimed first, then the oldest")
    void claimOrder() throws InterruptedException {
        Job older = enqueue("alpha", "org_a", false);
        Thread.sleep(5);
        Job newer = enqueue("alpha", "org_a", false);
        Job next = enqueue("alpha", "org_a", true);

        assertThat(queue.claim("w1", LEASE, 9).map(Job::getId)).contains(next.getId());
        assertThat(queue.claim("w1", LEASE, 9).map(Job::getId)).contains(older.getId());
        assertThat(queue.claim("w1", LEASE, 9).map(Job::getId)).contains(newer.getId());
        assertThat(queue.claim("w1", LEASE, 9)).isEmpty();
    }

    @Test
    @DisplayName("an organisation running its share waits; another organisation's job goes first")
    void perOrganisation() {
        Job x1 = enqueue("alpha", "org_x", false);
        enqueue("alpha", "org_x", false);
        Job y1 = enqueue("beta", "org_y", false);

        assertThat(queue.claim("w1", LEASE, 1).map(Job::getId)).contains(x1.getId());
        assertThat(queue.claim("w2", LEASE, 1).map(Job::getId)).contains(y1.getId());
        assertThat(queue.claim("w3", LEASE, 1)).isEmpty();
    }

    @Test
    @DisplayName("a worker whose lease ran out and was taken over writes nothing; the new one finishes")
    void fencing() {
        Job job = enqueue("alpha", "org_a", false);
        Job first = queue.claim("w1", LEASE, 9).orElseThrow();
        assertThat(first.getAttempt()).isEqualTo(1);
        expireLease(job.getId());

        Job second = queue.claim("w2", LEASE, 9).orElseThrow();
        assertThat(second.getId()).isEqualTo(job.getId());
        assertThat(second.getAttempt()).isEqualTo(2);

        JobQueue.Fence stale = JobQueue.Fence.of(first);
        JobQueue.Progress nothing = new JobQueue.Progress(1, 1, "stale", 5, 0);
        assertThat(queue.heartbeat(stale, LEASE, nothing)).isEqualTo(JobQueue.Beat.LOST);
        assertThat(queue.finish(stale, JobStatus.DONE, null, nothing)).isFalse();
        assertThat(queue.find("alpha", job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);

        assertThat(queue.finish(JobQueue.Fence.of(second), JobStatus.DONE, null,
                new JobQueue.Progress(1, 1, "fine", 4, 0))).isTrue();
        Job done = queue.find("alpha", job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getLog()).isEqualTo("fine");
        assertThat(done.getFinishedOn()).isNotNull();
    }

    @Test
    @DisplayName("cancelling: a queued job at once, a running one at its next heartbeat")
    void cancel() {
        Job queued = enqueue("alpha", "org_a", false);
        Job running = enqueue("alpha", "org_b", false);
        Job claimed = queue.claim("w1", LEASE, 9).orElseThrow();
        assertThat(claimed.getId()).isEqualTo(queued.getId());
        // The other is still queued: cancelled at once.
        assertThat(queue.cancel("alpha", running.getId())).isTrue();
        assertThat(queue.find("alpha", running.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);

        assertThat(queue.cancel("alpha", claimed.getId())).isTrue();
        assertThat(queue.heartbeat(JobQueue.Fence.of(claimed), LEASE, null)).isEqualTo(JobQueue.Beat.CANCEL);
        // Another website cannot reach it.
        assertThat(queue.cancel("beta", claimed.getId())).isFalse();
        assertThat(queue.find("beta", claimed.getId())).isEmpty();
    }

    @Test
    @DisplayName("a job that stalled too often, or was asked to stop, is finished rather than run again")
    void abandoned() {
        Job tired = enqueue("alpha", "org_a", false);
        controlPlane.updateFirst(Query.query(Criteria.where("_id").is(tired.getId())),
                new Update().set("status", JobStatus.RUNNING).set("attempt", JobQueue.MAX_ATTEMPTS)
                        .set("leaseUntil", Instant.now().minusSeconds(5)), Job.class);
        Job stopping = enqueue("alpha", "org_a", false);
        controlPlane.updateFirst(Query.query(Criteria.where("_id").is(stopping.getId())),
                new Update().set("status", JobStatus.RUNNING).set("attempt", 1).set("cancelRequested", true)
                        .set("leaseUntil", Instant.now().minusSeconds(5)), Job.class);

        assertThat(queue.claim("w1", LEASE, 9)).isEmpty();
        assertThat(queue.expireAbandoned()).isEqualTo(2);
        assertThat(queue.find("alpha", tired.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(queue.find("alpha", stopping.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    @DisplayName("a website being deleted has every unfinished job stopped; the list and counts are its own")
    void perWebsite() {
        enqueue("alpha", "org_a", false);
        Job running = enqueue("alpha", "org_a", false);
        enqueue("beta", "org_b", false);
        controlPlane.updateFirst(Query.query(Criteria.where("_id").is(running.getId())),
                new Update().set("status", JobStatus.RUNNING), Job.class);

        assertThat(queue.forSite("alpha", null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(queue.counts("alpha", Instant.now().minusSeconds(60)))
                .containsEntry(JobStatus.QUEUED, 1L).containsEntry(JobStatus.RUNNING, 1L);

        assertThat(queue.cancelAll("alpha")).isEqualTo(2);
        assertThat(queue.forSite("alpha", JobStatus.CANCELLED, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(queue.find("alpha", running.getId()).orElseThrow().isCancelRequested()).isTrue();
        assertThat(queue.forSite("beta", JobStatus.QUEUED, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }
}
