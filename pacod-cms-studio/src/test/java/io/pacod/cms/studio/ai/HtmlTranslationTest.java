package io.pacod.cms.studio.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTranslationTest {

    /** A body in older markup: tables, <font>, a <style>, Word markup, a YouTube frame. */
    private static final String LEGACY = """
            <style>.x{color:red}</style><p class="MsoNormal"><font face="Arial" size="3">Добро утро</font></p>
            <table border="1"><tr><td>Едно</td><td><b>Две</b></td></tr></table>
            <p><img src="/assets/img/articles/a.jpg" alt="Мост" width="600"></p>
            <div class="video-container"><iframe src="https://www.youtube.com/embed/abc" allowfullscreen></iframe></div>
            <p><a href="/article/12?lang=bg" title="Още">Още</a></p>""";

    private static final String LEGACY_DE = """
            <style>.x{color:red}</style><p class="MsoNormal"><font face="Arial" size="3">Guten Morgen</font></p>
            <table border="1"><tr><td>Eins</td><td><b>Zwei</b></td></tr></table>
            <p><img src="/assets/img/articles/a.jpg" alt="Brücke" width="600"></p>
            <div class="video-container"><iframe src="https://www.youtube.com/embed/abc" allowfullscreen></iframe></div>
            <p><a href="/article/12?lang=bg" title="Mehr">Mehr</a></p>""";

    @Test
    @DisplayName("a body in older markup translates with everything kept: table, <font>, <style>, the frame; alt and title translated")
    void legacyBodyKeepsItsSkeleton() {
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE)).hasValueSatisfying(html -> assertThat(html)
                .contains("<font face=\"Arial\" size=\"3\">Guten Morgen</font>", "<table border=\"1\">",
                        "<style>.x{color:red}</style>", "src=\"https://www.youtube.com/embed/abc\"", "alt=\"Brücke\""));
    }

    @Test
    @DisplayName("anything the model added, removed or redirected is refused")
    void additionsRefused() {
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE + "<script>alert(1)</script>")).isEmpty();
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE.replace("<a href", "<a onclick=\"x()\" href"))).isEmpty();
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE.replace("/article/12?lang=bg", "https://evil.example/"))).isEmpty();
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE.replace("youtube.com/embed/abc", "evil.example/x"))).isEmpty();
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE.replace(".x{color:red}", ".x{background:url(//evil)}"))).isEmpty();
        assertThat(HtmlTranslation.html(LEGACY, LEGACY_DE.replace("<b>Zwei</b>", "Zwei"))).isEmpty();
        assertThat(HtmlTranslation.html("<p>a</p>", "<p>b</p><img src=x onerror=alert(1)>")).isEmpty();
    }

    @Test
    @DisplayName("markup in the text is text: an escaped tag stays escaped, never becomes an element")
    void escapedMarkupStaysText() {
        assertThat(HtmlTranslation.html("<p>Use &lt;b&gt; for bold</p>", "<p>Nutze &lt;b&gt; für Fett</p>"))
                .contains("<p>Nutze &lt;b&gt; für Fett</p>");
    }

    /** A body with a code sample, as the editor's codesample plugin stores it, and code within a sentence. */
    private static final String CODE = """
            <p>Пуснете проверката:</p>
            <pre class="language-bash"><code># проверка преди всяко предаване
            make verify &amp;&amp; echo "готово"</code></pre>
            <p>или само <code>make test</code>.</p>""";

    @Test
    @DisplayName("the code is kept exactly while the words around it are translated")
    void codeIsKept() {
        String translated = CODE.replace("Пуснете проверката:", "Run the check:").replace("или само", "or just");
        assertThat(HtmlTranslation.html(CODE, translated)).hasValueSatisfying(html -> assertThat(html)
                .contains("<p>Run the check:</p>", "<pre class=\"language-bash\"><code># проверка преди всяко предаване\n"
                        + "make verify &amp;&amp; echo \"готово\"</code></pre>", "<p>or just <code>make test</code>.</p>"));
        // The same text, spelled with other entities, is the same code.
        assertThat(HtmlTranslation.html(CODE, translated.replace("&amp;&amp;", "&#38;&#x26;"))).isPresent();
    }

    @Test
    @DisplayName("an answer that changes, reflows or reorders code is refused, in a sample or within a sentence")
    void changedCodeRefused() {
        assertThat(HtmlTranslation.html(CODE, CODE.replace("# проверка", "# check"))).isEmpty();
        assertThat(HtmlTranslation.html(CODE, CODE.replace("\"готово\"", "\"done\""))).isEmpty();
        assertThat(HtmlTranslation.html(CODE, CODE.replace("make test", "make Test"))).isEmpty();
        assertThat(HtmlTranslation.html(CODE, CODE.replace("предаване\nmake", "предаване make"))).isEmpty();
        assertThat(HtmlTranslation.html(CODE, CODE.replace("make verify", "make  verify"))).isEmpty();
        assertThat(HtmlTranslation.html("<pre><code>a</code></pre><pre><code>b</code></pre>",
                "<pre><code>b</code></pre><pre><code>a</code></pre>")).isEmpty();
    }

    @Test
    @DisplayName("a title that is text stays text, unescaped; one that brings an element is refused")
    void textFields() {
        assertThat(HtmlTranslation.field("Tom & Jerry", "Tom &amp; Jerry auf Deutsch")).contains("Tom & Jerry auf Deutsch");
        assertThat(HtmlTranslation.field("Plain", "<img src=x onerror=alert(1)>")).isEmpty();
        assertThat(HtmlTranslation.field("Zwei<br>Zeilen", "Two<br>lines")).contains("Two<br>lines");
        assertThat(HtmlTranslation.field("$100 off", "$100 Rabatt")).contains("$100 Rabatt");
    }
}
