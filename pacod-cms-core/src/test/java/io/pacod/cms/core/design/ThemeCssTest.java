package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeCssTest {

    @Test
    @DisplayName("token values become custom properties, light on :root and dark on .night; others are dropped")
    void rendersTokens() {
        String css = ThemeCss.render(Map.of("accent", "#1D4E6F", "accent@dark", "#123", "font-display", "georgia",
                "shell", "1100", "bg", "red", "ink", "#12345", "font-text", "Comic Sans; } body { display:none",
                "gutter", "500"), null);

        assertThat(css).contains(":root {", "--accent: #1d4e6f;", "--font-display: Georgia, 'Times New Roman', serif;",
                "--shell: 1100px;", ".night {", "--accent: #123;");
        // Not a colour, not a hex of the right length, not an allowed font, out of range.
        assertThat(css).doesNotContain("--bg", "--ink", "--font-text", "Comic", "--gutter");
    }

    @Test
    @DisplayName("own CSS keeps what a stylesheet needs and loses what it never does")
    void sanitizesOwnCss() {
        String css = ThemeCss.render(Map.of(), """
                @import url("https://tracker.example/x.css");
                .masthead { color: var(--accent); }
                .x { width: expression(alert(1)); background: url(javascript:alert(1)); behavior: url(x.htc); }
                </style><script>alert(1)</script>
                """);
        assertThat(css).contains(".masthead { color: var(--accent); }")
                .doesNotContain("@import", "tracker.example", "expression(", "javascript:", "behavior:", "</");
    }

    @Test
    @DisplayName("nothing set renders nothing, and a stylesheet's version follows its content")
    void emptyAndVersion() {
        assertThat(ThemeCss.render(Map.of(), "  ")).isEmpty();
        assertThat(ThemeCss.version("a")).hasSize(12).isEqualTo(ThemeCss.version("a")).isNotEqualTo(ThemeCss.version("b"));
    }

    @Test
    @DisplayName("every preset of every installed set is a valid theme")
    void presetsAreValid() {
        TemplateSetCatalog catalog = new TemplateSetCatalog();
        assertThat(catalog.orDefault("editorial").themes()).containsKeys("classic", "harbour");
        assertThat(catalog.find("minimal").orElseThrow().themes()).containsKeys("paper", "slate");
        assertThat(catalog.chain("minimal")).containsExactly("minimal", "editorial");
        catalog.all().forEach(set -> set.themes().values().forEach(preset ->
                assertThat(ThemeCss.clean(preset.tokens())).isEqualTo(preset.tokens())));
    }
}
