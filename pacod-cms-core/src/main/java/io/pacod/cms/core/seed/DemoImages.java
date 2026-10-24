package io.pacod.cms.core.seed;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The demo content's pictures, drawn when a website is given them rather than shipped: abstract
 * compositions of gradients and shapes, so the repository carries no photograph and no licence to
 * track.
 *
 * <ul>
 *   <li><b>No text is drawn.</b> A runtime image may have no fonts at all (no fontconfig on a slim
 *       base), and drawing a glyph there throws. The alt text says what the picture shows.</li>
 *   <li><b>Deterministic</b>: the shapes come from a {@link Random} seeded with the picture's key,
 *       so the same key gives the same picture, which is what lets a test pin it.</li>
 *   <li><b>{@code TYPE_INT_RGB}</b>, opaque: the JPEG writer mangles an image with an alpha
 *       channel. Translucent shapes are composited onto the opaque background as they are drawn.</li>
 *   <li><b>Sized to the rules</b>: the caller passes the website's required size, and
 *       {@link #jpeg} steps the quality down until the file fits the byte limit, or refuses.</li>
 * </ul>
 */
public final class DemoImages {

    /** The compositions there are; a seed names one by its lowercase name. */
    public enum Motif {
        /** A grid of rounded tiles, some lit. */
        GRID,
        /** Layered ridges, a sun behind them. */
        PEAKS,
        /** Overlapping discs. */
        CIRCLES,
        /** Diagonal bands. */
        STRIPES,
        /** Concentric rings around a planet. */
        ORBIT,
        /** Stacked waves. */
        WAVES,
        /** A field of stars around a faint galactic band. */
        STARFIELD,
        /** A large lit planet, its night side in shadow, a ring and a moon. */
        PLANET,
        /** A rocket climbing on its exhaust trail through the stars. */
        ROCKET,
        /** A station: modules along a truss, wide solar wings. */
        STATION,
        /** Soft coloured clouds of gas, stars in front of them. */
        NEBULA,
        /** A cratered horizon with a world rising over it. */
        MOONSCAPE,
        /** Tumbling rocks across a starfield. */
        ASTEROIDS;

        public static Motif of(String name) {
            return name == null ? CIRCLES : valueOf(name.strip().toUpperCase(Locale.ROOT));
        }
    }

    /** The highest and lowest JPEG quality tried, and the step between them. */
    private static final float BEST = 0.9f;
    private static final float WORST = 0.3f;
    private static final float STEP = 0.1f;

    private DemoImages() {
    }

    /**
     * Draws one picture.
     *
     * @param key     what seeds the shapes: the same key gives the same picture
     * @param colours two or more {@code #rrggbb} colours: the background's gradient, then the shapes'
     */
    public static BufferedImage draw(String key, Motif motif, List<String> colours, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A picture needs a size, not " + width + "x" + height);
        }
        List<Color> palette = colours.stream().map(Color::decode).toList();
        if (palette.size() < 2) {
            throw new IllegalArgumentException("A picture needs at least two colours: " + key);
        }
        Random random = new Random(key.hashCode());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setPaint(new GradientPaint(0, 0, palette.get(0), width, height, palette.get(1)));
            g.fillRect(0, 0, width, height);
            Color accent = palette.size() > 2 ? palette.get(2) : Color.WHITE;
            switch (motif) {
                case GRID -> grid(g, random, width, height, accent);
                case PEAKS -> peaks(g, random, width, height, palette, accent);
                case CIRCLES -> circles(g, random, width, height, accent);
                case STRIPES -> stripes(g, random, width, height, accent);
                case ORBIT -> orbit(g, random, width, height, accent);
                case WAVES -> waves(g, random, width, height, accent);
                case STARFIELD -> starfield(g, random, width, height, accent);
                case PLANET -> planet(g, random, width, height, palette, accent);
                case ROCKET -> rocket(g, random, width, height, accent);
                case STATION -> station(g, random, width, height, accent);
                case NEBULA -> nebula(g, random, width, height, palette, accent);
                case MOONSCAPE -> moonscape(g, random, width, height, palette, accent);
                case ASTEROIDS -> asteroids(g, random, width, height, palette, accent);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * The picture as a JPEG of at most {@code maxBytes}, at the best quality that fits.
     *
     * @param maxBytes the limit, or zero or less for none
     * @throws IllegalStateException when even the lowest quality does not fit
     */
    public static byte[] jpeg(BufferedImage image, long maxBytes) {
        for (float quality = BEST; quality >= WORST - 0.001f; quality -= STEP) {
            byte[] bytes = encode(image, quality);
            if (maxBytes <= 0 || bytes.length <= maxBytes) {
                return bytes;
            }
        }
        throw new IllegalStateException("A " + image.getWidth() + "x" + image.getHeight()
                + " picture does not fit in " + maxBytes + " bytes even at the lowest quality");
    }

    private static byte[] encode(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static void alpha(Graphics2D g, float alpha) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
    }

    private static void grid(Graphics2D g, Random random, int w, int h, Color accent) {
        int columns = 6 + random.nextInt(3);
        double cell = (double) w / columns;
        int rows = (int) Math.ceil(h / cell);
        double gap = cell * 0.12;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                boolean lit = random.nextInt(5) == 0;
                g.setColor(lit ? accent : Color.WHITE);
                alpha(g, lit ? 0.85f : 0.06f + random.nextFloat() * 0.12f);
                g.fill(new RoundRectangle2D.Double(column * cell + gap, row * cell + gap,
                        cell - 2 * gap, cell - 2 * gap, cell * 0.25, cell * 0.25));
            }
        }
        alpha(g, 1f);
    }

    private static void peaks(Graphics2D g, Random random, int w, int h, List<Color> palette, Color accent) {
        g.setColor(accent);
        alpha(g, 0.9f);
        double sun = h * (0.18 + random.nextDouble() * 0.1);
        g.fill(new Ellipse2D.Double(w * (0.55 + random.nextDouble() * 0.25), h * 0.12, sun, sun));
        for (int layer = 0; layer < 4; layer++) {
            Path2D.Double ridge = new Path2D.Double();
            double base = h * (0.45 + layer * 0.14);
            ridge.moveTo(0, h);
            ridge.lineTo(0, base);
            for (double x = 0; x <= w; x += w / 6.0) {
                ridge.lineTo(x + random.nextDouble() * w / 12, base - random.nextDouble() * h * 0.22);
            }
            ridge.lineTo(w, base);
            ridge.lineTo(w, h);
            ridge.closePath();
            g.setColor(layer % 2 == 0 ? palette.get(0).darker() : palette.get(1).darker());
            alpha(g, 0.45f + layer * 0.15f);
            g.fill(ridge);
        }
        alpha(g, 1f);
    }

    private static void circles(Graphics2D g, Random random, int w, int h, Color accent) {
        for (int i = 0; i < 9; i++) {
            double r = h * (0.12 + random.nextDouble() * 0.35);
            g.setColor(i % 3 == 0 ? accent : Color.WHITE);
            alpha(g, i % 3 == 0 ? 0.75f : 0.12f + random.nextFloat() * 0.2f);
            g.fill(new Ellipse2D.Double(random.nextDouble() * w - r / 2, random.nextDouble() * h - r / 2, r, r));
        }
        alpha(g, 1f);
    }

    private static void stripes(Graphics2D g, Random random, int w, int h, Color accent) {
        double band = w / (7.0 + random.nextInt(4));
        for (double x = -h; x < w + h; x += band * 2) {
            Path2D.Double stripe = new Path2D.Double();
            stripe.moveTo(x, h);
            stripe.lineTo(x + h, 0);
            stripe.lineTo(x + h + band, 0);
            stripe.lineTo(x + band, h);
            stripe.closePath();
            boolean bright = random.nextInt(4) == 0;
            g.setColor(bright ? accent : Color.WHITE);
            alpha(g, bright ? 0.8f : 0.1f);
            g.fill(stripe);
        }
        alpha(g, 1f);
    }

    private static void orbit(Graphics2D g, Random random, int w, int h, Color accent) {
        double cx = w * (0.35 + random.nextDouble() * 0.3);
        double cy = h * (0.4 + random.nextDouble() * 0.2);
        g.setColor(Color.WHITE);
        for (int i = 0; i < 40; i++) {
            alpha(g, 0.3f + random.nextFloat() * 0.6f);
            double star = 1 + random.nextDouble() * 2.5;
            g.fill(new Ellipse2D.Double(random.nextDouble() * w, random.nextDouble() * h, star, star));
        }
        g.setStroke(new BasicStroke(Math.max(1.5f, h / 160f)));
        for (int ring = 1; ring <= 4; ring++) {
            double rx = h * 0.16 * ring * 1.4;
            double ry = rx * 0.42;
            alpha(g, 0.35f);
            g.draw(new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2));
        }
        double planet = h * 0.22;
        g.setColor(accent);
        alpha(g, 1f);
        g.fill(new Ellipse2D.Double(cx - planet / 2, cy - planet / 2, planet, planet));
    }

    private static void waves(Graphics2D g, Random random, int w, int h, Color accent) {
        for (int layer = 0; layer < 6; layer++) {
            Path2D.Double wave = new Path2D.Double();
            double base = h * (0.25 + layer * 0.13);
            double amplitude = h * (0.03 + random.nextDouble() * 0.05);
            double length = w / (1.5 + random.nextDouble() * 2);
            double phase = random.nextDouble() * Math.PI * 2;
            wave.moveTo(0, h);
            for (int x = 0; x <= w; x += 4) {
                wave.lineTo(x, base + Math.sin(x / length * Math.PI * 2 + phase) * amplitude);
            }
            wave.lineTo(w, h);
            wave.closePath();
            g.setColor(layer == 2 ? accent : Color.WHITE);
            alpha(g, layer == 2 ? 0.7f : 0.1f + layer * 0.03f);
            g.fill(wave);
        }
        alpha(g, 1f);
    }

    // ------------------------------------------------------------------- the space motifs

    /** A soft glow: {@code colour} at {@code strength} in the middle, nothing at radius {@code r}. */
    private static void glow(Graphics2D g, double x, double y, double r, Color colour, float strength) {
        Color inner = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), Math.round(255 * strength));
        Color outer = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 0);
        alpha(g, 1f);
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Double(x, y), (float) r,
                new float[]{0f, 1f}, new Color[]{inner, outer}));
        g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
    }

    /** Stars of a few sizes, the brightest with a soft halo. */
    private static void stars(Graphics2D g, Random random, int w, int h, int count) {
        for (int i = 0; i < count; i++) {
            double x = random.nextDouble() * w;
            double y = random.nextDouble() * h;
            double size = 0.6 + Math.pow(random.nextDouble(), 3) * 3.2;
            if (size > 2.6) {
                glow(g, x + size / 2, y + size / 2, size * 3, Color.WHITE, 0.25f);
            }
            g.setColor(Color.WHITE);
            alpha(g, 0.35f + random.nextFloat() * 0.65f);
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }
        alpha(g, 1f);
    }

    private static void starfield(Graphics2D g, Random random, int w, int h, Color accent) {
        // A faint band across the sky: many low ellipses along a diagonal.
        double tilt = -0.25 - random.nextDouble() * 0.3;
        for (int i = 0; i < 60; i++) {
            double t = random.nextDouble();
            double x = t * w;
            double y = h * 0.55 + (t - 0.5) * w * tilt + (random.nextGaussian() * h * 0.06);
            glow(g, x, y, h * (0.08 + random.nextDouble() * 0.14), i % 4 == 0 ? accent : Color.WHITE,
                    0.06f + random.nextFloat() * 0.08f);
        }
        stars(g, random, w, h, 220);
    }

    private static void planet(Graphics2D g, Random random, int w, int h, List<Color> palette, Color accent) {
        stars(g, random, w, h, 120);
        double r = h * (0.32 + random.nextDouble() * 0.1);
        double cx = w * (0.38 + random.nextDouble() * 0.24);
        double cy = h * (0.5 + random.nextDouble() * 0.1);
        Ellipse2D.Double body = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
        glow(g, cx, cy, r * 1.35, accent, 0.25f);
        // The ring's far half, behind the planet.
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (r * 0.05)));
        alpha(g, 0.45f);
        g.draw(new java.awt.geom.Arc2D.Double(cx - r * 1.7, cy - r * 0.32, r * 3.4, r * 0.64, 0, 180,
                java.awt.geom.Arc2D.OPEN));
        g.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), accent,
                (float) (cx + r), (float) (cy + r), palette.get(0).darker()));
        alpha(g, 1f);
        g.fill(body);
        // Bands across the face, clipped to the disc.
        java.awt.Shape clip = g.getClip();
        g.clip(body);
        for (int band = 0; band < 6; band++) {
            g.setColor(band % 2 == 0 ? Color.WHITE : palette.get(1));
            alpha(g, 0.08f + random.nextFloat() * 0.1f);
            double y = cy - r + band * r / 3 + random.nextDouble() * r / 6;
            g.fill(new Ellipse2D.Double(cx - r * 1.3, y, r * 2.6, r * (0.12 + random.nextDouble() * 0.12)));
        }
        // The night side.
        g.setColor(Color.BLACK);
        alpha(g, 0.55f);
        g.fill(new Ellipse2D.Double(cx - r * 0.55, cy - r * 1.05, r * 2.2, r * 2.2));
        g.setClip(clip);
        // The ring's near half, in front of it.
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (r * 0.05)));
        alpha(g, 0.65f);
        g.draw(new java.awt.geom.Arc2D.Double(cx - r * 1.7, cy - r * 0.32, r * 3.4, r * 0.64, 180, 180,
                java.awt.geom.Arc2D.OPEN));
        // A small moon.
        double m = r * 0.18;
        g.setColor(palette.get(1).brighter());
        alpha(g, 0.95f);
        g.fill(new Ellipse2D.Double(cx + r * 1.4, cy - r * 1.1, m, m));
        alpha(g, 1f);
    }

    private static void rocket(Graphics2D g, Random random, int w, int h, Color accent) {
        stars(g, random, w, h, 140);
        double angle = Math.toRadians(-20 - random.nextInt(25));
        double length = h * 0.38;
        double width = length * 0.24;
        java.awt.geom.AffineTransform saved = g.getTransform();
        g.translate(w * (0.45 + random.nextDouble() * 0.15), h * 0.55);
        g.rotate(angle + Math.PI / 2);
        // The exhaust trail: widening, fading cones behind the tail.
        for (int i = 0; i < 6; i++) {
            Path2D.Double plume = new Path2D.Double();
            double spread = width * (0.3 + i * 0.35);
            double reach = length * (0.4 + i * 0.45);
            plume.moveTo(-width * 0.3, length / 2);
            plume.lineTo(width * 0.3, length / 2);
            plume.lineTo(spread, length / 2 + reach);
            plume.lineTo(-spread, length / 2 + reach);
            plume.closePath();
            g.setColor(i < 2 ? accent : Color.WHITE);
            alpha(g, i < 2 ? 0.7f - i * 0.2f : 0.12f - i * 0.015f);
            g.fill(plume);
        }
        // Fins, body, nose, window.
        g.setColor(accent.darker());
        alpha(g, 1f);
        Path2D.Double fins = new Path2D.Double();
        fins.moveTo(-width * 0.5, length * 0.2);
        fins.lineTo(-width, length * 0.55);
        fins.lineTo(width, length * 0.55);
        fins.lineTo(width * 0.5, length * 0.2);
        fins.closePath();
        g.fill(fins);
        g.setColor(new Color(0xEE, 0xF1, 0xF5));
        g.fill(new RoundRectangle2D.Double(-width / 2, -length * 0.3, width, length * 0.8, width * 0.6, width * 0.6));
        Path2D.Double nose = new Path2D.Double();
        nose.moveTo(-width / 2, -length * 0.25);
        nose.quadTo(0, -length * 0.75, width / 2, -length * 0.25);
        nose.closePath();
        g.fill(nose);
        g.setColor(accent);
        g.fill(new Ellipse2D.Double(-width * 0.22, -length * 0.15, width * 0.44, width * 0.44));
        g.setTransform(saved);
        alpha(g, 1f);
    }

    private static void station(Graphics2D g, Random random, int w, int h, Color accent) {
        stars(g, random, w, h, 150);
        double cx = w * 0.5;
        double cy = h * (0.45 + random.nextDouble() * 0.1);
        double span = w * 0.7;
        // The truss.
        g.setColor(Color.WHITE);
        alpha(g, 0.7f);
        g.fill(new java.awt.geom.Rectangle2D.Double(cx - span / 2, cy - h * 0.012, span, h * 0.024));
        // Solar wings at both ends, a grid of cells each.
        for (int side = -1; side <= 1; side += 2) {
            double x0 = cx + side * span / 2 - (side < 0 ? 0 : w * 0.16);
            for (int wing = -1; wing <= 1; wing += 2) {
                double y0 = cy + (wing < 0 ? -h * 0.32 : h * 0.03);
                g.setColor(accent.darker());
                alpha(g, 0.9f);
                g.fill(new java.awt.geom.Rectangle2D.Double(x0, y0, w * 0.16, h * 0.29));
                g.setColor(accent);
                alpha(g, 0.6f);
                for (int c = 1; c < 4; c++) {
                    g.fill(new java.awt.geom.Rectangle2D.Double(x0 + c * w * 0.04, y0, Math.max(1, w * 0.002), h * 0.29));
                }
            }
        }
        // Modules along the middle.
        int modules = 3 + random.nextInt(3);
        for (int i = 0; i < modules; i++) {
            double mw = w * (0.05 + random.nextDouble() * 0.04);
            double mx = cx - span * 0.25 + i * span * 0.5 / Math.max(1, modules - 1) - mw / 2;
            g.setColor(new Color(0xDD, 0xE3, 0xEA));
            alpha(g, 1f);
            g.fill(new RoundRectangle2D.Double(mx, cy - h * 0.06, mw, h * 0.12, h * 0.05, h * 0.05));
        }
        alpha(g, 1f);
    }

    private static void nebula(Graphics2D g, Random random, int w, int h, List<Color> palette, Color accent) {
        Color[] tints = {accent, palette.get(1).brighter(), Color.WHITE};
        for (int i = 0; i < 45; i++) {
            double r = h * (0.12 + random.nextDouble() * 0.4);
            double x = w * (0.5 + random.nextGaussian() * 0.22);
            double y = h * (0.5 + random.nextGaussian() * 0.2);
            glow(g, x, y, r, tints[i % tints.length], 0.08f + random.nextFloat() * 0.14f);
        }
        stars(g, random, w, h, 180);
    }

    private static void moonscape(Graphics2D g, Random random, int w, int h, List<Color> palette, Color accent) {
        stars(g, random, w, h, 120);
        // A world rising: a disc half above the horizon.
        double r = h * 0.22;
        double cx = w * (0.6 + random.nextDouble() * 0.2);
        double cy = h * 0.38;
        glow(g, cx, cy, r * 1.4, accent, 0.3f);
        g.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), accent.brighter(),
                (float) (cx + r), (float) (cy + r), accent.darker().darker()));
        alpha(g, 1f);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        // The ground: a gentle curve, then craters on it.
        Path2D.Double ground = new Path2D.Double();
        ground.moveTo(0, h);
        ground.lineTo(0, h * 0.62);
        ground.quadTo(w * 0.5, h * 0.52, w, h * 0.64);
        ground.lineTo(w, h);
        ground.closePath();
        g.setPaint(new GradientPaint(0, (float) (h * 0.55), palette.get(1).brighter(), 0, h, palette.get(0)));
        alpha(g, 1f);
        g.fill(ground);
        for (int i = 0; i < 14; i++) {
            double cw = w * (0.03 + random.nextDouble() * 0.09);
            double x = random.nextDouble() * w;
            double y = h * (0.66 + random.nextDouble() * 0.3);
            g.setColor(Color.BLACK);
            alpha(g, 0.25f);
            g.fill(new Ellipse2D.Double(x, y, cw, cw * 0.3));
            g.setColor(Color.WHITE);
            alpha(g, 0.12f);
            g.fill(new Ellipse2D.Double(x + cw * 0.08, y - cw * 0.04, cw * 0.84, cw * 0.18));
        }
        alpha(g, 1f);
    }

    private static void asteroids(Graphics2D g, Random random, int w, int h, List<Color> palette, Color accent) {
        stars(g, random, w, h, 160);
        for (int i = 0; i < 11; i++) {
            double r = h * (0.03 + Math.pow(random.nextDouble(), 2) * 0.16);
            double cx = random.nextDouble() * w;
            double cy = random.nextDouble() * h;
            Path2D.Double rock = new Path2D.Double();
            int corners = 7 + random.nextInt(5);
            for (int c = 0; c < corners; c++) {
                double a = Math.PI * 2 * c / corners;
                double d = r * (0.7 + random.nextDouble() * 0.45);
                if (c == 0) {
                    rock.moveTo(cx + Math.cos(a) * d, cy + Math.sin(a) * d);
                } else {
                    rock.lineTo(cx + Math.cos(a) * d, cy + Math.sin(a) * d);
                }
            }
            rock.closePath();
            Color lit = i % 4 == 0 ? accent.darker() : palette.get(1).brighter().brighter();
            g.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), lit,
                    (float) (cx + r * 0.6), (float) (cy + r * 0.6), palette.get(0)));
            alpha(g, 1f);
            g.fill(rock);
        }
        alpha(g, 1f);
    }
}
