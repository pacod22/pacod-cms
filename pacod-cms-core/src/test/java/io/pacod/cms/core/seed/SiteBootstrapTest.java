package io.pacod.cms.core.seed;

import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shipped {@code seed/site.json}, read through the real mapper. */
class SiteBootstrapTest {

    private static final SiteSeedStep.Website DEMO = new SiteSeedStep.Website("demo", "Demo Journal", "en");

    private SiteSettingsRepository settings;
    private MenuRepository menus;
    private SiteBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        settings = mock(SiteSettingsRepository.class);
        menus = mock(MenuRepository.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        bootstrap = new SiteBootstrap(settings, menus, mapper);
    }

    @Test
    @DisplayName("an empty database gets the seeded settings and both menus")
    void seedsWhatIsAbsent() {
        when(settings.existsById(SiteSettings.ID)).thenReturn(false);
        when(menus.existsById(anyString())).thenReturn(false);

        bootstrap.seed(DEMO);

        ArgumentCaptor<SiteSettings> saved = ArgumentCaptor.forClass(SiteSettings.class);
        verify(settings).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(SiteSettings.ID);
        assertThat(saved.getValue().name(List.of("en"))).isNotBlank();

        ArgumentCaptor<Menu> menu = ArgumentCaptor.forClass(Menu.class);
        verify(menus, org.mockito.Mockito.times(2)).save(menu.capture());
        assertThat(menu.getAllValues()).extracting(Menu::getKey).containsExactly(Menu.MAIN, Menu.FOOTER);
    }

    @Test
    @DisplayName("nothing present is overwritten")
    void keepsWhatExists() {
        when(settings.existsById(SiteSettings.ID)).thenReturn(true);
        when(menus.existsById(anyString())).thenReturn(true);

        bootstrap.seed(DEMO);

        verify(settings, never()).save(any());
        verify(menus, never()).save(any());
    }

    @Test
    @DisplayName("every seeded menu item has a label and a linkable target")
    void seededMenusAreWellFormed() {
        when(settings.existsById(SiteSettings.ID)).thenReturn(true);
        when(menus.existsById(anyString())).thenReturn(false);
        bootstrap.seed(DEMO);
        ArgumentCaptor<Menu> menu = ArgumentCaptor.forClass(Menu.class);
        verify(menus, org.mockito.Mockito.times(2)).save(menu.capture());

        List<MenuItem> all = new ArrayList<>();
        for (Menu m : menu.getAllValues()) {
            for (MenuItem item : m.getItems()) {
                all.add(item);
                all.addAll(item.getChildren());
            }
        }
        assertThat(all).isNotEmpty().allSatisfy(item -> {
            assertThat(item.label(List.of("en"))).as("label").isNotBlank();
            if (item.getKind() == MenuLinkKind.CATEGORY) {
                assertThat(item.getTarget()).matches("\\d+");
            }
            if (item.getKind() == MenuLinkKind.PAGE) {
                assertThat(item.getTarget()).matches("[a-z0-9][a-z0-9-]*");
            }
            if (item.getKind() == MenuLinkKind.NONE) {
                assertThat(item.isGroup()).as("a NONE item is a group").isTrue();
            }
        });
    }

    @Test
    @DisplayName("the generic menus link no section: a website without demo content has none to link")
    void genericMenusLinkNoCategory() {
        when(settings.existsById(SiteSettings.ID)).thenReturn(true);
        when(menus.existsById(anyString())).thenReturn(false);
        bootstrap.seed(DEMO);
        ArgumentCaptor<Menu> menu = ArgumentCaptor.forClass(Menu.class);
        verify(menus, org.mockito.Mockito.times(2)).save(menu.capture());

        for (Menu m : menu.getAllValues()) {
            for (MenuItem item : m.getItems()) {
                assertThat(item.getKind()).isNotEqualTo(MenuLinkKind.CATEGORY);
                assertThat(item.getChildren()).extracting(MenuItem::getKind).doesNotContain(MenuLinkKind.CATEGORY);
            }
        }
    }

    @Test
    @DisplayName("another website opens under its own name, not the demonstration's")
    void anotherWebsiteGetsItsOwnName() {
        when(settings.existsById(SiteSettings.ID)).thenReturn(false);
        when(menus.existsById(anyString())).thenReturn(true);

        bootstrap.seed(new SiteSeedStep.Website("acme", "Acme News", "de"));

        ArgumentCaptor<SiteSettings> saved = ArgumentCaptor.forClass(SiteSettings.class);
        verify(settings).save(saved.capture());
        assertThat(saved.getValue().name(List.of("de"))).isEqualTo("Acme News");
        assertThat(saved.getValue().name(List.of("en"))).isNull();
        assertThat(saved.getValue().getTaglines()).isEmpty();
    }
}
