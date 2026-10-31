package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.services.sitefiles.SiteFileService;
import io.pacod.cms.site.data.SiteOrigin;
import io.pacod.cms.site.services.SiteChromeReader;
import io.pacod.cms.site.services.SiteFilesReader;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A website's crawler files -- {@code /robots.txt}, {@code /sitemap.xml}, {@code /llms.txt},
 * {@code /llms-full.txt} -- answered for the website the Host names, from its own database, so no
 * file sits in a folder and no website's list names another's.
 *
 * <ul>
 *   <li>Each is generated (core's {@code SiteFileService}), generated with the operator's
 *       additions, the operator's own text served verbatim, or off -- a 404 -- as chosen at
 *       {@code /mgmt/site-files}. A custom file left empty is a 404 too.</li>
 *   <li>Absolute addresses come from {@code SiteSettings.baseUrl}, or the request's origin while it
 *       is blank ({@link SiteOrigin}), as the pages' canonical and {@code og:url} do.</li>
 *   <li>Every answer names its type in UTF-8 and carries {@code nosniff}: a custom text is the
 *       operator's, served on the website's origin, and must never be read as HTML.</li>
 *   <li>A database that cannot be read is a 503 with {@code Retry-After}, which a crawler
 *       retries -- never an empty sitemap or a generated robots.txt, which it would believe.</li>
 * </ul>
 */
@Slf4j
@Controller
public class SiteFilesController {

    /** The four addresses, kept off {@code LocaleChangeInterceptor}: no cookie on a publicly cached answer. */
    public static final String[] PATHS = SiteFileKind.PATHS;

    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final SiteFilesReader reader;
    private final SiteChromeReader chrome;

    public SiteFilesController(SiteFilesReader reader, SiteChromeReader chrome) {
        this.reader = reader;
        this.chrome = chrome;
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots(HttpServletRequest request) {
        return answer(SiteFileKind.ROBOTS, request);
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap(HttpServletRequest request) {
        return answer(SiteFileKind.SITEMAP, request);
    }

    @GetMapping("/llms.txt")
    public ResponseEntity<String> llms(HttpServletRequest request) {
        return answer(SiteFileKind.LLMS, request);
    }

    @GetMapping("/llms-full.txt")
    public ResponseEntity<String> llmsFull(HttpServletRequest request) {
        return answer(SiteFileKind.LLMS_FULL, request);
    }

    private ResponseEntity<String> answer(SiteFileKind kind, HttpServletRequest request) {
        String body;
        try {
            SiteFiles files = reader.settings();
            SiteFileMode mode = files.modeOf(kind);
            if (mode == SiteFileMode.OFF) {
                return notFound();
            }
            if (mode == SiteFileMode.CUSTOM) {
                body = files.setting(kind).getCustom();
                if (body == null || body.isBlank()) {
                    return notFound();
                }
            } else {
                var settings = chrome.settings();
                String origin = SiteOrigin.of(settings, request);
                body = reader.generated(kind, files, origin, SiteFileService.ownHosts(settings, origin));
            }
        } catch (DataAccessException e) {
            log.error("Could not read {}: {}", kind.path(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "600")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(TEXT).body(kind.path().substring(1) + " cannot be read right now.\n");
        }
        return ResponseEntity.ok().contentType(type(kind)).cacheControl(CACHE)
                .header("X-Content-Type-Options", "nosniff").body(body);
    }

    private static ResponseEntity<String> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(TEXT)
                .header("X-Content-Type-Options", "nosniff").body("Not found.\n");
    }

    static MediaType type(SiteFileKind kind) {
        MediaType type = MediaType.parseMediaType(kind.contentType());
        return new MediaType(type.getType(), type.getSubtype(), StandardCharsets.UTF_8);
    }
}
