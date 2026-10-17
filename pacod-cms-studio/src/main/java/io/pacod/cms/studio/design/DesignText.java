package io.pacod.cms.studio.design;

import io.pacod.cms.core.design.LayoutResolver;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.studio.i18n.MgmtText;

/**
 * The design screens' words, as {@code ${labels…}} in their templates: a section type, a field, a
 * choice, a region, an issue -- each from the bundles ({@code design.*}), falling back to the name
 * itself for what the bundles cannot know (a region a set invented, a type no longer known).
 */
public final class DesignText {

    private final MgmtText text;

    public DesignText(MgmtText text) {
        this.text = text;
    }

    private String or(String key, String fallback, Object... args) {
        String value = text.msg(key, args);
        return value == null || value.equals(key) ? fallback : value;
    }

    public String section(String typeId) {
        return or("design.section." + typeId, typeId);
    }

    public String section(SectionType type) {
        return section(type.id());
    }

    public String field(String name) {
        return or("design.field." + name, name);
    }

    public String choice(String field, Object value) {
        return or("design.choice." + field + "." + value, String.valueOf(value));
    }

    public String region(String name) {
        return or("design.region." + name, name);
    }

    public String issue(LayoutResolver.Issue issue) {
        return or("design.issue." + issue.code(), issue.code());
    }

    public String token(String id) {
        return or("design.token." + id, id);
    }

    /** A menu item's label in {@code lang}; with no language, its first. Null when it has none. */
    public String menuLabel(io.pacod.cms.core.data.MenuItem item, String lang) {
        if (item == null || item.getLabels() == null || item.getLabels().isEmpty()) {
            return null;
        }
        return lang == null ? item.getLabels().getFirst().getText()
                : io.pacod.cms.core.data.TextItems.find(item.getLabels(), lang);
    }

    public String font(String id) {
        return or("design.font." + id, id);
    }
}
