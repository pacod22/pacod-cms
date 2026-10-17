package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The website's settings and menus, read once per TTL for every page.
 *
 * <p>Both degrade rather than throw: every public page renders the chrome, the error page
 * included, and a template that throws inside an error dispatch re-enters the error dispatch.
 * An unreadable database therefore costs the masthead its name and the navigation its links,
 * never the page.
 */
@Slf4j
@Service
public class SiteChromeReader {

    private final SiteSettingsRepository settingsRepository;
    private final MenuRepository menuRepository;

    public SiteChromeReader(SiteSettingsRepository settingsRepository, MenuRepository menuRepository) {
        this.settingsRepository = settingsRepository;
        this.menuRepository = menuRepository;
    }

    @Cacheable(cacheNames = CacheConfig.SITE, key = "'settings'")
    public SiteSettings settings() {
        return settingsNow();
    }

    @Cacheable(cacheNames = CacheConfig.SITE, key = "'menus'")
    public Map<String, Menu> menus() {
        return menusNow();
    }

    /** What is stored now, past the cache: the studio's preview shows a menu edit at once. */
    public SiteSettings settingsNow() {
        try {
            return settingsRepository.findById(SiteSettings.ID).orElseGet(SiteSettings::new);
        } catch (DataAccessException e) {
            log.error("Could not read the site settings; rendering without them: {}", e.getMessage());
            return new SiteSettings();
        }
    }

    /** What is stored now, past the cache. */
    public Map<String, Menu> menusNow() {
        try {
            Map<String, Menu> byKey = new LinkedHashMap<>();
            for (Menu menu : menuRepository.findAll()) {
                if (menu.getKey() != null) {
                    byKey.put(menu.getKey(), menu);
                }
            }
            return byKey;
        } catch (DataAccessException e) {
            log.error("Could not read the menus; rendering without navigation: {}", e.getMessage());
            return Map.of();
        }
    }
}
