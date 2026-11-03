package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One page view by a reader -- the row behind the reader log, its addresses and user-agents
 * tables and its filtered splits ({@code /mgmt/monitoring/readers}).
 *
 * <p>The counts the monitoring screen charts do <em>not</em> come from here. They are dotted
 * {@code $inc}s into the traffic buckets ({@link SiteMetric#getViewsByLanguage()} and its
 * siblings), which keep every range up to twelve months. This collection is the detail, and it
 * holds what the buckets must not: the reader's <b>IP address and raw User-Agent</b>. That is
 * personal data, which is why it expires.
 *
 * <h2>The TTL index is what bounds it</h2>
 * <b>31 days</b>, as {@link CrawlerVisit}'s: one more than the thirty-day range, so that range's
 * first partial day is never missing rows. Indexes are built by {@code SiteIndexes} in each
 * website's database when it is provisioned -- {@code SiteProvisioner.SCHEMA_VERSION} was raised
 * with this class so that every existing website is provisioned again and gets it. If the index
 * fails to build, addresses are kept forever and the ERROR line in the log is the only thing
 * that says so.
 *
 * <h2>What a row holds</h2>
 * The path without its query string (a search term is the reader's own text), the address the
 * site's {@code ClientAddress} derived (never a client-written {@code X-Forwarded-For}), the
 * User-Agent cut at {@link #MAX_USER_AGENT}, the families the site classified it as, both
 * languages as the buckets key them ({@code und} when unknown), and the reader's hash from
 * {@code VisitorIdentity}, so a filtered view can count distinct readers. Only views the site
 * counts as a reader's are written: crawlers, previews and print renders never reach it.
 *
 * <p>The site writes it only while {@code app.metrics.reader-log.enabled} ({@code READER_LOG}) is
 * on. The id is an ObjectId, and {@link #occurredAt} an {@link Instant}, for the reasons
 * {@link CrawlerVisit} gives.
 */
@Data
@Document(collection = ReaderVisit.COLLECTION)
@CompoundIndex(name = "readerVisit_ip_occurredAt", def = "{'ip': 1, 'occurredAt': -1}")
public class ReaderVisit {

    public static final String COLLECTION = "readerVisit";

    public static final String FIELD_OCCURRED_AT = "occurredAt";
    public static final String FIELD_PAGE = "page";
    public static final String FIELD_PATH = "path";
    public static final String FIELD_SERVED_LANGUAGE = "servedLanguage";
    public static final String FIELD_BROWSER_LANGUAGE = "browserLanguage";
    public static final String FIELD_BROWSER = "browser";
    public static final String FIELD_OS = "os";
    public static final String FIELD_DEVICE = "device";
    public static final String FIELD_IP = "ip";
    public static final String FIELD_USER_AGENT = "userAgent";
    public static final String FIELD_VISITOR = "visitor";

    /** How long a row is kept: the TTL below, spelled for the code that reads a window. */
    public static final int RETENTION_DAYS = 31;

    /** Longest User-Agent kept; anything past it is truncated, not refused. */
    public static final int MAX_USER_AGENT = CrawlerVisit.MAX_USER_AGENT;

    /** Longest path kept. */
    public static final int MAX_PATH = CrawlerVisit.MAX_PATH;

    /** The language key for "not known": no header, a malformed one, or no served language. */
    public static final String UNKNOWN_LANGUAGE = "und";

    @Id
    private String id;

    /** When the view was counted. TTL anchor, and the index every window query uses. */
    @Indexed(name = "readerVisit_occurredAt_ttl", expireAfter = "31d")
    private Instant occurredAt;

    private BannerPage page;

    /** The request path without its query string. */
    private String path;

    /** The language the page was shown in, as {@code viewsByLanguage} keys it. */
    private String servedLanguage;

    /** The reader's first {@code Accept-Language} choice, as {@code viewsByBrowserLanguage} keys it. */
    private String browserLanguage;

    private ReaderBrowser browser;

    private ReaderOs os;

    private ReaderDevice device;

    /** The reader's address, an IP literal as {@code IpLiterals.normalise} spells it. */
    private String ip;

    private String userAgent;

    /** {@code VisitorIdentity}'s hash, the key {@link SiteVisitor} holds: the reader counts are distinct ones. */
    private String visitor;
}
