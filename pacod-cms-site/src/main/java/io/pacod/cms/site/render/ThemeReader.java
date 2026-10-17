package io.pacod.cms.site.render;

import io.pacod.cms.core.design.Theme;
import io.pacod.cms.core.design.ThemeCss;
import io.pacod.cms.core.repository.ThemeRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The website's published theme as a stylesheet, cached with its layouts (60 s, per website).
 * Nothing published -- or a database that cannot be read -- is an empty sheet, and the page
 * keeps its template set's own look.
 */
@Slf4j
@Service
public class ThemeReader {

    private final ThemeRepository themes;

    public ThemeReader(ThemeRepository themes) {
        this.themes = themes;
    }

    /**
     * @param version a fingerprint of {@code css}, for its address: a published change is a new
     *                address, so the old one can be cached for good
     */
    public record Stylesheet(String css, String version, String previewToken) {

        public Stylesheet(String css, String version) {
            this(css, version, null);
        }

        public boolean isPresent() {
            return !css.isEmpty();
        }

        /** The same sheet served to a preview: its address carries the token, never a version. */
        public Stylesheet forPreview(String token) {
            return new Stylesheet(css, version, token);
        }

        /**
         * Where the head links it: {@code ?v=} for readers (a change is a new address, cached for
         * good), the preview's token for a preview ({@code no-store}, whatever the draft holds).
         */
        public String href() {
            return previewToken != null
                    ? "/theme.css?" + SitePreview.PARAMETER + "=" + URLEncoder.encode(previewToken, StandardCharsets.UTF_8)
                    : "/theme.css?v=" + version;
        }
    }

    @Cacheable(value = CacheConfig.DESIGN, key = "'theme'")
    public Stylesheet published() {
        try {
            String css = themes.findById(Theme.ID).map(t -> ThemeCss.render(t.getPublished(), t.getPublishedCss())).orElse("");
            return new Stylesheet(css, css.isEmpty() ? "" : ThemeCss.version(css));
        } catch (DataAccessException e) {
            log.error("Could not read the theme; rendering the set's own look: {}", e.getMessage());
            return new Stylesheet("", "");
        }
    }

    /** The draft, for the studio's preview: never cached (the cache is keyed per website alone). */
    public Stylesheet draft() {
        try {
            String css = themes.findById(Theme.ID).map(t -> ThemeCss.render(t.getDraft(), t.getDraftCss())).orElse("");
            return new Stylesheet(css, css.isEmpty() ? "" : ThemeCss.version(css));
        } catch (DataAccessException e) {
            log.error("Could not read the theme draft; previewing the set's own look: {}", e.getMessage());
            return new Stylesheet("", "");
        }
    }
}
