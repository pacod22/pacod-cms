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

        DesignAssist.Outcome outcome = assist.theme("calm and bright");
        assertThat(outcome.applied()).isEqualTo(2);
        assertThat(outcome.refused()).containsExactlyInAnyOrder("ink", "shell");
        verify(theme).saveDraft(eq(Map.of("accent", "#1d4e6f", "font-display", "georgia")), eq(".x{}"));

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
        verify(theme, never()).saveDraft(any(), any());
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

        DesignAssist.Outcome outcome = assist.layout(PageType.HOME, "a lead and a list");
        assertThat(outcome.applied()).isEqualTo(2);
        assertThat(outcome.refused()).hasSize(3);
        ArgumentCaptor<Map<String, List<Section>>> draft = ArgumentCaptor.forClass(Map.class);
        verify(layouts).replaceDraft(eq(PageType.HOME), draft.capture());
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
        verify(layouts, never()).replaceDraft(any(), any());
    }

    @Test
    @DisplayName("no description, no call")
    void noBrief() {
        assertThatThrownBy(() -> assist.theme(" ")).isInstanceOf(AiGateway.AiException.class);
        verify(gateway, never()).complete(any());
    }
}
