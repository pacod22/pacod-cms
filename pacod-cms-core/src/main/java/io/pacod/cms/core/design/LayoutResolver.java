package io.pacod.cms.core.design;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a stored layout renders as on a template set -- and what it cannot render there.
 *
 * <p>Nothing is dropped from the stored layout; what the set cannot render is left out of the
 * result and reported as an {@link Issue}: a region the set lacks, a section type it does not
 * implement, settings that fail their type's fields. The site renders the result; the studio shows
 * the issues, and they are the report a template switch shows before it is applied.
 *
 * <p>A page with content of its own always shows it: a layout without a {@code primary} section
 * gets one at the end of the set's first region (and an issue saying so); a second one is not
 * rendered.
 */
public final class LayoutResolver {

    private LayoutResolver() {
    }

    public record ResolvedSection(String id, SectionType type, Map<String, Object> settings) {

        public boolean primary() {
            return type == SectionType.PRIMARY;
        }
    }

    /**
     * Something stored that does not render on this set.
     *
     * @param code {@code region-missing}, {@code type-unknown}, {@code type-unsupported},
     *             {@code settings-invalid}, {@code primary-not-allowed}, {@code primary-duplicate} or
     *             {@code primary-missing}
     */
    public record Issue(String region, String sectionId, String type, String code, List<String> details) {
    }

    public record Resolved(Map<String, List<ResolvedSection>> regions, List<Issue> issues) {

        public List<ResolvedSection> region(String name) {
            return regions.getOrDefault(name, List.of());
        }
    }

    /**
     * @param stored the website's regions for the page type, or null for the set's default
     */
    public static Resolved resolve(TemplateSetManifest set, PageType pageType, Map<String, List<Section>> stored) {
        TemplateSetManifest.PageTypeSpec spec = set.spec(pageType);
        Map<String, List<Section>> source = stored != null ? stored : spec.defaultLayout();
        Map<String, List<ResolvedSection>> out = new LinkedHashMap<>();
        for (String region : spec.regions()) {
            out.put(region, new ArrayList<>());
        }
        List<Issue> issues = new ArrayList<>();
        boolean primarySeen = false;
        for (Map.Entry<String, List<Section>> region : source.entrySet()) {
            List<ResolvedSection> target = out.get(region.getKey());
            for (Section section : region.getValue() == null ? List.<Section>of() : region.getValue()) {
                if (section == null) {
                    continue;
                }
                Optional<SectionType> type = SectionType.byId(section.getType());
                if (target == null) {
                    issues.add(new Issue(region.getKey(), section.getId(), section.getType(), "region-missing", List.of()));
                    continue;
                }
                if (type.isEmpty()) {
                    issues.add(new Issue(region.getKey(), section.getId(), section.getType(), "type-unknown", List.of()));
                    continue;
                }
                if (!set.renders(type.get())) {
                    issues.add(new Issue(region.getKey(), section.getId(), section.getType(), "type-unsupported", List.of()));
                    continue;
                }
                if (type.get() == SectionType.PRIMARY) {
                    if (!pageType.hasPrimary()) {
                        issues.add(new Issue(region.getKey(), section.getId(), section.getType(), "primary-not-allowed", List.of()));
                        continue;
                    }
                    if (primarySeen) {
                        issues.add(new Issue(region.getKey(), section.getId(), section.getType(), "primary-duplicate", List.of()));
                        continue;
                    }
                    primarySeen = true;
                }
                SectionSettings.Result settings = SectionSettings.check(type.get(), section.getSettings());
                if (!settings.ok()) {
                    issues.add(new Issue(region.getKey(), section.getId(), section.getType(), "settings-invalid",
                            settings.errors()));
                    continue;
                }
                target.add(new ResolvedSection(section.getId(), type.get(), settings.settings()));
            }
        }
        if (pageType.hasPrimary() && !primarySeen) {
            String first = spec.regions().getFirst();
            out.get(first).add(new ResolvedSection("primary", SectionType.PRIMARY, Map.of()));
            issues.add(new Issue(first, null, SectionType.PRIMARY.id(), "primary-missing", List.of()));
        }
        Map<String, List<ResolvedSection>> frozen = new LinkedHashMap<>();
        out.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new Resolved(frozen, List.copyOf(issues));
    }
}
