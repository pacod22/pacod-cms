package io.pacod.cms.studio.ai;

import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.Theme;
import io.pacod.cms.studio.design.DesignLayouts;
import io.pacod.cms.studio.design.DesignTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TestTenantExtension binds the website test; the set is minimal, which renders no search box. */
class DesignAssistTest {

    private final AiGateway gateway = mock(AiGateway.class);
    private final DesignTheme theme = mock(DesignTheme.class);
    private final DesignLayouts layouts = mock(DesignLayouts.class);
    private final ObjectMapper json = new ObjectMapper();
    private DesignAssist assist;

    @BeforeEach
    void setUp() {
        assist = new DesignAssist(gateway, theme, layouts, "@fast");
        when(layouts.currentSet()).thenReturn(new TemplateSetCatalog().find("minimal").orElseThrow());
        when(layouts.draftOf(any())).thenReturn(Map.of());
        Theme current = new Theme();
        current.setDraftCss(".x{}");
        when(theme.load()).thenReturn(current);
    }

    private void answer(Map<String, ?> body) {
        when(gateway.complete(any())).thenReturn(new AiGateway.Answer(json.readTree(json.writeValueAsString(body)), 0, "gw"));
    }

    @Test
    @DisplayName("a theme answer: what the vocabulary takes reaches the draft, its CSS kept; the rest is named")
    void theme() {
        Map<String, Object> body = new HashMap<>();
        body.put("accent", "#1D4E6F");
        body.put("ink", "red; background:url(//evil)");
        body.put("font-display", "georgia");
        body.put("shell", 99999);
        body.put("bg", null);
        answer(body);

        DesignAssist.Outcome outcome = assist.theme("calm and bright", DesignAssist.Mode.REPLACE, new DesignAssist.Actor("usr_a", null));
        assertThat(outcome.applied()).isEqualTo(2);
        assertThat(outcome.refused()).containsExactlyInAnyOrder("ink", "shell");
        verify(theme).assistDraft(eq(Map.of("accent", "#1d4e6f", "font-display", "georgia")), eq("usr_a"), eq(null));

        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().user()).isEqualTo("calm and bright");
        assertThat(call.getValue().schema().toString()).contains("accent@dark", "georgia");
    }

    @Test
    @DisplayName("a theme answer with nothing usable changes nothing")
    void themeNothingUsable() {
        answer(Map.of("accent", "not a colour"));
        assertThatThrownBy(() -> assist.theme("x")).isInstanceOf(AiGateway.AiException.class);
        verify(theme, never()).assistDraft(any(), any(), any());
    }

    @Test
    @DisplayName("a layout answer: only types the set renders, in its regions, with valid settings; HTML cleaned")
    @SuppressWarnings("unchecked")
    void layout() {
        answer(Map.of("sections", List.of(
                Map.of("region", "main", "type", "article-list", "settings", Map.of("source", "latest", "count", 5, "style", "list")),
                Map.of("region", "main", "type", "search-box", "settings", Map.of()),
                Map.of("region", "aside", "type", "rich-text", "settings", Map.of("html", "<p>x</p>")),
                Map.of("region", "main", "type", "article-list", "settings", Map.of("source", "nowhere")),
                Map.of("region", "main", "type", "rich-text", "settings", Map.of("html", "<p onclick=\"x()\">Hello</p><script>y()</script>")))));

        DesignAssist.Outcome outcome = assist.layout(PageType.HOME, "a lead and a list", DesignAssist.Mode.REPLACE,
                new DesignAssist.Actor("usr_a", null));
        assertThat(outcome.applied()).isEqualTo(2);
        assertThat(outcome.refused()).hasSize(3);
        ArgumentCaptor<Map<String, List<Section>>> draft = ArgumentCaptor.forClass(Map.class);
        verify(layouts).assistDraft(eq(PageType.HOME), draft.capture(), eq("usr_a"), eq(null));
        assertThat(draft.getValue().get("main")).extracting(Section::getType).containsExactly("article-list", "rich-text");
        assertThat(draft.getValue().get("main").get(1).getSettings().get("html")).isEqualTo("<p>Hello</p>");

        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        // The schema offers what minimal renders, never what it does not.
        assertThat(call.getValue().schema().toString()).contains("article-list", "rich-text").doesNotContain("search-box");
    }

    @Test
    @DisplayName("an empty layout answer -- the mock's -- never wipes the draft")
    void layoutEmpty() {
        answer(Map.of("sections", List.of()));
        assertThatThrownBy(() -> assist.layout(PageType.HOME, "x")).isInstanceOf(AiGateway.AiException.class);
        verify(layouts, never()).assistDraft(any(), any(), any(), any());
    }

    @Test
    @DisplayName("no description, no call")
    void noBrief() {
        assertThatThrownBy(() -> assist.theme(" ")).isInstanceOf(AiGateway.AiException.class);
        verify(gateway, never()).complete(any());
    }

    @Test
    @DisplayName("refining a theme shows the current values, and a null keeps one")
    void themeRefine() {
        Theme current = new Theme();
        current.setDraft(new java.util.LinkedHashMap<>(Map.of("accent", "#111111", "bg", "#ffffff")));
        when(theme.load()).thenReturn(current);
        Map<String, Object> body = new HashMap<>();
        body.put("accent", "#222222");
        body.put("bg", null);
        answer(body);

        assist.theme("darker links", DesignAssist.Mode.REFINE, new DesignAssist.Actor("usr_a", "job1"));
        verify(theme).assistDraft(eq(Map.of("accent", "#222222", "bg", "#ffffff")), eq("usr_a"), eq("job1"));
        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().user()).contains("Brief: darker links", "#111111");
        assertThat(call.getValue().system()).contains("keep its current value");
        assertThat(call.getValue().userId()).isEqualTo("usr_a");
        assertThat(call.getValue().trace()).isEqualTo("job1");
    }

    @Test
    @DisplayName("refining a layout shows the current sections the set renders")
    void layoutRefine() {
        when(layouts.draftOf(PageType.HOME)).thenReturn(Map.of("main", List.of(
                Section.of(io.pacod.cms.core.design.SectionType.ARTICLE_LIST, Map.of("source", "latest", "count", 7)),
                Section.of(io.pacod.cms.core.design.SectionType.SEARCH_BOX, Map.of()))));
        answer(Map.of("sections", List.of(
                Map.of("region", "main", "type", "article-list", "settings", Map.of("source", "latest", "count", 3)))));

        assist.layout(PageType.HOME, "fewer stories", DesignAssist.Mode.REFINE, new DesignAssist.Actor("usr_a", null));
        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().user()).contains("Brief: fewer stories", "article-list", "\"count\":7").doesNotContain("search-box");
        assertThat(call.getValue().system()).contains("keeping every section");
    }

    @Test
    @DisplayName("a page section is never offered, shown or taken: it names a page of the website, which the draft keeps")
    void pageSectionsAreNotAssisted() {
        when(layouts.draftOf(PageType.HOME)).thenReturn(Map.of("main", List.of(
                Section.of(io.pacod.cms.core.design.SectionType.PAGE, Map.of("page", "platform")),
                Section.of(io.pacod.cms.core.design.SectionType.RICH_TEXT, Map.of("html", "<p>x</p>")))));
        answer(Map.of("sections", List.of(
                Map.of("region", "main", "type", "page", "settings", Map.of("page", "terms")),
                Map.of("region", "main", "type", "rich-text", "settings", Map.of("html", "<p>y</p>")))));

        DesignAssist.Outcome outcome = assist.layout(PageType.HOME, "a word about us", DesignAssist.Mode.REFINE,
                new DesignAssist.Actor("usr_a", null));
        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.refused()).containsExactly("page");
        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        // Minimal renders page sections; the model is still never told of them, nor shown the draft's.
        assertThat(call.getValue().schema().toString()).contains("rich-text").doesNotContain("page");
        assertThat(call.getValue().user()).contains("rich-text").doesNotContain("platform");
    }

    @Test
    @DisplayName("an absent or unknown mode refines; only replace starts over")
    void modes() {
        assertThat(DesignAssist.Mode.of(null)).isEqualTo(DesignAssist.Mode.REFINE);
        assertThat(DesignAssist.Mode.of("whatever")).isEqualTo(DesignAssist.Mode.REFINE);
        assertThat(DesignAssist.Mode.of("replace")).isEqualTo(DesignAssist.Mode.REPLACE);
    }
}
