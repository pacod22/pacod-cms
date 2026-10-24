package io.pacod.cms.studio.ai;

import io.pacod.cms.core.design.BodyBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body's styled blocks are drawn twice: by the site ({@code site.css}, every template set and
 * the print views) and by the studio's editor ({@code body-blocks.css}). A block missing from
 * either is one the AI may write and a reader -- or the operator previewing it -- sees unstyled.
 */
class BodyBlocksCssTest {

    /** The site's sheet, read from its module: the studio's classpath does not carry it. */
    private static final Path SITE_CSS = Path.of("../pacod-cms-site/src/main/resources/static/site-assets/site.css");

    @Test
    @DisplayName("every block is styled by the site and by the studio's editor")
    void everyBlockIsStyledTwice() throws IOException {
        String site = Files.readString(SITE_CSS);
        String studio;
        try (InputStream in = getClass().getResourceAsStream("/static/mgmt-assets/body-blocks.css")) {
            assertThat(in).as("body-blocks.css on the studio's classpath").isNotNull();
            studio = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (BodyBlocks block : BodyBlocks.values()) {
            assertThat(site).as("site.css styles " + block.cssClass()).contains(".article-body ." + block.cssClass());
            assertThat(studio).as("body-blocks.css styles " + block.cssClass())
                    .contains(".mce-content-body ." + block.cssClass(), ".ai-preview ." + block.cssClass());
        }
    }
}
