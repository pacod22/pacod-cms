package io.pacod.cms.core.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoImagesTest {

    private static final List<String> COLOURS = List.of("#13293d", "#1d4e6f", "#f2c14e");

    @ParameterizedTest
    @EnumSource(DemoImages.Motif.class)
    @DisplayName("every motif draws a JPEG of exactly the size asked for, under the byte limit, that ImageIO reads back")
    void exactSizeUnderTheLimit(DemoImages.Motif motif) throws IOException {
        byte[] jpeg = DemoImages.jpeg(DemoImages.draw("key-" + motif, motif, COLOURS, 600, 400), 128 * 1024);

        assertThat(jpeg.length).isPositive().isLessThanOrEqualTo(128 * 1024);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(read).isNotNull();
        assertThat(read.getWidth()).isEqualTo(600);
        assertThat(read.getHeight()).isEqualTo(400);
    }

    @Test
    @DisplayName("the same key draws the same picture; another key another")
    void deterministic() {
        byte[] once = DemoImages.jpeg(DemoImages.draw("port-alder", DemoImages.Motif.WAVES, COLOURS, 300, 200), 0);
        byte[] again = DemoImages.jpeg(DemoImages.draw("port-alder", DemoImages.Motif.WAVES, COLOURS, 300, 200), 0);
        byte[] other = DemoImages.jpeg(DemoImages.draw("night-train", DemoImages.Motif.WAVES, COLOURS, 300, 200), 0);

        assertThat(again).isEqualTo(once);
        assertThat(other).isNotEqualTo(once);
    }

    @Test
    @DisplayName("a tight limit lowers the quality until the file fits; an impossible one is refused")
    void qualityStepsDown() {
        BufferedImage picture = DemoImages.draw("tight", DemoImages.Motif.ORBIT, COLOURS, 600, 400);
        int best = DemoImages.jpeg(picture, 0).length;

        byte[] fitted = DemoImages.jpeg(picture, best - 1);
        assertThat(fitted.length).isLessThan(best);

        assertThatThrownBy(() -> DemoImages.jpeg(picture, 200))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("200 bytes");
    }

    @Test
    @DisplayName("a motif is named in lowercase in the seed; an unknown one and a single colour are refused")
    void motifNamesAndColours() {
        assertThat(DemoImages.Motif.of("peaks")).isEqualTo(DemoImages.Motif.PEAKS);
        assertThatThrownBy(() -> DemoImages.Motif.of("sparkles")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoImages.draw("k", DemoImages.Motif.GRID, List.of("#000000"), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
