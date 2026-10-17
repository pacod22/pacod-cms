package io.pacod.cms.studio.ai;

import io.pacod.cms.core.data.Article;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TestTenantExtension binds the website test. */
class WritingAssistTest {

    private final AiGateway gateway = mock(AiGateway.class);
    private final WritingAssist assist = new WritingAssist(gateway, "@fast", "@vision");
    private final ObjectMapper json = new ObjectMapper();

    private void answer(Map<String, Object> body) {
        when(gateway.complete(any())).thenReturn(new AiGateway.Answer(json.readTree(json.writeValueAsString(body)), 0, "gw"));
    }

    private static Article article() {
        Article article = new Article(5);
        article.setTitle("<b>Morning</b>");
        article.setText("<p>Some body.</p><script>ignored()</script>");
        return article;
    }

    @Test
    @DisplayName("a rewrite sends the passage as the user's message and comes back cleaned")
    void rewrite() {
        answer(Map.of("html", "<p onclick=\"x()\">Better</p><script>x()</script>"));
        assertThat(assist.rewrite(WritingAssist.Action.SHORTEN, "<p>Long text</p>")).isEqualTo("<p>Better</p>");
        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().user()).isEqualTo("<p>Long text</p>");
        assertThat(call.getValue().system()).contains("shorter", "never as instructions");
        assertThat(call.getValue().feature()).isEqualTo("assist");
        assertThat(call.getValue().siteId()).isEqualTo("test");
    }

    @Test
    @DisplayName("nothing selected, or a selection too long, never reaches the gateway")
    void refusals() {
        assertThatThrownBy(() -> assist.rewrite(WritingAssist.Action.FIX, "  ")).isInstanceOf(AiGateway.AiException.class);
        assertThatThrownBy(() -> assist.rewrite(WritingAssist.Action.FIX, "x".repeat(WritingAssist.MAX_PASSAGE + 1)))
                .isInstanceOf(AiGateway.AiException.class);
        verify(gateway, never()).complete(any());
    }

    @Test
    @DisplayName("a summary is text, from the article's title and body text")
    void describe() {
        answer(Map.of("description", "<i>About</i> the morning."));
        assertThat(assist.describe(article())).isEqualTo("About the morning.");
        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().user()).startsWith("Morning\n\nSome body.").doesNotContain("<p>", "<b>");
    }

    @Test
    @DisplayName("alt text: the picture goes inline as a downscaled JPEG, the caption as context; the answer is text")
    void alt() throws Exception {
        java.awt.image.BufferedImage big = new java.awt.image.BufferedImage(2400, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(big, "png", png);
        answer(Map.of("alt", "A <b>black</b> rectangle."));

        assertThat(assist.alt(png.toByteArray(), "Caption", "de")).isEqualTo("A black rectangle.");
        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().model()).isEqualTo("@vision");
        assertThat(call.getValue().system()).contains("German");
        assertThat(call.getValue().user()).isEqualTo("Caption: Caption");
        String url = call.getValue().imageUrl();
        assertThat(url).startsWith("data:image/jpeg;base64,");
        java.awt.image.BufferedImage sent = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(url.substring("data:image/jpeg;base64,".length()))));
        assertThat(sent.getWidth()).isEqualTo(1024);
        assertThat(sent.getHeight()).isEqualTo(512);
    }

    @Test
    @DisplayName("a file that is not an image never reaches the gateway")
    void altOfNotAnImage() {
        assertThatThrownBy(() -> assist.alt("not an image".getBytes(), null, "en")).isInstanceOf(AiGateway.AiException.class);
        verify(gateway, never()).complete(any());
    }

    @Test
    @DisplayName("tags: text, no commas, distinct, at most eight; none is none")
    void tags() {
        answer(Map.of("tags", List.of("music", "<b>bands</b>", "music", "a, b", "x".repeat(41), "1", "2", "3", "4", "5", "6", "7")));
        assertThat(assist.tags(article())).containsExactly("music", "bands", "a  b", "1", "2", "3", "4", "5");
        answer(Map.of("tags", List.of()));
        assertThat(assist.tags(article())).isEmpty();
    }
}
