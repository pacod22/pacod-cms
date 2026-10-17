package io.pacod.cms.core.design;

import io.pacod.cms.core.identity.authz.Yaml12;
import io.pacod.cms.core.tenant.Tenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The template sets this installation has: every {@code design/sets/<id>.yaml} on the classpath,
 * read once and checked hard -- a manifest naming an unknown section type, a page type without
 * regions or a default layout its own set cannot render fails the start, not a reader's request.
 */
@Slf4j
@Component
public class TemplateSetCatalog {

    public static final String LOCATION = "classpath*:design/sets/*.yaml";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,39}");
    private static final Pattern REGION = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private final Map<String, TemplateSetManifest> sets;

    public TemplateSetCatalog() {
        this(load());
    }

    TemplateSetCatalog(Map<String, TemplateSetManifest> sets) {
        this.sets = Map.copyOf(sets);
        if (!this.sets.containsKey(Tenant.DEFAULT_TEMPLATE_SET)) {
            throw new IllegalStateException("No manifest for the default template set " + Tenant.DEFAULT_TEMPLATE_SET);
        }
        for (TemplateSetManifest set : this.sets.values()) {
            chain(set.id());
        }
        log.info("Template set manifests: {}", new TreeMap<>(this.sets).keySet());
    }

    /**
     * Where a set's templates are looked up, in order: the set, then what it extends, and so on --
     * never including the shared {@code _base}, which comes last for every set.
     */
    public List<String> chain(String id) {
        List<String> chain = new ArrayList<>();
        String next = id;
        while (next != null) {
            if (chain.contains(next)) {
                throw new IllegalStateException("Template set " + id + " extends itself through " + chain);
            }
            TemplateSetManifest set = sets.get(next);
            if (set == null) {
                throw new IllegalStateException("Template set " + id + " extends " + next + ", which is not installed");
            }
            chain.add(next);
            next = set.parent();
        }
        return List.copyOf(chain);
    }

    public List<TemplateSetManifest> all() {
        return new TreeMap<>(sets).values().stream().toList();
    }

    public Optional<TemplateSetManifest> find(String id) {
        return Optional.ofNullable(id == null ? null : sets.get(id));
    }

    /** The set {@code id}, or the default when the installation does not have it. */
    public TemplateSetManifest orDefault(String id) {
        return find(id).orElseGet(() -> sets.get(Tenant.DEFAULT_TEMPLATE_SET));
    }

    private static Map<String, TemplateSetManifest> load() {
        Map<String, TemplateSetManifest> sets = new LinkedHashMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String file = r.getFilename();
                String id = file == null ? "" : file.replaceFirst("\\.yaml$", "");
                TemplateSetManifest manifest = parse(id, r.getContentAsString(StandardCharsets.UTF_8));
                sets.put(manifest.id(), manifest);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sets;
    }

    /** Reads one manifest; {@code id} is its file's name, which the manifest must repeat. */
    @SuppressWarnings("unchecked")
    public static TemplateSetManifest parse(String id, String yaml) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalStateException("Not a template set id: " + id);
        }
        Map<String, Object> root = (Map<String, Object>) Yaml12.load(yaml);
        if (!id.equals(root.get("id"))) {
            throw new IllegalStateException("Template set " + id + ": the manifest says id " + root.get("id"));
        }
        Set<SectionType> sections = EnumSet.noneOf(SectionType.class);
        for (Object s : (List<Object>) root.getOrDefault("sections", List.of())) {
            sections.add(SectionType.byId(String.valueOf(s)).orElseThrow(() ->
                    new IllegalStateException("Template set " + id + ": unknown section type " + s)));
        }
        if (!sections.contains(SectionType.PRIMARY)) {
            throw new IllegalStateException("Template set " + id + " must render the primary section");
        }
        Map<String, Object> types = (Map<String, Object>) root.getOrDefault("pageTypes", Map.of());
        Map<PageType, TemplateSetManifest.PageTypeSpec> specs = new EnumMap<>(PageType.class);
        for (PageType pageType : PageType.values()) {
            Map<String, Object> spec = (Map<String, Object>) types.get(pageType.name());
            if (spec == null) {
                throw new IllegalStateException("Template set " + id + " has no page type " + pageType);
            }
            List<String> regions = new ArrayList<>();
            for (Object r : (List<Object>) spec.getOrDefault("regions", List.of())) {
                String region = String.valueOf(r);
                if (!REGION.matcher(region).matches() || regions.contains(region)) {
                    throw new IllegalStateException("Template set " + id + ", " + pageType + ": bad region " + region);
                }
                regions.add(region);
            }
            if (regions.isEmpty()) {
                throw new IllegalStateException("Template set " + id + ", " + pageType + ": no regions");
            }
            Map<String, List<Section>> layout = new LinkedHashMap<>();
            Map<String, Object> declared = (Map<String, Object>) spec.getOrDefault("layout", Map.of());
            int n = 0;
            for (Map.Entry<String, Object> region : declared.entrySet()) {
                List<Section> list = new ArrayList<>();
                for (Object o : (List<Object>) region.getValue()) {
                    Map<String, Object> s = (Map<String, Object>) o;
                    // Fixed ids, so the default layout is the same document every time it is read.
                    list.add(new Section("d" + pageType.name().toLowerCase() + n++, String.valueOf(s.get("type")),
                            (Map<String, Object>) s.getOrDefault("settings", Map.of())));
                }
                layout.put(region.getKey(), list);
            }
            TemplateSetManifest.PageTypeSpec pageSpec = new TemplateSetManifest.PageTypeSpec(List.copyOf(regions), layout);
            specs.put(pageType, pageSpec);
        }
        Map<String, TemplateSetManifest.ThemePreset> themes = new LinkedHashMap<>();
        Map<String, Object> declaredThemes = (Map<String, Object>) root.getOrDefault("themes", Map.of());
        for (Map.Entry<String, Object> preset : declaredThemes.entrySet()) {
            Map<String, Object> p = (Map<String, Object>) preset.getValue();
            Map<String, String> raw = new LinkedHashMap<>();
            ((Map<String, Object>) p.getOrDefault("tokens", Map.of())).forEach((k, v) -> raw.put(k, String.valueOf(v)));
            Map<String, String> clean = ThemeCss.clean(raw);
            if (!clean.keySet().equals(raw.keySet())) {
                throw new IllegalStateException("Template set " + id + ", theme " + preset.getKey()
                        + ": not a theme value " + raw.keySet().stream().filter(k -> !clean.containsKey(k)).toList());
            }
            themes.put(preset.getKey(), new TemplateSetManifest.ThemePreset(preset.getKey(),
                    String.valueOf(p.getOrDefault("name", preset.getKey())), clean));
        }
        Object parent = root.get("extends");
        if (parent != null && (!ID.matcher(String.valueOf(parent)).matches() || id.equals(parent))) {
            throw new IllegalStateException("Template set " + id + ": cannot extend " + parent);
        }
        TemplateSetManifest manifest = new TemplateSetManifest(id, String.valueOf(root.getOrDefault("name", id)),
                String.valueOf(root.getOrDefault("description", "")), parent == null ? null : String.valueOf(parent),
                sections, specs, themes);
        // A set's own defaults must render in full on that set.
        for (PageType pageType : PageType.values()) {
            LayoutResolver.Resolved resolved = LayoutResolver.resolve(manifest, pageType, null);
            if (!resolved.issues().isEmpty()) {
                throw new IllegalStateException("Template set " + id + ", " + pageType + ": its default layout "
                        + resolved.issues());
            }
        }
        return manifest;
    }
}
