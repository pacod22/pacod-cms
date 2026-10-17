package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One reader, on one day. The whole of what the platform knows about a visitor.
 *
 * <p>Three fields, and the list is the point: a hash, a day, and when that reader was last seen.
 * <b>No IP address, no User-Agent, no path, no referrer</b>, and nothing that survives two days.
 * The public site is deliberately {@code SessionCreationPolicy.STATELESS} and sets a cookie only
 * for language ({@code LocaleConfig}); counting readers must not change either fact, so
 * {@code VisitorIdentity} derives {@link #hash} from the request and the request alone.
 *
 * <p>The hash is salted and truncated, so it is neither reversible from a list of candidate
 * addresses nor a full-fidelity identifier. Readers behind one NAT collapse into one row. That
 * undercount is the accepted price of adding no cookie and no consent surface; the monitoring
 * screen says so where the number is shown.
 *
 * <h2>The TTL anchor is {@code day}, not {@code lastSeen}</h2>
 * {@link #lastSeen} is rewritten every flush interval a reader stays on the site, so anchoring the
 * TTL there would let a reader who never leaves keep their row alive indefinitely. {@link #day}
 * never moves, so a row is gone two days after the day it belongs to, whatever the reader does.
 *
 * <p>One row per reader <em>per day</em> rather than per reader: it is what makes the daily
 * distinct count a plain {@code countDocuments}, and it bounds how long any one hash persists.
 */
@Data
@Document(collection = "siteVisitor")
public class SiteVisitor {

    public static final String COLLECTION = "siteVisitor";

    public static final String FIELD_HASH = "hash";
    public static final String FIELD_DAY = "day";
    public static final String FIELD_LAST_SEEN = "lastSeen";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** {@code "<hash>|2026-08-09"}. Computed, never assigned -- see {@link SiteMetric}. */
    @Id
    private String id;

    /**
     * Salted, truncated digest of the request's client address and User-Agent.
     *
     * <p>Stored separately from the id as well as inside it, because the live count is a
     * {@code distinct} over this field: a reader crossing midnight holds two rows and must still
     * count once.
     */
    private String hash;

    /** Midnight of the day this row covers. TTL anchor -- see the class javadoc. */
    @Indexed(name = "siteVisitor_day_ttl", expireAfter = "2d")
    private LocalDateTime day;

    /** Bumped every flush while the reader is active. Indexed: the live count queries on it. */
    @Indexed(name = "siteVisitor_lastSeen")
    private LocalDateTime lastSeen;

    public static String idFor(String hash, LocalDateTime at) {
        return hash + "|" + at.format(DAY_FORMAT);
    }
}
