package io.pacod.cms.studio.design;

import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.Theme;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.ThemeRepository;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignThemeAndMenusTest {

    private Theme stored;
    private final Map<String, Menu> menuStore = new HashMap<>();
    private DesignTheme theme;
    private DesignMenus menus;

    @BeforeEach
    void setUp() {
        ThemeRepository themes = mock(ThemeRepository.class);
        when(themes.findById(Theme.ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(themes.save(any(Theme.class))).thenAnswer(i -> stored = i.getArgument(0));
        theme = new DesignTheme(themes, new DesignLayouts(mock(LayoutRepository.class), new TemplateSetCatalog()));
        MenuRepository repo = mock(MenuRepository.class);
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(menuStore.get(i.<String>getArgument(0))));
        when(repo.save(any(Menu.class))).thenAnswer(i -> {
            Menu m = i.getArgument(0);
            menuStore.put(m.getKey(), m);
            return m;
        });
        menus = new DesignMenus(repo);
    }

    private void on(String set, Runnable work) {
        TenantContext.runAs(new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), set), work);
    }

    @Test
    @DisplayName("a theme draft takes values its tokens accept, refuses the rest by name, and publishes as a copy")
    void themeDraftAndPublish() {
        on("editorial", () -> {
            assertThatThrownBy(() -> theme.saveDraft(Map.of("accent", "green", "bg", "#fff"), null))
                    .isInstanceOf(MgmtOperationException.class).hasMessageContaining("accent");
            theme.saveDraft(Map.of("accent", "#1D4E6F", "bg", "", "font-display", "georgia"), ".x { color: red; }");
        });
        assertThat(stored.getDraft()).containsExactlyInAnyOrderEntriesOf(Map.of("accent", "#1d4e6f", "font-display", "georgia"));
        assertThat(stored.getPublished()).isEmpty();
        on("editorial", theme::publish);
        assertThat(stored.getPublished()).isEqualTo(stored.getDraft()).isNotSameAs(stored.getDraft());
        assertThat(stored.getPublishedCss()).isEqualTo(".x { color: red; }");
    }

    @Test
    @DisplayName("a preset is the current set's; it replaces the draft's values and keeps its own CSS")
    void presets() {
        on("minimal", () -> {
            theme.saveDraft(Map.of("accent", "#000000"), ".mine {}");
            theme.applyPreset("slate");
            assertThatThrownBy(() -> theme.applyPreset("classic")).isInstanceOf(MgmtOperationException.class);
        });
        assertThat(stored.getDraft()).containsEntry("accent", "#4338ca").containsKey("shell");
        assertThat(stored.getDraftCss()).isEqualTo(".mine {}");
        assertThat(stored.getPreset()).isEqualTo("slate");
    }

    @Test
    @DisplayName("menu items: labels per language, links checked as the site checks them, one level of nesting")
    void menuItems() {
        menus.add("main", null, new DesignMenus.ItemForm(Map.of("en", "Travel", "de", "Reisen"), MenuLinkKind.CATEGORY, "4"));
        menus.add("main", "0", new DesignMenus.ItemForm(Map.of("en", "About"), MenuLinkKind.PAGE, "about"));
        menus.add("main", null, new DesignMenus.ItemForm(Map.of("en", "Home"), MenuLinkKind.HOME, "ignored"));
        Menu main = menuStore.get("main");
        assertThat(main.getItems()).hasSize(2);
        assertThat(main.getItems().get(0).getChildren()).hasSize(1);
        assertThat(main.getItems().get(1).getTarget()).isNull();

        assertThatThrownBy(() -> menus.add("main", "0.0", new DesignMenus.ItemForm(Map.of("en", "x"), MenuLinkKind.HOME, null)))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> menus.add("main", null, new DesignMenus.ItemForm(Map.of("en", "x"), MenuLinkKind.URL, "javascript:alert(1)")))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> menus.add("main", null, new DesignMenus.ItemForm(Map.of("en", "x"), MenuLinkKind.CATEGORY, "travel")))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> menus.add("main", null, new DesignMenus.ItemForm(Map.of("en", " "), MenuLinkKind.HOME, null)))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> menus.load("sidebar")).isInstanceOf(io.pacod.cms.studio.MgmtNotFoundException.class);

        menus.move("main", "1", -1);
        assertThat(menuStore.get("main").getItems().get(0).getKind()).isEqualTo(MenuLinkKind.HOME);
        menus.update("main", "1.0", new DesignMenus.ItemForm(Map.of("en", "About us"), MenuLinkKind.PAGE, "about"));
        assertThat(menuStore.get("main").getItems().get(1).getChildren().getFirst().getLabels().getFirst().getText()).isEqualTo("About us");
        menus.remove("main", "1.0");
        assertThat(menuStore.get("main").getItems().get(1).getChildren()).isEmpty();
    }
}
