package io.pacod.cms.core.seed;

import io.pacod.cms.core.services.ImageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A shipped photograph fitted to a website's rules: whatever the minimum, the shape and the byte
 * limit, what comes out is what {@code ImageService.validate} accepts -- or a refusal, never a
 * picture that fails the upload it is about to go through.
 */
class DemoPhotosTest {

    /** A real one, 1500x1000: a starfield photograph compresses unlike a flat test card. */
    private static final byte[] PHOTO = DemoPhotos.read("demo/space/photos/lantern-reef.jpg");

    private static BufferedImage read(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    @DisplayName("under the default rule a shipped photograph keeps its size")
    void defaultRuleKeepsTheSize() throws IOException {
        byte[] out = DemoPhotos.fit(PHOTO, 600, 400, true, 400 * 1024);
        BufferedImage image = read(out);
        assertThat(image.getWidth()).isEqualTo(1500);
        assertThat(image.getHeight()).isEqualTo(1000);
        assertThat(out.length).isLessThanOrEqualTo(400 * 1024);
    }

    @Test
    @DisplayName("a minimum above the shipped size: enlarged to the minimum")
    void largerMinimumEnlarges() throws IOException {
        BufferedImage image = read(DemoPhotos.fit(PHOTO, 1800, 1200, true, 0));
        assertThat(image.getWidth()).isEqualTo(1800);
        assertThat(image.getHeight()).isEqualTo(1200);
    }

    @Test
    @DisplayName("another shape: cropped to it, within the pixel the rule allows")
    void otherShapeIsCropped() throws IOException {
        BufferedImage image = read(DemoPhotos.fit(PHOTO, 800, 600, true, 0));
        assertThat(ImageService.fitsDimensions(image.getWidth(), image.getHeight(), 800, 600))
                .as("%dx%d", image.getWidth(), image.getHeight()).isTrue();
        assertThat(image.getHeight()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a small byte limit: made smaller until it fits, never below the minimum")
    void smallLimitShrinks() throws IOException {
        byte[] out = DemoPhotos.fit(PHOTO, 600, 400, true, 60 * 1024);
        BufferedImage image = read(out);
        assertThat(out.length).isLessThanOrEqualTo(60 * 1024);
        assertThat(ImageService.fitsDimensions(image.getWidth(), image.getHeight(), 600, 400))
                .as("%dx%d", image.getWidth(), image.getHeight()).isTrue();
        assertThat(image.getWidth()).isLessThan(1500);
    }

    @Test
    @DisplayName("a limit nothing fits is refused, not stored broken")
    void impossibleLimitIsRefused() {
        assertThatThrownBy(() -> DemoPhotos.fit(PHOTO, 600, 400, true, 2_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("with the dimension check off the photograph is kept as shipped")
    void checkOffKeepsTheShippedPicture() throws IOException {
        BufferedImage image = read(DemoPhotos.fit(PHOTO, 800, 600, false, 0));
        assertThat(image.getWidth()).isEqualTo(1500);
        assertThat(image.getHeight()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a frame of an exact size, for a website's share picture")
    void frameIsExact() {
        BufferedImage share = DemoPhotos.frame(PHOTO, 1200, 630, 0.5, 0.5);
        assertThat(share.getWidth()).isEqualTo(1200);
        assertThat(share.getHeight()).isEqualTo(630);
    }

    @Test
    @DisplayName("padded, a portrait picture is shown whole in the rule's shape, on its background")
    void padKeepsTheWholePicture() throws IOException {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(FolderSeed.screenshot(), "png", png);
        BufferedImage image = read(DemoPhotos.pad(png.toByteArray(), 600, 400, true, 400 * 1024, DemoPhotos.colour("#000000")));
        assertThat(image.getWidth()).isEqualTo(900);
        assertThat(image.getHeight()).isEqualTo(600);
        assertThat(new java.awt.Color(image.getRGB(5, 300)).getRed()).as("the padding").isLessThan(40);
        assertThat(new java.awt.Color(image.getRGB(450, 2)).getRed()).as("the picture's own frame").isGreaterThan(200);
        // A wide one is padded above and below instead, and one already in shape is left as it is.
        BufferedImage wide = read(DemoPhotos.pad(PHOTO, 600, 600, true, 0, DemoPhotos.colour("#ffffff")));
        assertThat(wide.getWidth()).isEqualTo(wide.getHeight()).isEqualTo(1500);
        assertThat(read(DemoPhotos.pad(PHOTO, 600, 400, true, 0, java.awt.Color.WHITE)).getWidth()).isEqualTo(1500);
        assertThatThrownBy(() -> DemoPhotos.colour("green")).isInstanceOf(IllegalArgumentException.class);
    }
}
