package io.pacod.cms.studio.design;

import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.LayoutResolver;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.SectionSettings;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.TemplateSetManifest;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioPrincipals;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A website's layouts, as the studio edits them: every change goes to the draft, and readers see
 * it only once published. The draft starts as a copy of what is published, or of the template
 * set's default, whose section ids are fixed -- the editor addresses sections by id, and showing it
 * must not save anything.
 *
 * <p>Nothing here drops what the current set cannot render: such sections stay in the draft,
 * flagged by {@link LayoutResolver}, and render again on a set that can.
 */
@Service
public class DesignLayouts {

    private final LayoutRepository layouts;
    private final TemplateSetCatalog catalog;

    public DesignLayouts(LayoutRepository layouts, TemplateSetCatalog catalog) {
        this.layouts = layouts;
        this.catalog = catalog;
    }

    /** The set the bound website renders with. */
    public TemplateSetManifest currentSet() {
        return catalog.orDefault(TenantContext.find().map(Tenant::templateSet).orElse(null));
    }

    /** A page type on the overview: whether the website designed it, whether a draft waits. */
    public record Status(PageType pageType, boolean custom, boolean unpublished, int issues) {
    }

    public List<Status> overview() {
        TemplateSetManifest set = currentSet();
        List<Status> out = new ArrayList<>();
        for (PageType type : PageType.values()) {
            Optional<Layout> doc = layouts.findById(type.name());
            boolean custom = doc.map(d -> d.getPublished() != null).orElse(false);
            boolean unpublished = doc.map(Layout::hasUnpublishedChanges).orElse(false);
            int issues = LayoutResolver.resolve(set, type, draftOf(type)).issues().size();
            out.add(new Status(type, custom, unpublished, issues));
        }
        return out;
    }

    /** One section on the editor: as stored, with what the resolver said about it. */
    public record SectionView(Section section, Optional<SectionType> type, List<LayoutResolver.Issue> issues) {

        public boolean rendered() {
            return issues.isEmpty();
        }

        public boolean primary() {
            return type.map(t -> t == SectionType.PRIMARY).orElse(false);
        }
    }

    /** A region on the editor; {@code known} false for one the current set does not have. */
    public record RegionView(String name, boolean known, List<SectionView> sections) {
    }

    public record Editor(PageType pageType, TemplateSetManifest set, List<RegionView> regions,
                         List<LayoutResolver.Issue> issues, boolean unpublished, boolean custom,
                         List<SectionType> addable) {
    }

    public Editor editor(PageType type) {
        TemplateSetManifest set = currentSet();
        Map<String, List<Section>> draft = draftOf(type);
        LayoutResolver.Resolved resolved = LayoutResolver.resolve(set, type, draft);
        List<RegionView> regions = new ArrayList<>();
        List<String> names = new ArrayList<>(set.spec(type).regions());
        draft.keySet().stream().filter(n -> !names.contains(n)).forEach(names::add);
        for (String name : names) {
            List<SectionView> sections = new ArrayList<>();
            for (Section s : draft.getOrDefault(name, List.of())) {
                List<LayoutResolver.Issue> issues = resolved.issues().stream()
                        .filter(i -> s.getId() != null && s.getId().equals(i.sectionId())).toList();
                sections.add(new SectionView(s, SectionType.byId(s.getType()), issues));
            }
            regions.add(new RegionView(name, set.spec(type).regions().contains(name), sections));
        }
        Optional<Layout> doc = layouts.findById(type.name());
        List<SectionType> addable = set.sections().stream().filter(t -> t != SectionType.PRIMARY)
                .sorted().toList();
        return new Editor(type, set, regions, resolved.issues(), doc.map(Layout::hasUnpublishedChanges).orElse(false),
                doc.map(d -> d.getPublished() != null).orElse(false), addable);
    }

    /** The draft: as saved, else what is published, else the set's default -- a copy, never the stored one. */
    public Map<String, List<Section>> draftOf(PageType type) {
        Optional<Layout> doc = layouts.findById(type.name());
        if (doc.isPresent() && doc.get().getDraft() != null) {
            return Layout.copy(doc.get().getDraft());
        }
        if (doc.isPresent() && doc.get().getPublished() != null) {
            return Layout.copy(doc.get().getPublished());
        }
        // The default's own ids, which are fixed: the editor addresses sections by id, and a GET
        // must not save anything to give them stable ones.
        return Layout.copy(currentSet().spec(type).defaultLayout());
    }

    public Section add(PageType type, String region, String typeId) {
        SectionType sectionType = SectionType.byId(typeId).filter(t -> t != SectionType.PRIMARY)
                .orElseThrow(() -> new MgmtOperationException("design.error.type", typeId));
        if (!currentSet().renders(sectionType)) {
            throw new MgmtOperationException("design.error.typeUnsupported", typeId);
        }
        if (!currentSet().spec(type).regions().contains(region)) {
            throw new MgmtOperationException("design.error.region", region);
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        sectionType.fields().forEach(f -> {
            if (f.defaultValue() != null) {
                defaults.put(f.name(), f.defaultValue());
            }
        });
        Section section = Section.of(sectionType, defaults);
        mutate(type, draft -> draft.computeIfAbsent(region, r -> new ArrayList<>()).add(section));
        return section;
    }

    /**
     * New settings for a section, from the form's values.
     *
     * @throws MgmtOperationException {@code design.error.settings}, naming the refused fields
     */
    public void update(PageType type, String sectionId, Map<String, String> values) {
        mutate(type, draft -> {
            Section section = find(draft, sectionId);
            SectionType sectionType = SectionType.byId(section.getType())
                    .orElseThrow(() -> new MgmtOperationException("design.error.type", section.getType()));
            SectionSettings.Result result = SectionSettings.check(sectionType, values);
            if (!result.ok()) {
                throw new SettingsRefused(result.errors());
            }
            section.setSettings(new LinkedHashMap<>(result.settings()));
        });
    }

    /** Refused settings, with the fields that failed, for the screen to mark. */
    public static final class SettingsRefused extends MgmtOperationException {
        private final List<String> errors;

        public SettingsRefused(List<String> errors) {
            super("design.error.settings", String.join(", ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    /** One place up ({@code -1}) or down ({@code +1}) in its region. */
    public void move(PageType type, String sectionId, int delta) {
        mutate(type, draft -> {
            for (List<Section> list : draft.values()) {
                for (int i = 0; i < list.size(); i++) {
                    if (sectionId.equals(list.get(i).getId())) {
                        int j = i + Integer.signum(delta);
                        if (j >= 0 && j < list.size()) {
                            Section s = list.remove(i);
                            list.add(j, s);
                        }
                        return;
                    }
                }
            }
            throw new MgmtOperationException("design.error.section");
        });
    }

    /** To the end of another region of the current set. */
    public void moveTo(PageType type, String sectionId, String region) {
        if (!currentSet().spec(type).regions().contains(region)) {
            throw new MgmtOperationException("design.error.region", region);
        }
        mutate(type, draft -> {
            Section section = find(draft, sectionId);
            draft.values().forEach(list -> list.removeIf(s -> sectionId.equals(s.getId())));
            draft.computeIfAbsent(region, r -> new ArrayList<>()).add(section);
        });
    }

    /**
     * A new order, from the editor's drag and drop: each region's section ids, in order. Every
     * section of the draft must appear exactly once, or nothing changes -- a stale page must not
     * lose sections it did not know about.
     */
    public void arrange(PageType type, Map<String, List<String>> order) {
        mutate(type, draft -> {
            Map<String, Section> byId = new LinkedHashMap<>();
            draft.values().forEach(list -> list.forEach(s -> byId.put(s.getId(), s)));
            List<String> all = order.values().stream().flatMap(List::stream).toList();
            if (all.size() != byId.size() || !byId.keySet().containsAll(all) || all.stream().distinct().count() != all.size()) {
                throw new MgmtOperationException("design.error.stale");
            }
            for (String region : order.keySet()) {
                if (!draft.containsKey(region) && !currentSet().spec(type).regions().contains(region)) {
                    throw new MgmtOperationException("design.error.region", region);
                }
            }
            Map<String, List<Section>> arranged = new LinkedHashMap<>();
            draft.keySet().forEach(r -> arranged.put(r, new ArrayList<>()));
            order.forEach((region, ids) -> arranged.put(region, new ArrayList<>(ids.stream().map(byId::get).toList())));
            draft.clear();
            draft.putAll(arranged);
        });
    }

    public void remove(PageType type, String sectionId) {
        mutate(type, draft -> {
            Section section = find(draft, sectionId);
            if (SectionType.PRIMARY.id().equals(section.getType())) {
                // The page's own content: the resolver would put it back anyway.
                throw new MgmtOperationException("design.error.primary");
            }
            draft.values().forEach(list -> list.removeIf(s -> sectionId.equals(s.getId())));
        });
    }

    /** The draft becomes the set's default again (published only once published). */
    public void reset(PageType type) {
        mutate(type, draft -> {
            draft.clear();
            draft.putAll(Layout.copy(currentSet().spec(type).defaultLayout()));
        });
    }

    /** Forgets the draft: the editor shows what is published again. */
    public void discard(PageType type) {
        layouts.findById(type.name()).ifPresent(doc -> {
            doc.setDraft(null);
            stamp(doc);
            layouts.save(doc);
        });
    }

    /** What the draft is becomes what readers see -- within a minute, as the site's caches turn over. */
    public void publish(PageType type) {
        Layout doc = layouts.findById(type.name()).orElseGet(() -> new Layout(type));
        Map<String, List<Section>> draft = draftOf(type);
        doc.setPublished(Layout.copy(draft));
        doc.setDraft(Layout.copy(draft));
        doc.setPublishedOn(Instant.now());
        doc.setPublishedBy(StudioPrincipals.currentSubject());
        stamp(doc);
        layouts.save(doc);
    }

    /**
     * The whole draft at once, for design assist: sections already checked against the set and
     * their settings. A page with content of its own keeps its {@code primary} section -- the one
     * the draft had, id and all -- first in the region it was in, else in the first.
     */
    public void replaceDraft(PageType type, Map<String, List<Section>> regions) {
        mutate(type, draft -> {
            Section primary = null;
            String primaryRegion = null;
            for (Map.Entry<String, List<Section>> region : draft.entrySet()) {
                for (Section section : region.getValue()) {
                    if (SectionType.PRIMARY.id().equals(section.getType())) {
                        primary = section;
                        primaryRegion = region.getKey();
                    }
                }
            }
            draft.clear();
            regions.forEach((region, sections) -> draft.put(region, new ArrayList<>(sections)));
            if (type.hasPrimary()) {
                String region = primaryRegion != null && currentSet().spec(type).regions().contains(primaryRegion)
                        ? primaryRegion : currentSet().spec(type).regions().getFirst();
                Section kept = primary != null ? primary : new Section("primary", SectionType.PRIMARY.id(), Map.of());
                draft.computeIfAbsent(region, r -> new ArrayList<>()).addFirst(kept);
            }
        });
    }

    private void mutate(PageType type, java.util.function.Consumer<Map<String, List<Section>>> change) {
        Layout doc = layouts.findById(type.name()).orElseGet(() -> new Layout(type));
        Map<String, List<Section>> draft = new LinkedHashMap<>(draftOf(type));
        draft.replaceAll((k, v) -> new ArrayList<>(v));
        change.accept(draft);
        doc.setDraft(draft);
        stamp(doc);
        layouts.save(doc);
    }

    private static void stamp(Layout doc) {
        doc.setUpdatedOn(Instant.now());
        doc.setUpdatedBy(StudioPrincipals.currentSubject());
    }

    private static Section find(Map<String, List<Section>> draft, String sectionId) {
        return draft.values().stream().flatMap(List::stream).filter(s -> sectionId != null && sectionId.equals(s.getId()))
                .findFirst().orElseThrow(() -> new MgmtOperationException("design.error.section"));
    }
}
