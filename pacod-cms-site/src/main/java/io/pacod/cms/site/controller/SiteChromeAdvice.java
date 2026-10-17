package io.pacod.cms.site.controller;

import io.pacod.cms.site.data.SiteChrome;
import io.pacod.cms.site.render.SitePreview;
import io.pacod.cms.site.render.SitePrint;
import io.pacod.cms.site.services.LanguageReader;
import io.pacod.cms.site.services.SiteChromeReader;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Optional;

/**
 * Puts {@code ${site}} -- the website's name, look, contact details, tracking ids and menus --
 * into the model of every page the public site renders.
 *
 * <p>Scoped to the template controllers by type, not to a package: the JSON controllers under
 * {@code /web/v1} render no chrome and should not pay for reading it, and Boot's
 * {@link BasicErrorController} must be included or the error page loses the masthead -- it lives
 * outside this application's packages.
 *
 * <p>The advice never throws. {@link SiteChromeReader} already degrades an unreadable database to
 * empty settings; anything else that goes wrong still yields {@link SiteChrome#empty}, because a
 * failure here inside an error dispatch would re-enter the error dispatch.
 */
@Slf4j
@ControllerAdvice(assignableTypes = {ArticleTemplateController.class, PageController.class,
        SearchController.class, SocialController.class, BasicErrorController.class})
public class SiteChromeAdvice {

    private final SiteChromeReader reader;
    private final LanguageReader languageReader;
    private final io.pacod.cms.site.render.ThemeReader themes;

    public SiteChromeAdvice(SiteChromeReader reader, LanguageReader languageReader,
                            io.pacod.cms.site.render.ThemeReader themes) {
        this.reader = reader;
        this.languageReader = languageReader;
        this.themes = themes;
    }

    /** The website's theme sheet, for the head's link; error pages need it as much as any. */
    @ModelAttribute("theme")
    public io.pacod.cms.site.render.ThemeReader.Stylesheet theme(HttpServletRequest request) {
        try {
            Optional<SitePreview.Preview> preview = SitePreview.of(request);
            if (preview.isPresent()) {
                return themes.draft().forPreview(preview.get().token());
            }
            return themes.published();
        } catch (RuntimeException e) {
            log.error("Could not read the theme; rendering without it: {}", e.getMessage());
            return new io.pacod.cms.site.render.ThemeReader.Stylesheet("", "");
        }
    }

    /** The studio's draft preview, or null: the head then carries its token for preview.js. */
    @ModelAttribute("preview")
    public SitePreview.Preview preview(HttpServletRequest request) {
        return SitePreview.of(request).orElse(null);
    }

    /**
     * The chrome. In a preview it is read past the cache -- a menu edit shows at once -- and
     * tells no third party about the page ({@link SiteChrome#withoutTracking}).
     */
    @ModelAttribute("site")
    public SiteChrome site(HttpServletRequest request) {
        String origin = origin(request);
        try {
            if (SitePreview.of(request).isPresent()) {
                return SiteChrome.of(reader.settingsNow(), reader.menusNow(), origin, languageReader.findAll(),
                        request.getRequestURI(), request.getQueryString()).withoutTracking();
            }
            SiteChrome chrome = SiteChrome.of(reader.settings(), reader.menus(), origin, languageReader.findAll(),
                    request.getRequestURI(), request.getQueryString());
            // The PDF renderer reads what is published, and tells no third party either.
            return SitePrint.is(request) ? chrome.withoutTracking() : chrome;
        } catch (RuntimeException e) {
            log.error("Could not build the site chrome; rendering it empty: {}", e.getMessage());
            return SiteChrome.empty(origin);
        }
    }

    /** Scheme, host and port as the reader sees them: forwarded headers are already applied. */
    static String origin(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request)
                .replacePath(null).replaceQuery(null).build().toUriString();
    }
}
