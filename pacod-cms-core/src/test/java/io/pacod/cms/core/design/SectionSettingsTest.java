package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SectionSettingsTest {

    @Test
    @DisplayName("form strings become typed values, defaults fill the gaps, unknown names are dropped")
    void cleansAndDefaults() {
        SectionSettings.Result r = SectionSettings.check(SectionType.ARTICLE_LIST,
                Map.of("source", "category", "category", "12", "count", " 4 ", "heading", "  Travel   notes ", "evil", "x"));
        assertThat(r.ok()).isTrue();
        assertThat(r.settings()).containsEntry("category", 12).containsEntry("count", 4).containsEntry("offset", 0)
                .containsEntry("style", "cards").containsEntry("heading", "Travel notes").doesNotContainKey("evil");
    }

    @Test
    @DisplayName("each field refuses what does not fit it, naming the field")
    void refusesWhatDoesNotFit() {
        assertThat(SectionSettings.check(SectionType.ARTICLE_LIST, Map.of("count", 99)).errors()).containsExactly("range:count");
        assertThat(SectionSettings.check(SectionType.ARTICLE_LIST, Map.of("count", "many")).errors()).containsExactly("number:count");
        assertThat(SectionSettings.check(SectionType.ARTICLE_LIST, Map.of("style", "carousel")).errors()).containsExactly("choice:style");
        assertThat(SectionSettings.check(SectionType.ARTICLE_LIST, Map.of("source", "category")).errors())
                .containsExactly("required:category");
        assertThat(SectionSettings.check(SectionType.RICH_TEXT, Map.of()).errors()).containsExactly("required:html");
        assertThat(SectionSettings.check(SectionType.CALL_TO_ACTION,
                Map.of("heading", "x".repeat(121), "label", "Go", "url", "/p/a")).errors()).containsExactly("length:heading");
    }

    @Test
    @DisplayName("a link is http(s), or one leading slash on the website itself -- never another scheme or host")
    void urls() {
        Map<String, Object> base = Map.of("heading", "Join", "label", "Go");
        for (String ok : new String[]{"/p/contact", "https://example.com/x", "http://example.com"}) {
            assertThat(SectionSettings.check(SectionType.CALL_TO_ACTION, with(base, ok)).ok()).as(ok).isTrue();
        }
        for (String bad : new String[]{"javascript:alert(1)", "//evil.example", "/\\\\evil", "/a b", "mailto:x@y", "data:text/html,x"}) {
            assertThat(SectionSettings.check(SectionType.CALL_TO_ACTION, with(base, bad)).errors()).as(bad).containsExactly("url:url");
        }
    }

    @Test
    @DisplayName("a page section names one page by its slug, as written -- never blank, never another shape")
    void pageSlug() {
        assertThat(SectionType.byId("page")).contains(SectionType.PAGE);
        assertThat(SectionType.PAGE.fields()).extracting(SettingField::name).containsExactly("page");
        SectionSettings.Result ok = SectionSettings.check(SectionType.PAGE, Map.of("page", " platform-2 ", "heading", "x"));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.settings()).containsExactly(Map.entry("page", "platform-2"));

        assertThat(SectionSettings.check(SectionType.PAGE, Map.of()).errors()).containsExactly("required:page");
        assertThat(SectionSettings.check(SectionType.PAGE, Map.of("page", "  ")).errors()).containsExactly("required:page");
        for (String bad : new String[]{"About", "-about", "p/about", "/p/about", "about us", "../terms", "über"}) {
            assertThat(SectionSettings.check(SectionType.PAGE, Map.of("page", bad)).errors()).as(bad).containsExactly("slug:page");
        }
        assertThat(SectionSettings.check(SectionType.PAGE, Map.of("page", "a".repeat(65))).errors()).containsExactly("length:page");
    }

    private static Map<String, Object> with(Map<String, Object> base, String url) {
        java.util.Map<String, Object> m = new java.util.HashMap<>(base);
        m.put("url", url);
        return m;
    }
}
