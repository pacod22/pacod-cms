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
 * The journal demo's pictures, drawn when a website is given them rather than shipped: abstract
 * compositions of gradients and shapes. (The space demo ships photographs instead, NASA's, with
 * their provenance beside them -- {@link DemoPhotos} fits those to a website's rules.)
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
        WAVES;

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

    /** One JPEG encoding at {@code quality}; shared with {@link DemoPhotos}. */
    static byte[] encode(BufferedImage image, float quality) {
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
}
