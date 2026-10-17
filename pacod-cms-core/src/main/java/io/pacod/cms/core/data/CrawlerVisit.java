package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One request from a crawler to the public site -- the row behind the crawlers screen's log, its
 * top paths and its "last seen".
 *
 * <p>The counts the screen charts do <em>not</em> come from here. They are dotted {@code $inc}s
 * into the traffic buckets ({@link SiteMetric#getCrawlerHitsByBot()} and its siblings),
 * which give every range up to twelve months for free. This collection is the detail, and it
 * expires: <b>31 days</b>, one more than the thirty-day range, so that range's first partial day
 * is never missing rows.
 *
 * <h2>Only automated clients are ever stored</h2>
 * A row is written only for a request the public site has already classified as a crawler or
 * another automated client, which is what lets this collection hold an address and a User-Agent
 * when {@link SiteVisitor} -- the reader statistics -- deliberately holds neither. No reader's
 * address reaches it.
 *
 * <h2>The TTL index is what bounds it</h2>
 * Indexes are built by {@code SiteIndexes} in each website's database when it is provisioned, off
 * the documents {@code MongoConfig}'s {@code @EntityScan} maps. If the TTL index fails to build,
 * the collection grows forever and the ERROR line in the log is the only thing that says so.
 *
 * <p>The id is an ObjectId, not max-plus-one: this is a log written by the thousand, and
 * {@code getMaxId}'s read-then-write would not survive it. {@link #occurredAt} is an
 * {@link Instant} so the mapper writes a plain UTC date -- none of the {@code LocalDateTime}
 * encoding {@code SiteMetricCollector.toDate} has to reproduce by hand.
 */
@Data
@Document(collection = CrawlerVisit.COLLECTION)
@CompoundIndex(name = "crawlerVisit_bot_occurredAt", def = "{'bot': 1, 'occurredAt': -1}")
public class CrawlerVisit {

    public static final String COLLECTION = "crawlerVisit";

    public static final String FIELD_OCCURRED_AT = "occurredAt";
    public static final String FIELD_BOT = "bot";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_VERIFICATION = "verification";
    public static final String FIELD_PATH = "path";
    public static final String FIELD_STATUS = "status";

    /** Longest User-Agent kept; anything past it is truncated, not refused. */
    public static final int MAX_USER_AGENT = 512;

    /** Longest path kept. */
    public static final int MAX_PATH = 2048;

    @Id
    private String id;

    /**
     * When the request arrived -- taken before the request was handled, so a slow DNS
     * verification never moves it. TTL anchor, and also the index every range query uses.
     */
    @Indexed(name = "crawlerVisit_occurredAt_ttl", expireAfter = "31d")
    private Instant occurredAt;

    /** {@code CrawlerClassifier} id, or {@code CrawlerClassifier.OTHER_BOT}. */
    private String bot;

    private CrawlerCategory category;

    private CrawlerVerification verification;

    /** The client address Caddy saw ({@code X-Real-IP}), never the client-supplied XFF. */
    private String ip;

    private String userAgent;

    private String method;

    /** The request path without its query string, so every {@code ?lang=} of a page is one path. */
    private String path;

    private int status;
}
