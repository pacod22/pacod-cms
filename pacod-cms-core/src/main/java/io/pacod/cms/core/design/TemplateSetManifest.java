package io.pacod.cms.core.design;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a template set declares about itself ({@code design/sets/<id>.yaml} in core, beside its
 * templates under {@code templates/sets/<id>/} in the site): its regions per page type, the
 * section types it renders, and the layout a page type has until a website designs its own.
 *
 * <p>{@code parent} ({@code extends:} in the manifest) is another set whose templates this one's
 * fall back to: a set overrides what it changes and inherits the rest. Its manifest is its own --
 * regions, sections and defaults are never inherited.
 *
 * <p>In core rather than beside the templates because the studio needs it too -- to offer regions
 * and section types, and to report what a switch of sets would leave unrendered -- and the studio
 * does not carry the site's templates.
 */
public record TemplateSetManifest(String id, String name, String description, String parent,
                                  Set<SectionType> sections, Map<PageType, PageTypeSpec> pageTypes,
                                  Map<String, ThemePreset> themes) {

    /** A look the set offers: values for the theme's tokens, applied to a website's draft as a start. */
    public record ThemePreset(String id, String name, Map<String, String> tokens) {

        public ThemePreset {
            tokens = Map.copyOf(tokens);
        }
    }

    /** One page type in this set: its regions, in page order, and its default layout. */
    public record PageTypeSpec(List<String> regions, Map<String, List<Section>> defaultLayout) {
    }

    public TemplateSetManifest {
        sections = Set.copyOf(sections);
        pageTypes = Map.copyOf(pageTypes);
        themes = themes == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(themes));
    }

    public PageTypeSpec spec(PageType pageType) {
        return pageTypes.get(pageType);
    }

    public boolean renders(SectionType type) {
        return sections.contains(type);
    }
}
