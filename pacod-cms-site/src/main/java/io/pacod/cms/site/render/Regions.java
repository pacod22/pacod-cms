package io.pacod.cms.site.render;

import io.pacod.cms.core.design.SectionType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A page's regions, as {@code ${regions}} in its template: {@code ${regions.get('main')}} is the
 * main region's sections in order, empty for a region the layout leaves empty or the set lacks.
 */
public final class Regions {

    private final Map<String, List<RenderedSection>> regions;

    public Regions(Map<String, List<RenderedSection>> regions) {
        Map<String, List<RenderedSection>> copy = new LinkedHashMap<>();
        regions.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.regions = Map.copyOf(copy);
    }

    public List<RenderedSection> get(String region) {
        return regions.getOrDefault(region, List.of());
    }

    public boolean isEmpty(String region) {
        return get(region).isEmpty();
    }

    /** The page's own content alone, in {@code main}: what a page renders with no layout at all. */
    public static Regions primaryOnly() {
        return new Regions(Map.of("main", List.of(new RenderedSection("primary", SectionType.PRIMARY.id(),
                SectionType.PRIMARY.fragment(), Map.of(), null))));
    }
}
