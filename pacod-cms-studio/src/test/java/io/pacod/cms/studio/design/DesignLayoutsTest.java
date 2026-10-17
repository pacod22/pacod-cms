package io.pacod.cms.studio.design;

import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The layout editor's rules, over a stored document kept in memory. */
class DesignLayoutsTest {

    private final Map<String, Layout> store = new HashMap<>();
    private LayoutRepository repository;
    private DesignLayouts layouts;

    @BeforeEach
    void setUp() {
        repository = mock(LayoutRepository.class);
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        when(repository.save(any(Layout.class))).thenAnswer(i -> {
            Layout l = i.getArgument(0);
            store.put(l.getId(), l);
            return l;
        });
        layouts = new DesignLayouts(repository, new TemplateSetCatalog());
    }

    private <T> T on(String set, java.util.function.Supplier<T> work) {
        return TenantContext.callAs(new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), set), work);
    }

    private void run(String set, Runnable work) {
        TenantContext.runAs(new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), set), work);
    }

    private List<String> ids(String region) {
        return store.get("HOME").getDraft().get(region).stream().map(Section::getId).toList();
    }

    @Test
    @DisplayName("showing a page type's layout saves nothing, and its sections keep their ids from one view to the next")
    void viewingSavesNothing() throws Exception {
        DesignLayouts.Editor first = on("editorial", () -> layouts.editor(PageType.HOME));
        DesignLayouts.Editor second = on("editorial", () -> layouts.editor(PageType.HOME));
        assertThat(first.regions()).extracting(DesignLayouts.RegionView::name).containsExactly("main", "aside", "wide");
        assertThat(first.regions().getFirst().sections().getFirst().section().getId())
                .isEqualTo(second.regions().getFirst().sections().getFirst().section().getId());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("replacing the whole draft keeps the page's own content, its id and its place")
    void replaceDraftKeepsPrimary() throws Exception {
        String primaryId = on("editorial", () -> layouts.editor(PageType.ARTICLE)).regions().stream()
                .flatMap(r -> r.sections().stream()).filter(DesignLayouts.SectionView::primary)
                .findFirst().orElseThrow().section().getId();
        run("editorial", () -> layouts.replaceDraft(PageType.ARTICLE, Map.of("aside",
                List.of(Section.of(io.pacod.cms.core.design.SectionType.RICH_TEXT, Map.of("html", "<p>x</p>"))))));
        Layout doc = store.get("ARTICLE");
        assertThat(doc.getDraft().get("main")).extracting(Section::getId).containsExactly(primaryId);
        assertThat(doc.getDraft().get("aside")).extracting(Section::getType).containsExactly("rich-text");
        assertThat(doc.getPublished()).isNull();
    }

    @Test
    @DisplayName("editing goes to the draft; readers see only what is published")
    void editingIsADraft() throws Exception {
        run("editorial", () -> {
            var added = layouts.add(PageType.HOME, "aside", "rich-text");
            assertThatThrownBy(() -> layouts.update(PageType.HOME, added.getId(), Map.of()))
                    .isInstanceOf(DesignLayouts.SettingsRefused.class);
            layouts.update(PageType.HOME, added.getId(), Map.of("html", "<p>Hello</p>", "heading", " About "));
        });
        Layout doc = store.get("HOME");
        assertThat(doc.getPublished()).isNull();
        Section text = doc.getDraft().get("aside").getLast();
        assertThat(text.getSettings()).containsEntry("html", "<p>Hello</p>").containsEntry("heading", "About");

        run("editorial", () -> layouts.publish(PageType.HOME));
        assertThat(store.get("HOME").getPublished().get("aside")).extracting(Section::getType).contains("rich-text");
        assertThat(store.get("HOME").hasUnpublishedChanges()).isFalse();
    }

    @Test
    @DisplayName("moving: up and down in a region, to another region, and a full new order -- all or nothing")
    void moving() throws Exception {
        run("editorial", () -> layouts.add(PageType.HOME, "main", "call-to-action"));
        List<String> main = ids("main");
        String cta = main.getLast();
        run("editorial", () -> layouts.move(PageType.HOME, cta, -1));
        assertThat(ids("main")).containsExactly(main.get(0), cta, main.get(1));

        run("editorial", () -> layouts.moveTo(PageType.HOME, cta, "wide"));
        assertThat(ids("wide")).containsExactly(cta);

        List<String> aside = ids("aside");
        // A stale page that does not know every section is refused, and nothing moves.
        assertThatThrownBy(() -> run("editorial", () -> layouts.arrange(PageType.HOME,
                Map.of("main", List.of(main.get(0)), "aside", aside)))).isInstanceOf(MgmtOperationException.class);
        assertThat(ids("wide")).containsExactly(cta);

        run("editorial", () -> layouts.arrange(PageType.HOME,
                Map.of("main", List.of(cta, main.get(1), main.get(0)), "aside", aside, "wide", List.of())));
        assertThat(ids("main")).containsExactly(cta, main.get(1), main.get(0));
        assertThat(ids("wide")).isEmpty();
    }

    @Test
    @DisplayName("the page's own content can be moved, never removed; a type the set lacks cannot be added")
    void guards() throws Exception {
        String primary = on("editorial", () -> layouts.editor(PageType.ARTICLE).regions().getFirst().sections().getFirst().section().getId());
        assertThatThrownBy(() -> run("editorial", () -> layouts.remove(PageType.ARTICLE, primary)))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> run("minimal", () -> layouts.add(PageType.HOME, "main", "search-box")))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> run("editorial", () -> layouts.add(PageType.HOME, "nowhere", "rich-text")))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> run("editorial", () -> layouts.add(PageType.HOME, "main", "primary")))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("on a set that cannot render a section, the editor keeps and flags it; back on one that can, it renders")
    void switchingKeepsSections() throws Exception {
        run("editorial", () -> layouts.add(PageType.HOME, "aside", "search-box"));
        DesignLayouts.Editor minimal = on("minimal", () -> layouts.editor(PageType.HOME));
        DesignLayouts.RegionView aside = minimal.regions().stream().filter(r -> r.name().equals("aside")).findFirst().orElseThrow();
        assertThat(aside.known()).isFalse();
        assertThat(aside.sections()).extracting(s -> s.section().getType()).contains("search-box");
        assertThat(aside.sections()).allMatch(s -> !s.rendered());

        DesignLayouts.Editor back = on("editorial", () -> layouts.editor(PageType.HOME));
        assertThat(back.issues()).isEmpty();
    }

    @Test
    @DisplayName("reset is the set's own layout; discard forgets the draft")
    void resetAndDiscard() throws Exception {
        run("editorial", () -> {
            layouts.add(PageType.HOME, "main", "rich-text");
            layouts.reset(PageType.HOME);
        });
        assertThat(ids("main")).hasSize(2);
        run("editorial", () -> layouts.discard(PageType.HOME));
        assertThat(store.get("HOME").getDraft()).isNull();
    }
}
