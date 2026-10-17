package io.pacod.cms.site.controller;

import io.pacod.cms.site.render.SitePreview;
import io.pacod.cms.site.render.ThemeReader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;

/**
 * {@code /theme.css}: the website's published theme ({@link ThemeReader}), an external
 * {@code text/css} file with {@code nosniff}, linked after the set's stylesheet. Requested with the
 * current version ({@code ?v=}) it is cached for a year -- a change is a new address; any other
 * address is cached a minute, like the rest of the website's chrome. A preview
 * ({@code ?_preview=}) gets the draft, {@code no-store}.
 */
@Controller
public class ThemeCssController {

    private static final MediaType CSS = new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8);

    private final ThemeReader themes;

    public ThemeCssController(ThemeReader themes) {
        this.themes = themes;
    }

    @GetMapping("/theme.css")
    public ResponseEntity<String> theme(@RequestParam(name = "v", required = false) String version) {
        boolean preview = SitePreview.current().isPresent();
        ThemeReader.Stylesheet sheet = preview ? themes.draft() : themes.published();
        CacheControl cache = preview ? CacheControl.noStore()
                : sheet.isPresent() && sheet.version().equals(version)
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();
        return ResponseEntity.ok()
                .contentType(CSS)
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .body(sheet.css());
    }
}
