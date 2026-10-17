package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Work the studio does in the background for a website: a PDF export, a translation run.
 *
 * <p>In the control plane, not the website's database, so one runner sees every website's queue
 * and a job for a deleted website can be found and refused rather than recreating its database.
 * {@code JobQueue} (core.jobs) is the only writer.
 *
 * <h2>Fencing</h2>
 * A worker claims a job by raising {@link #attempt} and naming itself in {@link #worker}; every
 * write after that is conditional on both. A worker that lost its lease -- a stall past
 * {@link #leaseUntil}, a restart -- can then write nothing over the run that took it over.
 *
 * <p>Finished jobs expire after 30 days ({@link #finishedOn} is a TTL index; an unfinished job has
 * none and stays).
 */
@Data
@Document(collection = "job")
@CompoundIndex(name = "claim", def = "{'status': 1, 'priority': -1, 'queuedOn': 1}")
@CompoundIndex(name = "site_queuedOn", def = "{'siteId': 1, 'queuedOn': -1}")
public class Job {

    /** A raised priority: claimed before everything queued at the normal one. */
    public static final int PRIORITY_NEXT = 10;
    /** What the log keeps: the end of it, since the end says how a run finished. */
    public static final int MAX_LOG = 64 * 1024;

    @Id
    private String id;

    private String siteId;
    private String organisationId;
    private JobType type;
    private Map<String, String> params = new LinkedHashMap<>();

    private JobStatus status;
    private int priority;
    private Instant queuedOn;
    /** Who asked: the person's display name, for the screen. */
    private String queuedBy;

    private Instant startedOn;
    @Indexed(name = "finishedOn_ttl", expireAfter = "30d")
    private Instant finishedOn;

    private String worker;
    private int attempt;
    private Instant leaseUntil;
    private boolean cancelRequested;

    private int done;
    private int total;
    /** The log's last {@link #MAX_LOG} characters; {@link #logChars} is how long it really was. */
    private String log;
    private long logChars;
    private String error;
    /** What its AI calls cost, in millionths of a US dollar, as the gateway reported. */
    private long costMicros;
}
