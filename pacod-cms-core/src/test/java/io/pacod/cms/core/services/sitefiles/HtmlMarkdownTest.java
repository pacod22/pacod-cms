package io.pacod.cms.core.services.sitefiles;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlMarkdownTest {

    private static final String ORIGIN = "https://demo.example.com";

    private static String md(String html) {
        return HtmlMarkdown.convert(html, ORIGIN);
    }

    @Test
    void blankIsEmpty() {
        assertThat(md(null)).isEmpty();
        assertThat(md("  ")).isEmpty();
    }

    @Test
    void headingsParagraphsAndEmphasis() {
        assertThat(md("<h2>Заглавие</h2><p>Текст с <strong>удебелен</strong> и <em>наклонен</em> шрифт.</p>"))
                .isEqualTo("## Заглавие\n\nТекст с **удебелен** и *наклонен* шрифт.");
    }

    @Test
    void linksAreAbsoluteAndOnlyFollowable() {
        assertThat(md("<p><a href=\"/p/about\">За нас</a>, <a href=\"https://x.test/a b\">x</a>, "
                + "<a href=\"javascript:alert(1)\">зло</a>, <a href=\"//evil.test/\">друго</a></p>"))
                .isEqualTo("[За нас](https://demo.example.com/p/about), [x](https://x.test/a%20b), зло, друго");
    }

    @Test
    void codeSamplesBecomeFencesInEitherShape() {
        assertThat(md("<pre class=\"language-java\"><code>int a = 1;\n  <b>x</b></code></pre>"))
                .isEqualTo("```java\nint a = 1;\n  x\n```");
        assertThat(md("<pre><code class=\"language-yaml\">a: 1</code></pre>")).isEqualTo("```yaml\na: 1\n```");
        assertThat(md("<pre>```\nnested\n```</pre>")).isEqualTo("````\n```\nnested\n```\n````");
    }

    @Test
    void scriptsStylesFramesAndCommentsAreDropped() {
        assertThat(md("<p>Едно</p><script>alert(1)</script><style>p{}</style>"
                + "<!-- [if mso]><p>скрито</p><![endif] --><iframe src=\"https://v.test\"></iframe><p>Две</p>"))
                .isEqualTo("Едно\n\nДве");
    }

    @Test
    void listsNestAndQuotesArePrefixed() {
        assertThat(md("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>")).isEqualTo("- a\n\n  - b\n- c");
        assertThat(md("<ol><li>едно</li><li>две</li></ol>")).isEqualTo("1. едно\n2. две");
        assertThat(md("<blockquote><p>цитат</p><p>втори</p></blockquote>")).isEqualTo("> цитат\n>\n> втори");
    }

    @Test
    void tablesAndLegacyMarkup() {
        assertThat(md("<table><tr><th>A</th><th>B|C</th></tr><tr><td>1</td></tr></table>"))
                .isEqualTo("| A | B\\|C |\n| --- | --- |\n| 1 |  |");
        assertThat(md("<font face=\"Arial\"><span>стар</span> текст<o:p></o:p></font>")).isEqualTo("стар текст");
    }

    @Test
    void picturesAreTheirAltText() {
        assertThat(md("<figure><img src=\"/assets/img/a.jpg\" alt=\"Луна\"><figcaption>Надпис</figcaption></figure>"))
                .isEqualTo("Луна\n\nНадпис");
    }
}
