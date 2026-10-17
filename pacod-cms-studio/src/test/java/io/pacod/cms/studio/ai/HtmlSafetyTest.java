package io.pacod.cms.studio.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlSafetyTest {

    @Test
    @DisplayName("structure, emphasis and safe links stay; scripts, handlers, styles, classes and bad schemes go")
    void allowList() {
        String cleaned = HtmlSafety.clean("<p>x</p>", """
                <p class="lead" style="color:red" onclick="x()">Good <b>bold</b> <a href="/article/3" onmouseover="y()">ours</a>
                <a href="https://example.com/a">theirs</a> <a href="javascript:alert(1)">bad</a> <a href="mailto:a@example.com">mail</a></p>
                <script>alert(1)</script><style>p{}</style><iframe src="https://evil.example/"></iframe>
                <ul><li>one</li></ul><form action="/x"><input name="q"></form>""");
        assertThat(cleaned).contains("<p>Good <b>bold</b>", "<a href=\"/article/3\">ours</a>", "href=\"https://example.com/a\"",
                        "href=\"mailto:a@example.com\"", "<ul><li>one</li></ul>")
                .doesNotContain("script", "style", "onclick", "onmouseover", "class=", "iframe", "javascript", "<form", "<input");
    }

    @Test
    @DisplayName("an image survives only where the selection already had it")
    void imagesFromTheSelectionOnly() {
        String source = "<p>a <img src=\"/assets/img/articles/a.jpg\" alt=\"A\"></p>";
        String cleaned = HtmlSafety.clean(source, "<p>b <img src=\"/assets/img/articles/a.jpg\" alt=\"B\"> "
                + "<img src=\"https://tracker.example/pixel.gif\"></p>");
        assertThat(cleaned).contains("src=\"/assets/img/articles/a.jpg\"").doesNotContain("tracker");
    }

    @Test
    @DisplayName("text is text: tags dropped, entities decoded")
    void text() {
        assertThat(HtmlSafety.text("<b>Tom</b> &amp; Jerry <script>x</script>")).isEqualTo("Tom & Jerry");
    }
}
