package io.pacod.cms.core.data;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * An hour of site traffic, kept for thirty days.
 *
 * <p>The hourly grain is what makes intraday shape visible -- when the morning peak is, whether
 * today's traffic has arrived yet. It is also the grain nobody needs a year of, so it expires and
 * {@link SiteMetricDay} carries the long history.
 *
 * <h2>One index doing two jobs</h2>
 * {@link #start} carries a single {@code @Indexed} that both expires the bucket and backs the
 * range query the chart runs -- a TTL index is an ordinary ascending index with
 * {@code expireAfterSeconds} attached, so {@code MongoPersistentEntityIndexResolver} emits one
 * definition and MongoDB serves both uses from it. Do <b>not</b> add a second {@code @Indexed} on
 * this field expecting a separate range index; it would be a duplicate key pattern under a
 * different name.
 *
 * <p>It is created by {@code SiteIndexes} in each website's database when the studio provisions
 * it. A failure there is logged at ERROR and provisioning continues, and the contract is that a
 * logged failure means the index is simply absent: no expiry, and every chart render a collection
 * scan. Treat the log line as an alert.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "siteMetricHour")
public class SiteMetricHour extends SiteMetric {

    public static final String COLLECTION = "siteMetricHour";

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    /**
     * TTL anchor and range index in one. Thirty days from the hour described, not from the last
     * write: the anchor is the bucket's own start, and {@code SiteMetricCollector} writes it with
     * {@code $setOnInsert} so a flush can never move it.
     */
    @Indexed(name = "siteMetricHour_start_ttl", expireAfter = "30d")
    private LocalDateTime start;

    /** The bucket key for {@code at}: {@code "2026-08-09T14"}. */
    public static String idFor(LocalDateTime at) {
        return at.format(ID_FORMAT);
    }
}
