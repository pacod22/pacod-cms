package io.pacod.cms.site.controller;

import io.pacod.cms.core.design.PageType;
import io.pacod.cms.site.render.PageComposer;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.site.exceptions.PageNotFoundException;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.PageService;
import io.pacod.cms.site.services.SiteMetricCollector;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * The standing pages -- about, contact, terms and the rest -- at {@code /p/{slug}}.
 *
 * <h2>Language</h2>
 * Resolved from {@code ?lang} then the {@code lang} cookie, the same precedence
 * {@code ArticleTemplateController} applies -- so a reader who switched language on an article
 * keeps it here.
 */
@Slf4j
@Controller
public class PageController {

    private final PageService pageService;
    private final PageComposer composer;
    private final BannerService bannerService;
    private final SiteMetricCollector metrics;

    public PageController(PageService pageService, PageComposer composer, BannerService bannerService,
                          SiteMetricCollector metrics) {
        this.pageService = pageService;
        this.composer = composer;
        this.bannerService = bannerService;
        this.metrics = metrics;
    }

    /**
     * The canonical route. {@code /p/terms} is what new links should use.
     *
     * <p>It answers at {@code /{lang}/p/{slug}} as well, the prefix
     * {@code ArticleTemplateController.getArticle} documents -- two or three lowercase letters,
     * two patterns on one mapping rather than two handlers, and never checked against the
     * {@code language} collection. That last point is stronger here than anywhere else: this
     * controller is the one whose text really is translated per language, and
     * {@code LanguageChain} resolves against the codes a {@code Page} actually holds. A prefix
     * naming a language since removed from the collection still renders, and still says in the
     * notice what it fell back to.
     *
     * <p>The prefix is what the reader <em>asked for</em>, so it feeds {@code requestedLang} and
     * not {@code lang}: those are two different facts on this screen, and the model attribute
     * named {@code lang} is the language actually served. {@code /de/p/about} on a page with no
     * German therefore renders English, says so in German, and writes {@code <html lang="en">} --
     * exactly what {@code /p/about?lang=de} does today.
     */
    @GetMapping({"/p/{slug}", "/{pathLang:[a-z]{2,3}}/p/{slug}"})
    public String page(@PathVariable String slug, @PathVariable(required = false) String pathLang,
                       HttpServletRequest request, Model model,
                       @RequestParam(required = false) String lang,
                       @CookieValue(name = "lang", required = false) String langCookie)
            throws PageNotFoundException {
        // The path wins over ?lang and over the cookie: it is part of the URL's identity, and a
        // cookie left over from an earlier visit must not decide what a shared link renders as.
        return render(pageService.bySlug(slug), request, model,
                pathLang != null ? pathLang : lang, langCookie);
    }

    private String render(Optional<Page> found, HttpServletRequest request, Model model,
                          String lang, String langCookie) throws PageNotFoundException {
        Page page = found.orElseThrow(() ->
                new PageNotFoundException(request.getRequestURI()));

        String resolved = resolveLang(lang, langCookie);
        PageService.Rendered rendered = pageService.render(page, resolved);

        model.addAttribute("page", rendered);
        // The template writes lang= on every link and on <html lang>, so it must be the language
        // actually rendered, not the one asked for -- otherwise a fallback page claims in its own
        // markup to be in a language it is not.
        model.addAttribute("lang", rendered.language());
        model.addAttribute("requestedLang", resolved);
        model.addAttribute("languages", pageService.languages());
        model.addAttribute("regions", composer.compose(PageType.PAGE, rendered.language()));

        model.addAttribute("topBanners", bannerService.forPage(BannerPlacement.TOP, BannerPage.PAGE));
        model.addAttribute("sidebarBanners", bannerService.forPage(BannerPlacement.SIDEBAR, BannerPage.PAGE));
        metrics.recordPageView(BannerPage.PAGE, request);

        return "page";
    }

    /**
     * The language the reader asked for: {@code ?lang}, then the cookie, then the site's own.
     *
     * <p>Normalisation is {@link LanguageChain#normalise}, shared with everything else that takes
     * a code off a request -- the {@code lang} cookie can hold {@code en-US}, because
     * {@code CookieLocaleResolver} serialises a whole {@code Locale}.
     */
    private String resolveLang(String requested, String fromCookie) {
        String candidate = (requested != null && !requested.isBlank()) ? requested
                : (fromCookie != null && !fromCookie.isBlank()) ? fromCookie
                : LanguageChain.original();

        String normalised = LanguageChain.normalise(candidate);
        return normalised != null ? normalised : LanguageChain.original();
    }
}
