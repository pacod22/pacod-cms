package io.pacod.cms.site.controller;

import io.pacod.cms.core.design.PageType;
import io.pacod.cms.site.render.PageComposer;

import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.ModelAttribute;
import io.pacod.cms.site.data.SiteChrome;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.WebArticleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The site's two search screens: the article search, server-rendered over MongoDB, and Google
 * Programmable Search, which reaches whatever Google has crawled (the article PDFs included).
 *
 * <h2>Why these are a controller and not two {@code Page} documents</h2>
 * <ul>
 *   <li><b>A search is not content.</b> Its results depend on the query, so the screen is
 *       rendered here over {@code WebArticleService.searchArticlesPageable}.</li>
 *   <li><b>The Google screen's content is a script.</b> {@code page.html} renders a body with
 *       {@code th:utext}, but the studio edits page bodies through TinyMCE, whose
 *       {@code xss_sanitization} strips {@code <script>} on save -- the first operator to open such
 *       a page and save would delete the search with no undo. The markup lives in a template, out
 *       of the editor's reach. The engine id is the website's {@code googleCseCx} setting; without
 *       one the screen redirects to the article search.</li>
 * </ul>
 *
 * <h2>No {@code /{lang}/…} prefix</h2>
 * The three reader routes carry one because each takes an identifier that names a thing. Search
 * takes a query. {@code ?lang} and the {@code lang} cookie still work, which is what every
 * in-page link is built from.
 */
@Slf4j
@Controller
public class SearchController {

    /** Matches the listing pages, so a reader moving between them sees the same rhythm. */
    private static final int PAGE_SIZE = 10;

    private final WebArticleService articleService;
    private final PageComposer composer;
    private final BannerService bannerService;
    private final SiteMetricCollector metrics;

    public SearchController(WebArticleService articleService, PageComposer composer, BannerService bannerService,
                            SiteMetricCollector metrics) {
        this.articleService = articleService;
        this.composer = composer;
        this.bannerService = bannerService;
        this.metrics = metrics;
    }

    /**
     * Full-text search over the website's articles.
     *
     * @param q the reader's query. Absent or blank is not an error and not an empty result set --
     *          it is the state the page opens in, and the template says so rather than reporting
     *          that nothing matched.
     */
    @GetMapping("/search")
    public String search(HttpServletRequest request, Model model,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) String lang,
                         @CookieValue(name = "lang", required = false) String langCookie) {

        String resolved = resolveLang(lang, langCookie);
        String query = q != null ? q.trim() : "";

        model.addAttribute("q", query);
        model.addAttribute("searched", !query.isEmpty());

        if (!query.isEmpty()) {
            // searchArticlesPageable, not the repository: it runs the query through
            // WebArticleService.escapeForRegex first. fulltextSearch is a regex query, so an
            // unescaped `(` or `*` from the search box is a malformed pattern, not a search for
            // that character -- the same reason the studio's image search uses a derived query
            // rather than a hand-written @Query.
            Page<ListItem> found = articleService.searchArticlesPageable(query,
                    PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id")),
                    resolved);

            // Reused for the pager arithmetic alone -- the window of page numbers, the previous
            // and next bounds. That maths already has an owner and a second copy of it here would
            // drift from the listing pages, which is exactly what a reader would notice first.
            // `id` and `name` stay null: this template builds its links from `q`, not from a path
            // variable.
            model.addAttribute("results", ArticleListPageable.builder().page(found).build());
        }

        model.addAttribute("lang", resolved);
        model.addAttribute("regions", composer.compose(PageType.SEARCH, resolved));
        pageRendered(model, request);

        return "search";
    }

    /**
     * The Google Custom Search screen.
     *
     * <p>Nothing here is server-rendered but the chrome: the CSE script builds the search box and
     * the results in the browser. It is a separate screen rather than a tab on the one above
     * because it searches a different index -- Google's crawl of the site, including the PDFs and
     * the pages the Mongo query cannot see.
     *
     * <p><b>{@code q} is accepted and deliberately not used by any Java code here.</b> The navbar's
     * Google button submits the site's one search box to this route, so the term arrives in the
     * URL -- as a bare {@code <a href>} the button would carry nothing, and a reader who typed a
     * word and clicked G would lose it silently. What consumes it is Google's own
     * script: CSE reads the query from the URL parameter named by its {@code queryParameterName}
     * option, which defaults to {@code q}. So the parameter has to be spelled {@code q} and has to
     * survive into the address bar, and both of those are the whole mechanism.
     *
     * <p>It is bound here anyway rather than left off the signature, because the template needs it
     * for the {@code <noscript>} fallback link -- the one part of this screen that works without
     * Google -- and because a reader who lands here can then be handed on to {@link #search} with
     * their term intact.
     */
    @GetMapping("/search-google")
    public String searchGoogle(HttpServletRequest request, Model model,
                               @ModelAttribute(name = "site", binding = false) SiteChrome site,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) String lang,
                               @CookieValue(name = "lang", required = false) String langCookie) {

        String term = q != null ? q.trim() : "";
        String resolved = resolveLang(lang, langCookie);

        // A website with no Programmable Search engine configured has no Google screen to show:
        // hand the reader to the site search, term and language intact, rather than render a
        // page whose only content is a script that cannot load.
        if (site == null || site.googleCseCx() == null) {
            return "redirect:" + UriComponentsBuilder.fromPath("/search")
                    .queryParam("q", term).queryParam("lang", resolved)
                    .encode().build().toUriString();
        }

        model.addAttribute("q", term);
        model.addAttribute("lang", resolved);
        pageRendered(model, request);

        return "search-google";
    }

    /**
     * The advertising and the traffic mark this render owes, kept together for the reason
     * {@code ArticleTemplateController.pageRendered} states: they must not drift apart, and
     * neither is visible on the page if it is dropped.
     */
    private void pageRendered(Model model, HttpServletRequest request) {
        model.addAttribute("topBanners", bannerService.forPage(BannerPlacement.TOP, BannerPage.SEARCH));
        model.addAttribute("sidebarBanners", bannerService.forPage(BannerPlacement.SIDEBAR, BannerPage.SEARCH));
        metrics.recordPageView(BannerPage.SEARCH, request);
    }

    /**
     * {@code ?lang}, then the {@code lang} cookie, then the language the site is written in.
     *
     * <p>Deliberately the shape {@code PageController.resolveLang} uses rather than
     * {@code ArticleTemplateController.readerLang}: this controller injects no {@code Locale}, and
     * ending at {@link LanguageChain#original()} is what the landing page does too. Normalisation is
     * {@link LanguageChain#normalise} because the cookie can hold {@code en-US} --
     * {@code CookieLocaleResolver} serialises a whole {@code Locale} -- and everything downstream
     * compares bare codes exactly.
     */
    private String resolveLang(String requested, String fromCookie) {
        String candidate = (requested != null && !requested.isBlank()) ? requested
                : (fromCookie != null && !fromCookie.isBlank()) ? fromCookie
                : LanguageChain.original();

        String normalised = LanguageChain.normalise(candidate);
        return normalised != null ? normalised : LanguageChain.original();
    }
}
