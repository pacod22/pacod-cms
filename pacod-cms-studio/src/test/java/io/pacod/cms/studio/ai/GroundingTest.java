package io.pacod.cms.studio.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingTest {

    private static List<String> unverified(String language, String material, String... answer) {
        return Grounding.unverified(language, List.of(material), answer);
    }

    @Test
    @DisplayName("a number is the material's however its groups are separated, padded or reordered")
    void numbers() {
        String material = "<p>Tickets cost 1,200 leva; doors open at 09:00 on 2026-05-12. Ще има 1 500 места.</p>";
        assertThat(unverified("en", material, "<ul><li>Price: 1200 leva</li><li>Doors: 9:00</li><li>Date: 12.05.2026</li>"
                + "<li>Seats: 1500</li><li>Also: 1 200 and 1.200</li></ul>")).isEmpty();
        assertThat(unverified("en", material, "<ul><li>Since: 1998</li><li>Stalls: 300</li></ul>"))
                .containsExactly("1998", "300");
    }

    @Test
    @DisplayName("a capitalised word inside a sentence is a name, and must be the material's")
    void names() {
        String material = "<p>The fair opens today in the old town. Parking is free.</p>";
        assertThat(unverified("en", material, "<h2>Getting there</h2><p>The fair opens today in the old town, says "
                + "Mayor Petrov. Parking is free.</p><ul><li>Bring a hat</li></ul>")).containsExactly("Mayor", "Petrov");
        assertThat(unverified("en", "<p>The mayor opens the Fair.</p>", "<p>Ask the Mayor at the fair.</p>")).isEmpty();
    }

    @Test
    @DisplayName("a heading in title case, or a word the answer also writes in lower case, says nothing about a name")
    void titleCaseAndLowerCase() {
        String material = "<p>Entry is free.</p>";
        assertThat(unverified("en", material, "<h3>Key Facts</h3><h2>Getting There Early</h2><ul><li>Free Parking</li></ul>"
                + "<p>The parking is free.</p>")).isEmpty();
        assertThat(unverified("en", material, "<h3>Key facts</h3><p>Entry is free in Plovdiv.</p>")).containsExactly("Plovdiv");
    }

    @Test
    @DisplayName("a language that capitalises every noun has its names left alone, never its numbers")
    void german() {
        String material = "<p>Der Markt öffnet heute.</p>";
        String answer = "<h2>Anreise und Parken</h2><p>Der Markt öffnet heute, sagt Bürgermeister Huber, um 18 Uhr.</p>";
        assertThat(unverified("de", material, answer)).containsExactly("18");
        assertThat(unverified("en", material, answer)).containsExactly("Bürgermeister", "Huber", "18", "Uhr");
    }

    @Test
    @DisplayName("an inflected or derived form of the material's word is the material's")
    void inflection() {
        assertThat(unverified("ru", "<p>Москва принимает фестиваль.</p>",
                "<p>Москва принимает фестиваль. Гости в Москве ждут, и в Петербурге тоже.</p>")).containsExactly("Петербурге");
        assertThat(unverified("en", "<p>The fair is in Bulgaria; Ivan Petrov opens it.</p>",
                "<p>A Bulgarian fair, opened by Mr Petrova and Ivo.</p>")).containsExactly("Mr", "Ivo");
    }

    @Test
    @DisplayName("material in another script than the answer cannot vouch for a name, so only numbers are checked")
    void otherScript() {
        assertThat(Grounding.unverified("bg", List.of("<p>A fair in Plovdiv on 12 May</p>", ""),
                "<p>Панаирът в Пловдив започва на 12 май. Откри го кметът Иванов в 1998.</p>")).containsExactly("1998");
    }

    @Test
    @DisplayName("placeholders in square brackets are the author's to fill in, never listed")
    void placeholders() {
        assertThat(unverified("en", "<p>A fair opens</p>",
                "<p>The fair opens on [date, e.g. 12 May]. The [2026 budget] Committee is set.</p>")).containsExactly("Committee");
    }

    @Test
    @DisplayName("an image's alt text and a link's title are published, so they are checked too")
    void attributes() {
        assertThat(unverified("en", "<p><img src=\"/a.jpg\"> The gate.</p>",
                "<figure class=\"body-figure\"><img src=\"/a.jpg\" alt=\"Mayor Petrov at the gate\">"
                        + "<figcaption>The gate.</figcaption></figure>")).containsExactly("Petrov");
    }

    @Test
    @DisplayName("a pull quote repeats the material or the answer word for word, or it is listed by its opening words")
    void pullQuotes() {
        String material = "<p>The fair opens today. Parking is free for everyone.</p>";
        assertThat(unverified("en", material, "<p>The fair opens today. Parking is free for everyone.</p>"
                + "<blockquote class=\"body-pullquote\">Parking is free for everyone.</blockquote>")).isEmpty();
        assertThat(unverified("en", material, "<p>The fair opens today. Parking is free for everyone.</p>"
                + "<blockquote class=\"body-pullquote\">Parking will always be free for every visitor, all year</blockquote>"))
                .containsExactly("“Parking will always be free for every visitor,…”");
        assertThat(unverified("en", "<p>A fair</p>", "<p>Stalls line the square from dawn.</p>"
                + "<blockquote class=\"body-pullquote\">Stalls line the square</blockquote>")).isEmpty();
    }

    @Test
    @DisplayName("listed in order of appearance, each once, at most twelve")
    void orderAndCap() {
        String numbers = IntStream.rangeClosed(1, 15).mapToObj(i -> String.valueOf(1_000 + i)).collect(Collectors.joining(" and "));
        List<String> listed = unverified("en", "<p>Nothing</p>", "<p>In 1998 and 1998 again.</p><p>" + numbers + "</p>");
        assertThat(listed).hasSize(Grounding.MAX_LISTED).startsWith("1998", "1001", "1002");
    }
}
