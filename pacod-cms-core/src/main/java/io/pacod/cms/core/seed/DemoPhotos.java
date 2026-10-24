package io.pacod.cms.core.seed;

import io.pacod.cms.core.services.ImageService;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The demo content's photographs, fitted to the website's upload rules when they are written.
 *
 * <p>A demo that ships photographs (the space demo: NASA's, see {@code demo/space/photos/VENDORED.md})
 * ships them at one size, 3:2 and at most 1500 pixels wide. A website's rules are its own
 * ({@code app.image.*}): a minimum size whose proportions every picture must share, and a byte
 * limit. The picture is stored through {@link ImageService#save} like an upload, so it has to meet
 * them -- and it has to meet them on EVERY installation, or a demo install fails halfway and fails
 * the same way on every rerun, the input being the same file each time.
 *
 * <ol>
 *   <li>Cropped to the rule's proportions around a focus point (the centre unless the seed says),
 *       then kept at its own size when that meets the minimum, enlarged to the minimum when not.
 *       With the dimension check off the picture is kept as shipped.</li>
 *   <li>Encoded at a stepped-down quality until it fits the byte limit; when even the lowest of
 *       those does not, made smaller -- never below the minimum -- and tried again.</li>
 *   <li>At the minimum, {@link DemoImages#jpeg}'s last resort: quality down to 0.3, else refused.</li>
 * </ol>
 */
public final class DemoPhotos {

    /** The qualities tried at each size, best first. */
    private static final float[] QUALITIES = {0.88f, 0.8f, 0.72f, 0.64f};

    /** How much smaller each next size is. */
    private static final double SHRINK = 0.85;

    private DemoPhotos() {
    }

    /** A shipped photograph's bytes, from the classpath. */
    public static byte[] read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The demo photograph " + resource + " is missing: " + e.getMessage(), e);
        }
    }

    /**
     * The photograph as a JPEG that meets the rules.
     *
     * @param minWidth       the website's minimum width; with {@code minHeight}, the shape
     * @param dimensionCheck whether the size rule applies at all
     * @param maxBytes       the byte limit, zero or less for none
     * @param focusX         the crop's centre across, 0..1
     * @param focusY         the crop's centre down, 0..1
     * @throws IllegalStateException when even the smallest size and lowest quality do not fit
     */
    public static byte[] fit(byte[] photo, int minWidth, int minHeight, boolean dimensionCheck, long maxBytes,
                             double focusX, double focusY) {
        BufferedImage image = rgb(decode(photo));
        if (dimensionCheck) {
            image = cropToShape(image, minWidth, minHeight, focusX, focusY);
            if (image.getWidth() < minWidth) {
                image = scale(image, minWidth, minHeight);
            }
        }
        int floorWidth = dimensionCheck ? minWidth : Math.min(image.getWidth(), 600);
        BufferedImage current = image;
        while (true) {
            for (float quality : QUALITIES) {
                byte[] bytes = DemoImages.encode(current, quality);
                if (maxBytes <= 0 || bytes.length <= maxBytes) {
                    return bytes;
                }
            }
            int next = (int) Math.floor(current.getWidth() * SHRINK);
            if (current.getWidth() <= floorWidth) {
                return DemoImages.jpeg(current, maxBytes);
            }
            int width = Math.max(next, floorWidth);
            int height = dimensionCheck ? heightFor(width, minWidth, minHeight)
                    : (int) Math.round((double) current.getHeight() * width / current.getWidth());
            current = scale(image, width, height);
        }
    }

    /** {@link #fit} with the crop centred. */
    public static byte[] fit(byte[] photo, int minWidth, int minHeight, boolean dimensionCheck, long maxBytes) {
        return fit(photo, minWidth, minHeight, dimensionCheck, maxBytes, 0.5, 0.5);
    }

    /**
     * A frame of exactly {@code width} by {@code height} cut out of the photograph -- the largest of
     * that shape, around the focus -- and scaled to it. For a website's share image (1200x630).
     */
    public static BufferedImage frame(byte[] photo, int width, int height, double focusX, double focusY) {
        BufferedImage image = cropToShape(rgb(decode(photo)), width, height, focusX, focusY);
        return scale(image, width, height);
    }

    /** The height a picture {@code width} wide must have to be in the rule's proportions. */
    static int heightFor(int width, int minWidth, int minHeight) {
        int height = (int) Math.round((double) width * minHeight / minWidth);
        return ImageService.fitsShape(width, height, minWidth, minHeight) ? height : height + 1;
    }

    private static BufferedImage decode(byte[] photo) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(photo));
            if (image == null) {
                throw new IllegalStateException("A demo photograph is not a readable image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage rgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The largest frame of the shape {@code shapeWidth:shapeHeight}, centred on the focus. */
    private static BufferedImage cropToShape(BufferedImage image, int shapeWidth, int shapeHeight,
                                             double focusX, double focusY) {
        int w = image.getWidth();
        int h = image.getHeight();
        int cropWidth = w;
        int cropHeight = heightFor(w, shapeWidth, shapeHeight);
        if (cropHeight > h) {
            cropHeight = h;
            cropWidth = (int) Math.floor((double) h * shapeWidth / shapeHeight);
            while (!ImageService.fitsShape(cropWidth, cropHeight, shapeWidth, shapeHeight) && cropWidth > 1) {
                cropWidth--;
            }
        }
        if (cropWidth == w && cropHeight == h) {
            return image;
        }
        int x = clamp((int) Math.round(focusX * w - cropWidth / 2.0), 0, w - cropWidth);
        int y = clamp((int) Math.round(focusY * h - cropHeight / 2.0), 0, h - cropHeight);
        return image.getSubimage(x, y, cropWidth, cropHeight);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Halves with bilinear while more than twice too large, then one bicubic step: no shimmer. */
    private static BufferedImage scale(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return draw(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage draw(BufferedImage image, int width, int height, Object interpolation) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
