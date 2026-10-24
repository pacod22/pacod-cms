package io.pacod.cms.core.seed;

import tools.jackson.databind.ObjectMapper;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.data.TextItems;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the website's {@link SiteSettings} and {@link Menu}s from {@code seed/site.json}, each only
 * while absent -- the {@link PageBootstrap} shape.
 *
 * <p>Absent, not "collection empty": settings and menus are independent, and an operator who has
 * written settings but deleted a menu should get the menu back, not have the settings overwritten.
 * A menu is seeded only when no menu of that key exists.
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(30)
public class SiteBootstrap implements SiteSeedStep {

    @Override
    public String name() {
        return "site settings and menus";
    }


    private static final String SEED = "seed/site.json";

    private final SiteSettingsRepository settingsRepository;
    private final MenuRepository menuRepository;
    private final ObjectMapper objectMapper;

    public SiteBootstrap(SiteSettingsRepository settingsRepository, MenuRepository menuRepository,
                         ObjectMapper objectMapper) {
        this.settingsRepository = settingsRepository;
        this.menuRepository = menuRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void seed(Website website) {
        Seed seed;
        try {
            seed = readSeed(objectMapper);
        } catch (IOException | RuntimeException e) {
            log.error("Could not read {}; the site starts without settings or menus: {}", SEED, e.getMessage());
            return;
        }

        try {
            if (!settingsRepository.existsById(SiteSettings.ID) && seed.getSettings() != null) {
                settingsRepository.save(toSettings(forWebsite(seed.getSettings(), website)));
                log.info("Seeded the site settings from {}", SEED);
            }
            for (MenuSeed menu : seed.getMenus()) {
                if (menu.getKey() != null && !menuRepository.existsById(menu.getKey())) {
                    menuRepository.save(toMenu(menu));
                    log.info("Seeded menu '{}' from {}", menu.getKey(), SEED);
                }
            }
        } catch (DataAccessException e) {
            log.error("Could not seed the site settings or menus: {}", e.getMessage());
        }
    }

    /**
     * The seed, as read from the classpath. Also what {@code DemoContent} compares a website's
     * menus with: a menu still equal to its seed is one nobody has edited.
     */
    static Seed readSeed(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = new ClassPathResource(SEED).getInputStream()) {
            return objectMapper.readValue(in, Seed.class);
        }
    }

    /**
     * The seed as it is for the website it describes, and with that website's own name for any
     * other: a website called «Acme News» must not open as «Demo Journal». The seed's taglines and
     * descriptions describe the demonstration, so they go with its name; the menus are generic and
     * stay.
     */
    static SettingsSeed forWebsite(SettingsSeed seed, Website website) {
        if (website == null || website.name() == null || website.name().isBlank()
                || seed.getNames().containsValue(website.name())) {
            return seed;
        }
        String language = website.defaultLanguage() == null || website.defaultLanguage().isBlank()
                ? "en" : website.defaultLanguage();
        SettingsSeed own = new SettingsSeed();
        own.setNames(new LinkedHashMap<>(Map.of(language, website.name())));
        own.setFooterNotes(new LinkedHashMap<>(Map.of(language, "© " + website.name())));
        own.setFavicon(seed.getFavicon());
        own.setLogoLight(seed.getLogoLight());
        own.setLogoDark(seed.getLogoDark());
        own.setOgImage(seed.getOgImage());
        return own;
    }

    static SiteSettings toSettings(SettingsSeed seed) {
        SiteSettings settings = new SiteSettings();
        settings.setNames(texts(seed.getNames()));
        settings.setTaglines(texts(seed.getTaglines()));
        settings.setDescriptions(texts(seed.getDescriptions()));
        settings.setFooterNotes(texts(seed.getFooterNotes()));
        settings.setBaseUrl(seed.getBaseUrl());
        settings.setLogoLight(seed.getLogoLight());
        settings.setLogoDark(seed.getLogoDark());
        settings.setFavicon(seed.getFavicon());
        settings.setOgImage(seed.getOgImage());
        settings.setContactAddress(seed.getContactAddress());
        settings.setContactPhone(seed.getContactPhone());
        settings.setContactEmail(seed.getContactEmail());
        settings.setSocialProfiles(new ArrayList<>(seed.getSocialProfiles()));
        settings.setLastModifiedOn(LocalDateTime.now());
        return settings;
    }

    static Menu toMenu(MenuSeed seed) {
        Menu menu = new Menu(seed.getKey());
        menu.setItems(items(seed.getItems()));
        return menu;
    }

    private static List<MenuItem> items(List<ItemSeed> seeds) {
        List<MenuItem> items = new ArrayList<>();
        for (ItemSeed seed : seeds) {
            MenuItem item = new MenuItem();
            item.setLabels(texts(seed.getLabels()));
            item.setKind(seed.getKind() != null ? seed.getKind() : MenuLinkKind.NONE);
            item.setTarget(seed.getTarget());
            item.setColumns(seed.getColumns());
            item.setChildren(items(seed.getChildren()));
            items.add(item);
        }
        return items;
    }

    /** Through {@link TextItems#put}, so a blank in the seed is dropped rather than stored as {@code ""}. */
    private static List<TextItem> texts(Map<String, String> byLang) {
        List<TextItem> items = new ArrayList<>();
        byLang.forEach((lang, text) -> items.addAll(TextItems.put(List.of(), lang, text)));
        return items;
    }

    @Data
    public static class Seed {
        private SettingsSeed settings;
        private List<MenuSeed> menus = new ArrayList<>();
    }

    @Data
    public static class SettingsSeed {
        private Map<String, String> names = new LinkedHashMap<>();
        private Map<String, String> taglines = new LinkedHashMap<>();
        private Map<String, String> descriptions = new LinkedHashMap<>();
        private Map<String, String> footerNotes = new LinkedHashMap<>();
        private String baseUrl;
        private String logoLight;
        private String logoDark;
        private String favicon;
        private String ogImage;
        private String contactAddress;
        private String contactPhone;
        private String contactEmail;
        private List<SiteSettings.SocialProfile> socialProfiles = new ArrayList<>();
    }

    @Data
    public static class MenuSeed {
        private String key;
        private List<ItemSeed> items = new ArrayList<>();
    }

    @Data
    public static class ItemSeed {
        private Map<String, String> labels = new LinkedHashMap<>();
        private MenuLinkKind kind;
        private String target;
        private Integer columns;
        private List<ItemSeed> children = new ArrayList<>();
    }
}
