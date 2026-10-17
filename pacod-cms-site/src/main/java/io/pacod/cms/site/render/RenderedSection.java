package io.pacod.cms.site.render;

import io.pacod.cms.core.design.SectionType;

import java.util.Map;

/**
 * One section as a template renders it: everything looked up already. Fragments only read --
 * the lookups happen in {@link PageComposer}, where one failure costs one section, not the page.
 *
 * @param fragment the fragment of the set's {@code sections} template that renders it
 * @param data     what its type looked up -- a {@code LandingSlot} for an article list; null for
 *                 the others
 */
public record RenderedSection(String id, String type, String fragment, Map<String, Object> settings, Object data) {

    public boolean isPrimary() {
        return SectionType.PRIMARY.id().equals(type);
    }

    /** A setting, or null: templates read {@code ${s.setting('heading')}}. */
    public Object setting(String name) {
        return settings.get(name);
    }
}
