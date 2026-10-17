package io.pacod.cms.core.data;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A day of site traffic, kept forever.
 *
 * <p>Written by the same flush that writes {@link SiteMetricHour} -- the additive counters
 * {@code $inc} into both grains in one cycle -- which is why this project has no nightly rollup
 * job and why the two grains cannot disagree. The one field that is <em>not</em> written that way
 * is {@code visitors}: distinct readers do not sum across hours, so the daily figure is counted
 * off the {@code siteVisitor} collection instead.
 *
 * <p>No TTL. A year-on-year comparison is the whole reason this grain exists, so the moment it
 * expires it stops being useful. Three hundred and sixty-five documents a year is not a size
 * problem.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "siteMetricDay")
public class SiteMetricDay extends SiteMetric {

    public static final String COLLECTION = "siteMetricDay";

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Midnight of the day described. Indexed for the range query; deliberately no {@code expireAfter}. */
    @Indexed(name = "siteMetricDay_start")
    private LocalDateTime start;

    /** The bucket key for {@code at}: {@code "2026-08-09"}. */
    public static String idFor(LocalDateTime at) {
        return at.format(ID_FORMAT);
    }
}
