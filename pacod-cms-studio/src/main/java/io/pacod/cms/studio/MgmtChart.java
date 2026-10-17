package io.pacod.cms.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A line chart, computed to the last coordinate in Java and emitted by Thymeleaf as inline SVG.
 *
 * <h2>Why the server draws it</h2>
 * There is no bundler in this repository, no npm toolchain and no CDN; {@code mgmt.js}'s own
 * header says introducing one is not the job of whatever change happens to need it first. But the
 * stronger reason is that markup which needs a script to mean anything fails silently when the
 * script does: a gallery can ship prev/next controls that do nothing
 * ({@code articleGalleryShipsNoStaticControls} exists to stop that on the public site), and an
 * editor that cannot load its plugins can go unnoticed. A chart assembled here cannot fail that
 * way: with scripting off, the page still draws.
 *
 * <p>Colours are not set here either. Each series carries a key that becomes a CSS class, so the
 * palette lives in {@code mgmt.css} with the rest of the design tokens and follows the light/dark
 * toggle for free.
 *
 * <h2>Hover without JavaScript</h2>
 * Every bucket gets a full-height transparent {@link Band} carrying a {@code <title>}, which is
 * the browser's own tooltip -- one element per bucket rather than one per point per series, and it
 * reports all series at the same instant, which is the comparison anyone hovering is making.
 * Point markers are drawn only on short windows ({@link #MAX_DOTS}); on a 168-point week they
 * would be noise, and the bands carry the numbers anyway.
 */
public final class MgmtChart {

    /** Unitless. CSS scales the whole thing with {@code width: 100%}, so these are ratios. */
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 260;

    private static final int PAD_LEFT = 52;
    private static final int PAD_RIGHT = 14;
    private static final int PAD_TOP = 14;
    private static final int PAD_BOTTOM = 30;

    private static final int GRIDLINES = 4;
    private static final int MAX_X_TICKS = 7;

    /** Above this many buckets, markers stop helping and the hover bands do the work alone. */
    private static final int MAX_DOTS = 48;

    private MgmtChart() {
    }

    /**
     * One line on the chart.
     *
     * @param key    becomes a CSS class; see {@code mgmt.css}
     * @param points the {@code points} attribute of a {@code <polyline>}
     * @param area   the {@code d} attribute of the tinted fill beneath it, empty when there is
     *               nothing to fill
     * @param dots   marker positions, empty on long windows
     */
    public record Series(String key, String label, long total, long peak,
                         String points, String area, List<Dot> dots) {
    }

    public record Dot(String x, String y) {
    }

    public record Gridline(String y, String label) {
    }

    public record XTick(String x, String label) {
    }

    /** A full-height hover target for one bucket. {@code tooltip} becomes its {@code <title>}. */
    public record Band(String x, String width, String tooltip) {
    }

    /**
     * Everything the template needs as a ready-made attribute value.
     *
     * <p>Including the label anchors, which look like they belong in the markup and do not:
     * Thymeleaf cannot do arithmetic on these without turning every coordinate into a number the
     * expression language then re-formats in the platform locale -- which is how a console in a
     * decimal-comma language (Bulgarian, German, Russian) writes {@code 52,00} into an SVG
     * attribute and moves the axis.
     */
    public record Chart(String viewBox, int width, int height,
                        String plotTop, String plotBottom, String plotLeft, String plotRight,
                        String plotHeight, String labelX, String xLabelY,
                        List<Gridline> gridlines, List<XTick> xTicks,
                        List<Series> series, List<Band> bands, boolean empty) {
    }

    /** One series' worth of input: a label, a CSS key, and one value per bucket. */
    public record Input(String key, String label, List<Long> values) {
    }

    /**
     * Builds a chart from parallel series.
     *
     * <p>All series share one axis, deliberately. A per-series scale would let two lines cross on
     * screen while one was ten times the other, which is a chart that lies. Callers that hold
     * quantities of genuinely different magnitude -- page views against banner clicks -- ask for
     * two charts instead.
     *
     * @param labels  one axis label per bucket, same length as every series' values
     * @param tooltips one tooltip line per bucket
     */
    public static Chart of(List<String> labels, List<String> tooltips, List<Input> inputs) {
        int count = labels == null ? 0 : labels.size();

        if (count == 0 || inputs == null || inputs.isEmpty()) {
            return new Chart(viewBox(), WIDTH, HEIGHT,
                    n(PAD_TOP), n(HEIGHT - PAD_BOTTOM), n(PAD_LEFT), n(WIDTH - PAD_RIGHT),
                    n(HEIGHT - PAD_TOP - PAD_BOTTOM), n(PAD_LEFT - 10), n(HEIGHT - PAD_BOTTOM + 18),
                    List.of(), List.of(), List.of(), List.of(), true);
        }

        long peak = 0;
        for (Input input : inputs) {
            for (Long value : input.values()) {
                peak = Math.max(peak, value == null ? 0 : value);
            }
        }

        // A flat zero series still needs an axis, or every point lands on a division by zero.
        long axisMax = niceCeiling(peak);

        double plotWidth = (double) WIDTH - PAD_LEFT - PAD_RIGHT;
        double plotHeight = (double) HEIGHT - PAD_TOP - PAD_BOTTOM;
        double baseline = PAD_TOP + plotHeight;

        // One bucket would put its single point at the left edge with nothing to compare it to;
        // centring it reads as "one reading, no trend", which is what it is.
        double step = count == 1 ? 0 : plotWidth / (count - 1);

        List<Series> series = new ArrayList<>();
        for (Input input : inputs) {
            series.add(buildSeries(input, count, axisMax, step, plotWidth, plotHeight, baseline));
        }

        return new Chart(viewBox(), WIDTH, HEIGHT,
                n(PAD_TOP), n(baseline), n(PAD_LEFT), n(WIDTH - PAD_RIGHT),
                n(plotHeight), n(PAD_LEFT - 10), n(baseline + 18),
                gridlines(axisMax, plotHeight),
                xTicks(labels, count, step),
                series,
                bands(tooltips, count, plotWidth),
                peak == 0);
    }

    private static Series buildSeries(Input input, int count, long axisMax, double step,
                                      double plotWidth, double plotHeight, double baseline) {
        StringBuilder points = new StringBuilder();
        List<Dot> dots = new ArrayList<>();
        long total = 0;
        long peak = 0;

        for (int i = 0; i < count; i++) {
            List<Long> values = input.values();
            long value = i < values.size() && values.get(i) != null ? values.get(i) : 0L;
            total += value;
            peak = Math.max(peak, value);

            double x = count == 1 ? PAD_LEFT + plotWidth / 2 : PAD_LEFT + i * step;
            double y = baseline - (plotHeight * value / axisMax);

            if (i > 0) {
                points.append(' ');
            }
            points.append(n(x)).append(',').append(n(y));

            if (count <= MAX_DOTS) {
                dots.add(new Dot(n(x), n(y)));
            }
        }

        // The tinted fill is the polyline closed down to the baseline at both ends. A single
        // point closes into a zero-width sliver, so it is left off entirely.
        String area = count < 2 ? "" : "M " + n(PAD_LEFT) + "," + n(baseline)
                + " L " + points + " L " + n(PAD_LEFT + (count - 1) * step) + "," + n(baseline) + " Z";

        return new Series(input.key(), input.label(), total, peak, points.toString(), area, dots);
    }

    private static List<Gridline> gridlines(long axisMax, double plotHeight) {
        List<Gridline> lines = new ArrayList<>();

        for (int i = 0; i <= GRIDLINES; i++) {
            long value = axisMax * i / GRIDLINES;
            double y = PAD_TOP + plotHeight - (plotHeight * i / GRIDLINES);
            lines.add(new Gridline(n(y), compact(value)));
        }

        return lines;
    }

    /**
     * Axis labels, thinned to at most {@link #MAX_X_TICKS}.
     *
     * <p>The last bucket always gets one: "now" is the reading anyone looks at first, and an
     * evenly-strided selection drops it whenever the count is not a multiple of the stride.
     */
    private static List<XTick> xTicks(List<String> labels, int count, double step) {
        List<XTick> ticks = new ArrayList<>();
        int stride = Math.max(1, (int) Math.ceil((double) count / MAX_X_TICKS));

        for (int i = 0; i < count; i += stride) {
            ticks.add(new XTick(n(PAD_LEFT + i * step), labels.get(i)));
        }

        int last = count - 1;
        if (last > 0 && (last % stride) != 0) {
            ticks.add(new XTick(n(PAD_LEFT + last * step), labels.get(last)));
        }

        return ticks;
    }

    private static List<Band> bands(List<String> tooltips, int count, double plotWidth) {
        if (tooltips == null || tooltips.isEmpty()) {
            return List.of();
        }

        List<Band> bands = new ArrayList<>();
        double width = plotWidth / count;

        for (int i = 0; i < count && i < tooltips.size(); i++) {
            bands.add(new Band(n(PAD_LEFT + i * width), n(width), tooltips.get(i)));
        }

        return bands;
    }

    /**
     * Rounds the peak up to a round number so the top gridline reads as a number a person would
     * choose. Never zero: an empty chart still needs an axis to divide by.
     */
    static long niceCeiling(long peak) {
        if (peak <= 0) {
            return 1;
        }
        if (peak <= 5) {
            return 5;
        }

        long magnitude = 1;
        while (magnitude * 100 < peak) {
            magnitude *= 10;
        }

        for (long candidate : new long[]{10, 20, 25, 50, 100}) {
            long value = candidate * magnitude;
            if (value >= peak) {
                return value;
            }
        }

        return magnitude * 100;
    }

    /** Axis labels only. Thousands as {@code 12k} so a 52px gutter does not have to grow. */
    static String compact(long value) {
        if (value < 1000) {
            return Long.toString(value);
        }
        if (value < 1_000_000) {
            return trim(value / 1000d) + "k";
        }
        return trim(value / 1_000_000d) + "M";
    }

    private static String trim(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }

    private static String viewBox() {
        return "0 0 " + WIDTH + " " + HEIGHT;
    }

    /**
     * A coordinate as SVG wants it.
     *
     * <p>{@code Locale.ROOT} is load-bearing: under a decimal-comma locale -- Bulgarian, German and
     * Russian among them -- a default-locale format writes {@code 12,5}, which SVG reads as two
     * numbers. The chart would not fail -- it would draw somewhere else.
     */
    private static String n(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
