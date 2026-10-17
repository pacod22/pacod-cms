package io.pacod.cms.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chart's geometry, which nothing else can check.
 *
 * <p>{@code MgmtScreenRenderTest} proves a {@code <polyline>} reaches the page; it cannot tell a
 * correct one from a polyline drawn off-canvas. Every failure below produces a page that renders
 * without an error and shows the wrong thing.
 */
class MgmtChartTest {

    @Test
    @DisplayName("coordinates are written in the C locale whatever the platform's is")
    void coordinatesAreLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            // This console runs in Bulgarian, where the decimal separator is a comma. An SVG
            // attribute of "52,00" is two numbers, not one -- the chart would draw somewhere
            // else, silently, and only in production.
            Locale.setDefault(Locale.forLanguageTag("bg-BG"));

            MgmtChart.Chart chart = MgmtChart.of(List.of("a", "b"), List.of("A", "B"),
                    List.of(new MgmtChart.Input("views", "Прегледи", List.of(1L, 2L))));

            assertThat(chart.series().get(0).points()).doesNotContain(",00,");
            assertThat(chart.plotLeft()).isEqualTo("52.00");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("all series share one axis, so two lines cannot cross while one is ten times the other")
    void seriesShareOneAxis() {
        MgmtChart.Chart chart = MgmtChart.of(List.of("a", "b"), List.of("A", "B"), List.of(
                new MgmtChart.Input("views", "Прегледи", List.of(0L, 100L)),
                new MgmtChart.Input("visitors", "Посетители", List.of(0L, 10L))));

        double viewsPeakY = lastY(chart.series().get(0).points());
        double visitorsPeakY = lastY(chart.series().get(1).points());

        // Both start on the baseline; the bigger series must end visibly higher up the chart.
        assertThat(viewsPeakY).isLessThan(visitorsPeakY);
    }

    @Test
    @DisplayName("an all-zero window is reported empty rather than drawn as a line along the axis")
    void anAllZeroWindowIsEmpty() {
        MgmtChart.Chart chart = MgmtChart.of(List.of("a", "b"), List.of("A", "B"),
                List.of(new MgmtChart.Input("views", "Прегледи", List.of(0L, 0L))));

        // This is what a fresh deployment looks like: nothing is back-filled, so the series
        // is genuinely flat zero. The template branches on this to say so.
        assertThat(chart.empty()).isTrue();
    }

    @Test
    @DisplayName("a single bucket is centred and draws no area, rather than a zero-width sliver")
    void oneBucketIsStillReadable() {
        MgmtChart.Chart chart = MgmtChart.of(List.of("a"), List.of("A"),
                List.of(new MgmtChart.Input("views", "Прегледи", List.of(5L))));

        MgmtChart.Series series = chart.series().get(0);

        assertThat(series.points().split(" ")).hasSize(1);
        assertThat(series.area()).isEmpty();
        // A lone point has no line, so the marker is the only thing that renders it at all.
        assertThat(series.dots()).hasSize(1);
    }

    @Test
    @DisplayName("markers are dropped on long windows, where the hover bands do the work")
    void longWindowsDropTheMarkers() {
        List<Long> week = java.util.stream.LongStream.range(0, 168).boxed().toList();

        MgmtChart.Chart chart = MgmtChart.of(
                week.stream().map(String::valueOf).toList(),
                week.stream().map(String::valueOf).toList(),
                List.of(new MgmtChart.Input("views", "Прегледи", week)));

        assertThat(chart.series().get(0).dots()).isEmpty();
        // One band per bucket regardless: the tooltips are the whole hover mechanism and there is
        // no JavaScript behind them.
        assertThat(chart.bands()).hasSize(168);
    }

    @Test
    @DisplayName("the last bucket always gets an axis label")
    void theNewestBucketIsAlwaysLabelled() {
        List<String> labels = java.util.stream.IntStream.range(0, 24)
                .mapToObj(i -> "h" + i).toList();

        MgmtChart.Chart chart = MgmtChart.of(labels, labels,
                List.of(new MgmtChart.Input("views", "Прегледи",
                        java.util.stream.LongStream.range(0, 24).boxed().toList())));

        // "Now" is the reading anyone looks at first, and an evenly-strided selection drops it
        // whenever the bucket count is not a multiple of the stride. All four MonitoringRange
        // sizes happen to land off the stride today (24, 168, 30, 365), so the fallback is what
        // is under test rather than luck -- change MAX_X_TICKS and this is the assertion that
        // should be re-checked against each of them, not just against 24.
        assertThat(chart.xTicks()).extracting(MgmtChart.XTick::label).contains("h23");
    }

    @Test
    @DisplayName("the axis tops out at a number a person would have chosen")
    void theAxisRoundsUp() {
        assertThat(MgmtChart.niceCeiling(0)).isEqualTo(1);     // never zero: everything divides by it
        assertThat(MgmtChart.niceCeiling(3)).isEqualTo(5);
        assertThat(MgmtChart.niceCeiling(87)).isEqualTo(100);
        assertThat(MgmtChart.niceCeiling(1200)).isEqualTo(2000);
    }

    @Test
    @DisplayName("axis labels stay short enough for a 52px gutter")
    void axisLabelsAreCompact() {
        assertThat(MgmtChart.compact(999)).isEqualTo("999");
        assertThat(MgmtChart.compact(1200)).isEqualTo("1.2k");
        assertThat(MgmtChart.compact(2000)).isEqualTo("2k");
        assertThat(MgmtChart.compact(3_400_000)).isEqualTo("3.4M");
    }

    private static double lastY(String points) {
        String last = points.substring(points.lastIndexOf(' ') + 1);
        return Double.parseDouble(last.split(",")[1]);
    }
}
