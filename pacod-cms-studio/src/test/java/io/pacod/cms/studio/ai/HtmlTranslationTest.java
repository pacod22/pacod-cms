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

    @Test
    @DisplayName("a title that is text stays text, unescaped; one that brings an element is refused")
    void textFields() {
        assertThat(HtmlTranslation.field("Tom & Jerry", "Tom &amp; Jerry auf Deutsch")).contains("Tom & Jerry auf Deutsch");
        assertThat(HtmlTranslation.field("Plain", "<img src=x onerror=alert(1)>")).isEmpty();
        assertThat(HtmlTranslation.field("Zwei<br>Zeilen", "Two<br>lines")).contains("Two<br>lines");
        assertThat(HtmlTranslation.field("$100 off", "$100 Rabatt")).contains("$100 Rabatt");
    }
}
