package io.pacod.cms.studio;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The windows the monitoring screen offers, and the grain each one reads.
 *
 * <p>Two grains exist -- {@code siteMetricHour}, thirty days and then expired, and
 * {@code siteMetricDay}, kept forever -- and the split here is simply which one answers the
 * question without a wasteful read. A day at hourly grain shows the morning peak; a year at hourly
 * grain would be nine thousand points on a chart a thousand pixels wide, and the hourly buckets
 * would have expired out from under most of it anyway.
 *
 * <p>{@link #H24} and {@link #D7} therefore read hours; {@link #D30} and {@link #M12} read days.
 * That boundary is also why {@code D7} is safe: seven days is well inside the hourly TTL.
 */
public enum MonitoringRange {

    H24("24h", "Последните 24 часа", ChronoUnit.HOURS, 24, "dd.MM HH:mm", "HH:mm"),
    D7("7d", "Последните 7 дни", ChronoUnit.HOURS, 24 * 7, "dd.MM HH:mm", "dd.MM"),
    D30("30d", "Последните 30 дни", ChronoUnit.DAYS, 30, "dd.MM.yyyy", "dd.MM"),
    M12("12m", "Последните 12 месеца", ChronoUnit.DAYS, 365, "dd.MM.yyyy", "MM.yyyy");

    private final String key;
    private final String label;
    private final ChronoUnit grain;
    private final int buckets;
    private final DateTimeFormatter tooltipFormat;
    private final DateTimeFormatter axisFormat;

    MonitoringRange(String key, String label, ChronoUnit grain, int buckets,
                    String tooltipPattern, String axisPattern) {
        this.key = key;
        this.label = label;
        this.grain = grain;
        this.buckets = buckets;
        this.tooltipFormat = DateTimeFormatter.ofPattern(tooltipPattern);
        this.axisFormat = DateTimeFormatter.ofPattern(axisPattern);
    }

    /** Unknown, absent and malformed all mean the default window rather than an error page. */
    public static MonitoringRange of(String key) {
        for (MonitoringRange range : values()) {
            if (range.key.equalsIgnoreCase(key)) {
                return range;
            }
        }
        return H24;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public ChronoUnit getGrain() {
        return grain;
    }

    public int getBuckets() {
        return buckets;
    }

    public boolean isHourly() {
        return grain == ChronoUnit.HOURS;
    }

    /** The first bucket of the window: {@code buckets - 1} back from the one open now. */
    public LocalDateTime from(LocalDateTime now) {
        return to(now).minus(buckets - 1L, grain);
    }

    /** The bucket open at {@code now}, which is the last one the window covers. */
    public LocalDateTime to(LocalDateTime now) {
        return now.truncatedTo(grain);
    }

    public String formatTooltip(LocalDateTime at) {
        return at.format(tooltipFormat);
    }

    public String formatAxis(LocalDateTime at) {
        return at.format(axisFormat);
    }
}
