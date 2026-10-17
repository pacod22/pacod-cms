package io.pacod.cms.core.jobs;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.control.data.JobType;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The jobs queue: what the studio's screens enqueue and the runner claims, in the control plane's
 * {@code job} collection through {@code controlPlaneMongoTemplate}. Not a bean of its own: core's
 * packages are scanned by name and the public site must never run jobs; the studio declares it.
 *
 * <ul>
 *   <li><b>A claim is one {@code findAndModify}</b>: the highest priority, then the oldest, of the
 *       queued jobs and of the running ones whose lease ran out -- a worker that died -- skipping
 *       organisations already running their share. It raises {@code attempt} and names the
 *       worker.</li>
 *   <li><b>Every later write is fenced</b> on {@code (id, worker, attempt)} and on the job still
 *       running ({@link Fence}): a worker whose job was taken over writes nothing.</li>
 *   <li>A job whose lease ran out {@value #MAX_ATTEMPTS} times, or that was asked to stop, is
 *       finished by {@link #expireAbandoned} rather than run again.</li>
 * </ul>
 */
public class JobQueue {

    public static final int MAX_ATTEMPTS = 3;

    private final MongoTemplate plane;
    private final Clock clock;

    public JobQueue(MongoTemplate controlPlane) {
        this(controlPlane, Clock.systemUTC());
    }

    public JobQueue(MongoTemplate controlPlane, Clock clock) {
        this.plane = controlPlane;
        this.clock = clock;
    }

    /** Who holds a claimed job: every write after the claim is conditional on all three. */
    public record Fence(String id, String worker, int attempt) {

        public static Fence of(Job job) {
            return new Fence(job.getId(), job.getWorker(), job.getAttempt());
        }
    }

    /** Where a run stands, written at each heartbeat and at the end. */
    public record Progress(int done, int total, String log, long logChars, long costMicros) {
    }

    /** What a heartbeat found: carry on, stop because someone asked, or stop because the job is no longer ours. */
    public enum Beat { OK, CANCEL, LOST }

    /* ------------------------------------------------------------------ the screens */

    public Job enqueue(String siteId, String organisationId, JobType type, Map<String, String> params,
                       String queuedBy, boolean next) {
        Job job = new Job();
        job.setId(new ObjectId().toHexString());
        job.setSiteId(siteId);
        job.setOrganisationId(organisationId);
        job.setType(type);
        job.setParams(new LinkedHashMap<>(params));
        job.setStatus(JobStatus.QUEUED);
        job.setPriority(next ? Job.PRIORITY_NEXT : 0);
        job.setQueuedOn(clock.instant());
        job.setQueuedBy(queuedBy);
        return plane.insert(job);
    }

    /** A job of this website; another website's id is absent. */
    public Optional<Job> find(String siteId, String id) {
        return Optional.ofNullable(plane.findOne(Query.query(Criteria.where("_id").is(id).and("siteId").is(siteId)), Job.class));
    }

    /** The website's jobs, newest first; {@code status} null for all. */
    public Page<Job> forSite(String siteId, JobStatus status, Pageable page) {
        Criteria criteria = Criteria.where("siteId").is(siteId);
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        Query query = Query.query(criteria);
        long total = plane.count(query, Job.class);
        List<Job> rows = plane.find(Query.query(criteria).with(page).with(Sort.by(Sort.Direction.DESC, "queuedOn")), Job.class);
        return new PageImpl<>(rows, page, total);
    }

    /** A queued job is cancelled at once; a running one is asked to stop at its next checkpoint. */
    public boolean cancel(String siteId, String id) {
        Query queued = Query.query(Criteria.where("_id").is(id).and("siteId").is(siteId).and("status").is(JobStatus.QUEUED));
        if (plane.updateFirst(queued, new Update().set("status", JobStatus.CANCELLED).set("finishedOn", clock.instant()), Job.class)
                .getModifiedCount() > 0) {
            return true;
        }
        Query running = Query.query(Criteria.where("_id").is(id).and("siteId").is(siteId).and("status").is(JobStatus.RUNNING));
        return plane.updateFirst(running, new Update().set("cancelRequested", true), Job.class).getModifiedCount() > 0;
    }

    /** Every unfinished job of a website being deleted: none may write into it again. */
    public long cancelAll(String siteId) {
        long queued = plane.updateMulti(Query.query(Criteria.where("siteId").is(siteId).and("status").is(JobStatus.QUEUED)),
                new Update().set("status", JobStatus.CANCELLED).set("finishedOn", clock.instant()), Job.class).getModifiedCount();
        long running = plane.updateMulti(Query.query(Criteria.where("siteId").is(siteId).and("status").is(JobStatus.RUNNING)),
                new Update().set("cancelRequested", true), Job.class).getModifiedCount();
        return queued + running;
    }

    /** A queued job goes next. */
    public boolean promote(String siteId, String id) {
        Query query = Query.query(Criteria.where("_id").is(id).and("siteId").is(siteId).and("status").is(JobStatus.QUEUED)
                .and("priority").lt(Job.PRIORITY_NEXT));
        return plane.updateFirst(query, new Update().set("priority", Job.PRIORITY_NEXT), Job.class).getModifiedCount() > 0;
    }

    /** A finished job's record goes; an unfinished one stays. */
    public boolean delete(String siteId, String id) {
        Query query = Query.query(Criteria.where("_id").is(id).and("siteId").is(siteId)
                .and("status").in(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED));
        return plane.remove(query, Job.class).getDeletedCount() > 0;
    }

    /** How many of the website's jobs are in each state, of those queued since {@code since}. */
    public Map<JobStatus, Long> counts(String siteId, Instant since) {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        // Typed, so the enum and the Instant are converted as the document maps them.
        var aggregation = Aggregation.newAggregation(Job.class,
                Aggregation.match(Criteria.where("siteId").is(siteId).and("queuedOn").gte(since)),
                Aggregation.group("status").count().as("n"));
        for (Document row : plane.aggregate(aggregation, Document.class).getMappedResults()) {
            Object status = row.get("_id");
            if (status != null) {
                counts.put(JobStatus.valueOf(status.toString()), ((Number) row.get("n")).longValue());
            }
        }
        return counts;
    }

    /* ------------------------------------------------------------------ the runner */

    /** Finishes the running jobs whose lease ran out and that must not run again. */
    public long expireAbandoned() {
        Instant now = clock.instant();
        long cancelled = plane.updateMulti(Query.query(Criteria.where("status").is(JobStatus.RUNNING)
                        .and("leaseUntil").lt(now).and("cancelRequested").is(true)),
                new Update().set("status", JobStatus.CANCELLED).set("finishedOn", now), Job.class).getModifiedCount();
        long failed = plane.updateMulti(Query.query(Criteria.where("status").is(JobStatus.RUNNING)
                        .and("leaseUntil").lt(now).and("attempt").gte(MAX_ATTEMPTS)),
                new Update().set("status", JobStatus.FAILED).set("finishedOn", now)
                        .set("error", "Stopped " + MAX_ATTEMPTS + " times without finishing"), Job.class).getModifiedCount();
        return cancelled + failed;
    }

    /**
     * The next job for {@code worker}, if any: queued, or running with its lease run out; never
     * one of an organisation already running {@code perOrganisation} jobs.
     */
    public Optional<Job> claim(String worker, Duration lease, int perOrganisation) {
        Instant now = clock.instant();
        List<String> busy = busyOrganisations(now, perOrganisation);
        Criteria queued = Criteria.where("status").is(JobStatus.QUEUED).and("cancelRequested").ne(true);
        Criteria orphaned = Criteria.where("status").is(JobStatus.RUNNING).and("leaseUntil").lt(now)
                .and("cancelRequested").ne(true).and("attempt").lt(MAX_ATTEMPTS);
        Criteria criteria = new Criteria().orOperator(queued, orphaned);
        if (!busy.isEmpty()) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("organisationId").nin(busy));
        }
        Query query = Query.query(criteria).with(Sort.by(Sort.Order.desc("priority"), Sort.Order.asc("queuedOn")));
        Update update = new Update().set("status", JobStatus.RUNNING).set("worker", worker)
                .set("leaseUntil", now.plus(lease)).set("startedOn", now).inc("attempt", 1);
        return Optional.ofNullable(plane.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Job.class));
    }

    private List<String> busyOrganisations(Instant now, int perOrganisation) {
        var aggregation = Aggregation.newAggregation(Job.class,
                Aggregation.match(Criteria.where("status").is(JobStatus.RUNNING).and("leaseUntil").gte(now)),
                Aggregation.group("organisationId").count().as("n"),
                Aggregation.match(Criteria.where("n").gte(perOrganisation)));
        return plane.aggregate(aggregation, Document.class).getMappedResults().stream()
                .map(row -> row.get("_id")).filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    /** Extends the lease and records progress; says whether to carry on. */
    public Beat heartbeat(Fence fence, Duration lease, Progress progress) {
        Update update = progress(new Update(), progress).set("leaseUntil", clock.instant().plus(lease));
        Job job = plane.findAndModify(fenced(fence), update, FindAndModifyOptions.options().returnNew(true), Job.class);
        if (job == null) {
            return Beat.LOST;
        }
        return job.isCancelRequested() ? Beat.CANCEL : Beat.OK;
    }

    /** Ends the run; false when the job was no longer this worker's, and nothing was written. */
    public boolean finish(Fence fence, JobStatus status, String error, Progress progress) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException(status + " does not finish a job");
        }
        Update update = progress(new Update(), progress).set("status", status).set("finishedOn", clock.instant())
                .set("error", error).unset("leaseUntil");
        return plane.updateFirst(fenced(fence), update, Job.class).getModifiedCount() > 0;
    }

    private static Query fenced(Fence fence) {
        return Query.query(Criteria.where("_id").is(fence.id()).and("worker").is(fence.worker())
                .and("attempt").is(fence.attempt()).and("status").is(JobStatus.RUNNING));
    }

    private static Update progress(Update update, Progress progress) {
        if (progress == null) {
            return update;
        }
        return update.set("done", progress.done()).set("total", progress.total()).set("log", progress.log())
                .set("logChars", progress.logChars()).set("costMicros", progress.costMicros());
    }
}
