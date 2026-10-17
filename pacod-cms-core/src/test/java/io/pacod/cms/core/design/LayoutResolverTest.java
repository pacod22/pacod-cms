package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutResolverTest {

    private static final TemplateSetManifest EDITORIAL = new TemplateSetCatalog().orDefault("editorial");

    private static final TemplateSetManifest NARROW = TemplateSetCatalog.parse("narrow", """
            id: narrow
            sections: [primary, article-list]
            pageTypes:
              HOME: {regions: [main]}
              ARTICLE: {regions: [main], layout: {main: [{type: primary}]}}
              CATEGORY: {regions: [main], layout: {main: [{type: primary}]}}
              LATEST: {regions: [main], layout: {main: [{type: primary}]}}
              PAGE: {regions: [main], layout: {main: [{type: primary}]}}
              SEARCH: {regions: [main], layout: {main: [{type: primary}]}}
            """);

    private static Map<String, List<Section>> layout(Object... regionsAndSections) {
        Map<String, List<Section>> m = new LinkedHashMap<>();
        for (int i = 0; i < regionsAndSections.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<Section> sections = (List<Section>) regionsAndSections[i + 1];
            m.put((String) regionsAndSections[i], sections);
        }
        return m;
    }

    @Test
    @DisplayName("switching to a narrower set reports, and never drops, what it cannot render")
    void switchReportsWhatIsLeftOut() {
        Map<String, List<Section>> stored = layout(
                "main", List.of(new Section("p", "primary", Map.of()), new Section("t", "rich-text", Map.of("html", "<p>x</p>"))),
                "aside", List.of(new Section("l", "article-list", Map.of())));

        LayoutResolver.Resolved resolved = LayoutResolver.resolve(NARROW, PageType.ARTICLE, stored);

        assertThat(resolved.region("main")).extracting(LayoutResolver.ResolvedSection::id).containsExactly("p");
        assertThat(resolved.issues()).extracting(LayoutResolver.Issue::code)
                .containsExactlyInAnyOrder("type-unsupported", "region-missing");
        // The stored layout itself is untouched: switching back renders all three.
        assertThat(stored.get("main")).hasSize(2);
        assertThat(LayoutResolver.resolve(EDITORIAL, PageType.ARTICLE, stored).issues()).isEmpty();
    }

    @Test
    @DisplayName("a page's own content always shows: a missing primary is added, a second one is not rendered")
    void primaryIsAlwaysThereOnce() {
        LayoutResolver.Resolved missing = LayoutResolver.resolve(EDITORIAL, PageType.PAGE,
                layout("main", List.of(new Section("t", "rich-text", Map.of("html", "<p>x</p>")))));
        assertThat(missing.region("main")).extracting(LayoutResolver.ResolvedSection::primary).containsExactly(false, true);
        assertThat(missing.issues()).extracting(LayoutResolver.Issue::code).containsExactly("primary-missing");

        LayoutResolver.Resolved twice = LayoutResolver.resolve(EDITORIAL, PageType.PAGE, layout(
                "main", List.of(new Section("a", "primary", Map.of())), "aside", List.of(new Section("b", "primary", Map.of()))));
        assertThat(twice.region("aside")).isEmpty();
        assertThat(twice.issues()).extracting(LayoutResolver.Issue::code).containsExactly("primary-duplicate");

        LayoutResolver.Resolved home = LayoutResolver.resolve(EDITORIAL, PageType.HOME,
                layout("main", List.of(new Section("a", "primary", Map.of()))));
        assertThat(home.region("main")).isEmpty();
        assertThat(home.issues()).extracting(LayoutResolver.Issue::code).containsExactly("primary-not-allowed");
    }

    @Test
    @DisplayName("stored settings are checked on reading too: a section whose settings fail is not rendered")
    void settingsCheckedOnRead() {
        LayoutResolver.Resolved resolved = LayoutResolver.resolve(EDITORIAL, PageType.HOME, layout("main", List.of(
                new Section("ok", "article-list", Map.of("count", 2)),
                new Section("bad", "article-list", Map.of("count", 500)),
                new Section("what", "carousel", Map.of()))));
        assertThat(resolved.region("main")).extracting(LayoutResolver.ResolvedSection::id).containsExactly("ok");
        assertThat(resolved.issues()).extracting(LayoutResolver.Issue::code).containsExactly("settings-invalid", "type-unknown");
        assertThat(resolved.issues().getFirst().details()).containsExactly("range:count");
    }
}
