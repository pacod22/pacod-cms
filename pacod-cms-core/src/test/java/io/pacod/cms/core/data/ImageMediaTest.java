package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** OriginalLanguageExtension makes bg the original: the chain is requested, en, bg. */
class ImageMediaTest {

    @Test
    @DisplayName("the alt text follows the reader's language chain, then the caption, then nothing")
    void altFor() {
        ImageMedia image = new ImageMedia(1);
        image.setCaption("Надпис");
        assertThat(image.altFor("de")).isEqualTo("Надпис");

        image.setAlts(List.of(new TextItem("Мост", "bg"), new TextItem("A bridge", "en")));
        assertThat(image.altFor("de")).isEqualTo("A bridge");
        assertThat(image.altFor("bg")).isEqualTo("Мост");
        image.setAlts(List.of(new TextItem("Eine Brücke", "de")));
        assertThat(image.altFor("de")).isEqualTo("Eine Brücke");
        assertThat(image.altFor("fr")).isEqualTo("Надпис");

        image.setCaption(null);
        assertThat(image.altFor("fr")).isEmpty();
    }
}
