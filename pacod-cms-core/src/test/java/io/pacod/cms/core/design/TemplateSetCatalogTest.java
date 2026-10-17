package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateSetCatalogTest {

    @Test
    @DisplayName("the installation's sets load, the default among them, each default layout rendering in full")
    void builtInSetsLoad() {
        TemplateSetCatalog catalog = new TemplateSetCatalog();
        TemplateSetManifest editorial = catalog.orDefault("editorial");
        assertThat(editorial.spec(PageType.HOME).regions()).containsExactly("main", "aside", "wide");
        assertThat(catalog.orDefault("no-such-set").id()).isEqualTo("editorial");
        // Default layouts name no category: a new website has none.
        editorial.pageTypes().values().forEach(spec -> spec.defaultLayout().values().forEach(sections ->
                sections.forEach(s -> assertThat(s.getSettings()).doesNotContainKey("category"))));
    }

    @Test
    @DisplayName("a manifest that would fail a reader fails the start instead")
    void badManifestsRefused() {
        String types = """
                pageTypes:
                  HOME: {regions: [main]}
                  ARTICLE: {regions: [main], layout: {main: [{type: primary}]}}
                  CATEGORY: {regions: [main], layout: {main: [{type: primary}]}}
                  LATEST: {regions: [main], layout: {main: [{type: primary}]}}
                  PAGE: {regions: [main], layout: {main: [{type: primary}]}}
                  SEARCH: {regions: [main], layout: {main: [{type: primary}]}}
                """;
        assertThatThrownBy(() -> TemplateSetCatalog.parse("x1", "id: x1\nsections: [primary, carousel]\n" + types))
                .hasMessageContaining("unknown section type");
        assertThatThrownBy(() -> TemplateSetCatalog.parse("x2", "id: other\nsections: [primary]\n" + types))
                .hasMessageContaining("says id");
        assertThatThrownBy(() -> TemplateSetCatalog.parse("x3", "id: x3\nsections: [primary]\n"
                + types.replace("HOME: {regions: [main]}", "HOME: {regions: [main], layout: {main: [{type: rich-text}]}}")))
                .hasMessageContaining("default layout");
        assertThatThrownBy(() -> TemplateSetCatalog.parse("x4", "id: x4\nsections: [primary]\npageTypes: {HOME: {regions: [main]}}"))
                .hasMessageContaining("no page type");
    }
}
