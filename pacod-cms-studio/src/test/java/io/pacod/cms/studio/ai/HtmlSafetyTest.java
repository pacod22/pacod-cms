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

    @Test
    @DisplayName("a body keeps the blocks on their own elements and loses every other class, style and handler")
    void bodyBlocks() {
        String cleaned = HtmlSafety.body("<p>x</p>", """
                <p class="body-lead lead">Lead</p>
                <div class="body-callout" style="color:red" onclick="x()"><p>Note</p></div>
                <blockquote class="body-pullquote">Quote</blockquote>
                <p class="body-callout">Wrong element</p>
                <div class="body-facts"><h3>Facts</h3><ul><li>one</li></ul></div>
                <div class="hero"><p>Unknown block</p></div>
                <div><p>Bare</p></div>""");
        assertThat(cleaned).contains("<p class=\"body-lead\">Lead</p>", "<div class=\"body-callout\"><p>Note</p></div>",
                        "<blockquote class=\"body-pullquote\">Quote</blockquote>", "<p>Wrong element</p>",
                        "<div class=\"body-facts\"><h3>Facts</h3>", "<p>Unknown block</p>", "<p>Bare</p>")
                .doesNotContain("style", "onclick", "hero", "class=\"lead", "<div><p>", "<div>");
    }

    @Test
    @DisplayName("a figure keeps its side and caption, and still only the source's images")
    void bodyFigure() {
        String source = "<p><img src=\"/assets/img/articles/a.jpg\"></p>";
        String cleaned = HtmlSafety.body(source, "<figure class=\"body-figure body-figure-left\">"
                + "<img src=\"/assets/img/articles/a.jpg\" alt=\"A\"><figcaption>Caption</figcaption></figure>"
                + "<figure class=\"body-figure\"><img src=\"https://tracker.example/p.gif\"></figure>");
        assertThat(cleaned).contains("<figure class=\"body-figure body-figure-left\"><img src=\"/assets/img/articles/a.jpg\"",
                "<figcaption>Caption</figcaption>").doesNotContain("tracker");
    }

    @Test
    @DisplayName("a body links only to addresses the source links or writes out; any other link keeps its text alone")
    void bodyLinks() {
        String source = "<p>See <a href=\"/article/3\">this</a>, https://fair.example/tickets. or write to info@fair.example.</p>";
        String cleaned = HtmlSafety.body(source, "<p><a href=\"/article/3\">this</a> <a href=\"https://fair.example/tickets/\">tickets</a> "
                + "<a href=\"mailto:INFO@fair.example?subject=Hi\">mail</a> <a href=\"https://evil.example/\">elsewhere</a></p>");
        assertThat(cleaned).contains("<a href=\"/article/3\">this</a>", "<a href=\"https://fair.example/tickets/\">tickets</a>",
                "<a href=\"mailto:INFO@fair.example?subject=Hi\">mail</a>", "</a> elsewhere</p>").doesNotContain("evil");
        assertThat(HtmlSafety.body("", "<p><a href=\"https://fair.example/\">site</a></p>")).isEqualTo("<p>site</p>");
    }

    @Test
    @DisplayName("a body keeps a code sample's language class on its pre or code, and no other class there")
    void bodyCodeSamples() {
        String cleaned = HtmlSafety.body("<p>x</p>", """
                <pre class="language-rust"><code>fn main() { println!("&lt;hi&gt;"); }</code></pre>
                <pre><code class="language-bash token">make verify</code></pre>
                <pre class="language-Rust language-x;y hljs"><code>a</code></pre>
                <pre class="language-%s"><code>b</code></pre>
                <p class="language-java">Not code</p>
                <p>Inline <code class="language-sql" style="color:red">select 1</code></p>""".formatted("a".repeat(25)));
        assertThat(cleaned).contains("<pre class=\"language-rust\"><code>fn main() { println!(\"&lt;hi&gt;\"); }</code></pre>",
                        "<pre><code class=\"language-bash\">make verify</code></pre>", "<pre><code>a</code></pre>",
                        "<pre><code>b</code></pre>", "<p>Not code</p>", "<code class=\"language-sql\">select 1</code>")
                .doesNotContain("token", "hljs", "Rust", "x;y", "style", "<p class");
    }

    @Test
    @DisplayName("the plain allow-list still takes no blocks")
    void cleanTakesNoBlocks() {
        assertThat(HtmlSafety.clean("", "<div class=\"body-callout\"><p>x</p></div>")).isEqualTo("<p>x</p>");
        assertThat(HtmlSafety.clean("", "<pre class=\"language-java\"><code>x</code></pre>")).isEqualTo("<pre><code>x</code></pre>");
    }
}
