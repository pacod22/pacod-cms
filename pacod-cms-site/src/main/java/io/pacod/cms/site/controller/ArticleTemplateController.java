package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.ArticleModel;
import io.pacod.cms.site.data.HeadMeta;
import io.pacod.cms.site.data.SiteChrome;
import io.pacod.cms.site.exceptions.ArticleNotFoundException;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.services.TagService;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.PrintExtras;
import io.pacod.cms.site.services.SitePdfs;
import io.pacod.cms.site.render.PageComposer;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.site.services.WebArticleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Controller
public class ArticleTemplateController {


    private final WebArticleService articleService;
    private final TagService tagService;
    private final PageComposer composer;
    private final BannerService bannerService;
    private final SiteMetricCollector metrics;
    private final SitePdfs pdfs;
    private final PrintExtras printExtras;

    public ArticleTemplateController(WebArticleService articleService, TagService tagService,
                                     PageComposer composer,
                                     BannerService bannerService,
                                     SiteMetricCollector metrics,
                                     SitePdfs pdfs,
                                     PrintExtras printExtras) {
        this.pdfs = pdfs;
        this.printExtras = printExtras;
        this.articleService = articleService;
        this.tagService = tagService;
        this.composer = composer;
        this.bannerService = bannerService;
        this.metrics = metrics;
    }

    /**
     * Everything one render of a public page owes the rest of the system: its advertising, and a
     * mark in the traffic buckets the monitoring screen charts.
     *
     * <p>Called once per rendered view rather than from a {@code @ControllerAdvice} or an
     * interceptor, because neither would know which {@link BannerPage} it is on -- and deriving it
     * from the view name or the URL is exactly the kind of second owner of a decision that this
     * codebase avoids. This method is the one place that already holds that classification.
     *
     * <p>Both side effects must stay together and must not run for views that are not a reader on
     * the site: {@code getArticlePrint} is what gets printed and renders no advertising at all,
     * and does not call this -- which is why counting from here needs no exclusion list.
     */
    private void pageRendered(Model model, HttpServletRequest request, BannerPage page) {
        pageRendered(model, request, page, List.of());
    }

    /** {@link #pageRendered(Model, HttpServletRequest, BannerPage)} on a page of these sections. */
    private void pageRendered(Model model, HttpServletRequest request, BannerPage page, List<Integer> categories) {
        addBanners(model, page, categories);
        metrics.recordPageView(page, request);
    }

    private void addBanners(Model model, BannerPage page, List<Integer> categories) {
        model.addAttribute("topBanners", bannerService.forPage(BannerPlacement.TOP, page, categories));
        model.addAttribute("sidebarBanners", bannerService.forPage(BannerPlacement.SIDEBAR, page, categories));
    }

    /** An article's section ids: what a booking for a section matches on its page. */
    private static List<Integer> categoryIds(Article article) {
        return article.getCategories() == null ? List.of() : article.getCategories().stream()
                .filter(java.util.Objects::nonNull).map(io.pacod.cms.core.data.Category::getId)
                .filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The landing page: the website's {@code HOME} layout, rendered server-side.
     *
     * <p>Language is resolved from {@code ?lang}, then the {@code lang} cookie, then the
     * website's own language ({@link LanguageChain#original()}). The injected {@code Locale} is
     * deliberately not used here: its default is the resolver's, and a first-time visitor to the
     * home page is owed the website's language, not a guess.
     *
     * <p>Neither {@code article} nor {@code tags} is added to the model: landing.html renders
     * neither, and this is the busiest route on the site.
     */
    @GetMapping({"", "/"})
    public String getLanding(HttpServletRequest request, Model model,
                             @RequestParam(required = false) String lang,
                             @CookieValue(name = "lang", required = false) String langCookie) {

        logRequest(request);

        String resolvedLang = resolveLang(lang, langCookie);

        // The home page has no content of its own: its layout's sections are all of it.
        model.addAttribute("regions", composer.compose(PageType.HOME, resolvedLang));
        model.addAttribute("lang", resolvedLang);
        model.addAttribute("head", HeadMeta.home(resolvedLang, site(model)));
        pageRendered(model, request, BannerPage.LANDING);

        return "landing";
    }

    /**
     * Picks the first usable language and reduces it to a bare subtag.
     *
     * <p>The normalisation matters: {@code CookieLocaleResolver} persists a serialized locale,
     * so the cookie can hold {@code en-US} rather than {@code en}. Everything downstream
     * compares language codes exactly -- {@code ListItem.getLocalizedText} matches against
     * {@code TextItem.lang} values like {@code "en"}, and {@code getAuthorsAsText} tests
     * {@code "en".equalsIgnoreCase(lang)} -- so a region-qualified value would silently miss
     * every translation and fall back to the defaults with no error anywhere.
     */
    private String resolveLang(String requested, String fromCookie) {
        String candidate = (requested != null && !requested.isBlank()) ? requested
                : (fromCookie != null && !fromCookie.isBlank()) ? fromCookie
                : LanguageChain.original();

        int separator = indexOfSubtagSeparator(candidate);
        String normalized = separator > 0 ? candidate.substring(0, separator) : candidate;

        return normalized.toLowerCase();
    }

    /**
     * The language a reader page renders in: the path prefix, then {@code ?lang}, then the locale
     * the request resolved to -- which is where the {@code lang} cookie enters, through
     * {@code CookieLocaleResolver}.
     *
     * <p>Shared by {@link #getArticle} and {@link #getList} because both carry the
     * {@code /{lang}/…} prefix and must agree about what it means. {@link #resolveLang} above is
     * deliberately a different method and not a caller of this one: the landing page has no
     * prefix, and reads the cookie itself rather than through the resolver.
     *
     * <p>Normalised for the reason every request-supplied code is: everything downstream compares
     * bare codes exactly -- {@code ListItem.getLocalizedText} against {@code TextItem.lang} values
     * like {@code "en"} -- so {@code en-US} would miss every translation with nothing anywhere
     * reporting it. A blank code normalises to null and falls through, so {@code ?lang=} (empty)
     * never reaches the templates as an empty string.
     */
    private String readerLang(String pathLang, String requested, java.util.Locale locale) {
        String resolved = LanguageChain.normalise(pathLang != null ? pathLang : requested);
        return resolved != null ? resolved : locale.getLanguage();
    }

    private int indexOfSubtagSeparator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The latest-article page, at {@code /latest}; {@code /index}, {@code /index/} and
     * {@code /index.html} answer the same. {@code /} is {@link #getLanding}.
     *
     * <p>{@code RequestMappingHandlerMapping} is ordered ahead of the resource handler and the
     * welcome-page mapping, so this method answers {@code /index.html} rather than anything static.
     */
    @RequestMapping(value = {"/latest", "/index", "/index/", "/index.html"}, method = RequestMethod.GET)
    public String getLatestArticlePage(HttpServletRequest request, Model model, @RequestParam(required = false) String lang, java.util.Locale locale) throws ArticleNotFoundException {

        logRequest(request);

        if (lang == null) {
            lang = locale.getLanguage();
        }

        List<Tag> tags = currentTags();

        // index.html renders an article as the article template does, and dereferences
        // ${article.title}, ${article.images}, ${article.text} and friends: without an article
        // in the model the route fails with
        // "EL1007E: Property or field 'title' cannot be found on null", i.e. HTTP 500.
        //
        // This page therefore shows the latest article; the template expects exactly one.
        final String resolvedLang = lang;
        java.util.Optional<Article> latest = articleService.getLatestArticle();
        ArticleModel articleModel = latest
                .map(a -> new ArticleModel(a, resolvedLang, tags))
                // An empty database must not turn this page into a 500.
                .orElseGet(() -> new ArticleModel(new Article(), resolvedLang, tags));

        model.addAttribute("article", articleModel);
        model.addAttribute("lang", lang);
        model.addAttribute("tags", tags);
        model.addAttribute("pdf", pdfs.article(articleModel.getId(), lang).orElse(null));
        model.addAttribute("regions", composer.compose(PageType.LATEST, lang));
        model.addAttribute("head", HeadMeta.latest(lang, site(model)));
        pageRendered(model, request, BannerPage.INDEX, latest.map(ArticleTemplateController::categoryIds).orElse(List.of()));

        return "index";
    }

    /**
     * An article, at {@code /article/{id}} and equally at {@code /{lang}/article/{id}}.
     *
     * <h2>One method, two patterns</h2>
     * The prefixed form is a second spelling of the same page, not a second page, so it is a
     * second pattern on this mapping rather than a second handler. A copied method would render
     * the same view while silently losing what a public render owes the rest of the system --
     * {@link #pageRendered}'s advertising and its mark in the traffic buckets the monitoring
     * screen charts -- and neither omission shows up on the page itself.
     *
     * <h2>The prefix is two or three lowercase letters, and that is load-bearing twice</h2>
     * It is the same shape a language's code already has where languages are edited
     * ({@code /mgmt/pages/languages}), so a code that could be typed there is a code that
     * resolves here. And it is what keeps this pattern clear of the other three-segment routes:
     * {@code /article-print/{id}} carries a first segment longer
     * than three letters, and {@code /v1/**} -- mapped nowhere on the site -- carries a digit, so
     * a request for it 404s rather than rendering an article in a language called {@code v1}.
     *
     * <p>The standing constraint that buys, and it applies to this application as a whole:
     * <b>no top-level route may be named with two or three lowercase letters.</b> One added later
     * -- {@code /faq}, {@code /bio} -- would be shadowed by this prefix on any path below it, and
     * the symptom is a 404 about a missing article rather than a routing error. Nothing enforces
     * it; the three {@code /news/…} assertions in the controller tests are what would notice the
     * pattern being widened, not a route being added.
     *
     * <h2>The code is never checked against the {@code language} collection</h2>
     * {@code /xx/article/5} renders, in exactly the way {@code /article/5?lang=xx} does:
     * untranslated fields fall back through {@link io.pacod.cms.core.data.LanguageChain}.
     * Refusing an unlisted code would make a reader path depend on a database row existing,
     * which is the one thing the language collection deliberately does not govern.
     *
     * <p>Precedence is path, then {@code ?lang}, then the resolved {@code Locale}. The path is
     * part of the URL's identity, so it wins over a query parameter that disagrees with it. One
     * consequence worth knowing: {@code LocaleChangeInterceptor} only ever sees {@code ?lang}, so
     * the {@code lang} cookie is not updated by the prefixed form and can disagree with what was
     * rendered. That self-heals rather than persisting -- every in-page link is built from the
     * {@code lang} model attribute this method sets.
     *
     * <p>The head files every spelling under one address ({@link HeadMeta#article}): the
     * original's bare {@code /article/{id}}, a translation's {@code /{lang}/article/{id}} --
     * {@code rel="canonical"} and {@code og:url} alike -- and lists the others as {@code hreflang}
     * alternates. {@code /article/5?lang=de} and {@code /de/article/5} name the same canonical,
     * and a language the article is not translated into names the one it fell back to.
     */
    @RequestMapping(value = {"/article/{id}", "/{pathLang:[a-z]{2,3}}/article/{id}"}, method = RequestMethod.GET)
    public String getArticle(HttpServletRequest request, @PathVariable(required = false) Integer id, @PathVariable(required = false) String pathLang, Model model, @RequestParam(required = false) String lang, java.util.Locale locale) throws ArticleNotFoundException {

        logRequest(request);

        lang = readerLang(pathLang, lang, locale);

        List<Tag> tags = currentTags();

        Article article = null;
        if (id != null) {
            Integer finalId = id;
            article = articleService.getArticleById(finalId)
                    .orElseThrow(() -> new ArticleNotFoundException(finalId));
        }

        if (article == null) {
            throw new ArticleNotFoundException(0);
        }

        ArticleModel articleModel = new ArticleModel(article, lang, tags);

        model.addAttribute("article", articleModel);
        model.addAttribute("lang", lang);
        model.addAttribute("pdf", pdfs.article(articleModel.getId(), lang).orElse(null));
        model.addAttribute("regions", composer.compose(PageType.ARTICLE, lang));
        model.addAttribute("head", HeadMeta.article(articleModel, site(model)));
        pageRendered(model, request, BannerPage.ARTICLE, categoryIds(article));
        return "article";
    }

    /**
     * The print view: what the studio's PDF jobs have Gotenberg render, and a reader may open.
     *
     * <p>{@code format} picks the template -- {@code article-print} or
     * {@code article-print-scientific} -- through {@link PdfFormat}, so the view name never comes
     * from the request; anything unknown is the standard one. {@code mode} decides where the
     * abstract and keywords come from ({@link PrintExtras}). Neither changes what a reader may see:
     * an unpublished article is a 404 here as everywhere, the token only stops the count.
     */
    @RequestMapping(value = {"/article-print/{id}"}, method = RequestMethod.GET)
    public String getArticlePrint(HttpServletRequest request, @PathVariable(required = false) Integer id, Model model,
                                  @RequestParam(required = false) String lang,
                                  @RequestParam(required = false) String format,
                                  @RequestParam(required = false) String mode,
                                  java.util.Locale locale) throws ArticleNotFoundException {

        logRequest(request);

        if (lang == null) {
            lang = locale.getLanguage();
        }

        Article article = null;
        if (id != null) {
            article = articleService.getArticleById(id)
                    .orElseThrow(() -> new ArticleNotFoundException(id));
        }

        if (article == null) {
            throw new ArticleNotFoundException(0);
        }

        List<Tag> tags = currentTags();
        ArticleModel articleModel = new ArticleModel(article, lang, tags);
        PdfFormat pdfFormat = PdfFormat.parse(format, PdfFormat.STANDARD);

        model.addAttribute("article", articleModel);
        model.addAttribute("lang", lang);
        model.addAttribute("printMeta", printExtras.meta(article, lang, PdfMode.parse(mode, PdfMode.CODE), tags));
        return pdfFormat == PdfFormat.SCIENTIFIC ? "article-print-scientific" : "article-print";
    }

    /**
     * A category or tag listing, at {@code /list/{id}} and equally at {@code /{lang}/list/{id}}.
     *
     * <p>Two patterns on one mapping, for the reasons {@link #getArticle} documents at length --
     * chiefly that a copied method loses {@link #pageRendered} silently. The prefix is the same
     * {@code [a-z]{2,3}}, so {@code /list/{id}} itself cannot be read as one: {@code list} is four
     * letters.
     *
     * <p>The {@code id} is a category number <em>or</em> a tag name, and that is unaffected here:
     * the prefix is a separate segment, so {@code /bg/list/alpha} resolves the tag {@code alpha}
     * exactly as {@code /list/alpha} does.
     */
    @RequestMapping(value = {"/list/{id}", "/{pathLang:[a-z]{2,3}}/list/{id}"}, method = RequestMethod.GET)
    public String getList(HttpServletRequest request, @PathVariable String id, @PathVariable(required = false) String pathLang, @PageableDefault(page = 0, size = 20)
            @SortDefault.SortDefaults({
                    @SortDefault(sort = "date", direction = Sort.Direction.DESC)
            }) Pageable pageable, Model model, @RequestParam(required = false) String lang, java.util.Locale locale){

        logRequest(request);

        lang = readerLang(pathLang, lang, locale);

        ArticleListPageable list = null;
        Integer listId = null;

        try {
            listId = Integer.valueOf(id);
        } catch (NumberFormatException e) {
            log.debug("Id is not number: {}, trying as tag", id);
        }



        if (listId != null) {
            list = articleService.getAllArticlesByCategoryPageable(listId, pageable, lang);
        } else {
            list = articleService.getAllArticlesByTagPageable(id, pageable, lang);
            list.setName(translateTag(id, lang, currentTags()));
        }

        model.addAttribute("list", list);
        model.addAttribute("lang", lang);
        model.addAttribute("regions", composer.compose(PageType.CATEGORY, lang));
        model.addAttribute("head", HeadMeta.list(id, pageable.getPageNumber(),
                listId != null && !WebArticleService.isMostRead(listId), lang, site(model)));
        pageRendered(model, request, BannerPage.LIST, listId == null ? List.of() : List.of(listId));

        return "list";
    }

    /**
     * The chrome {@code SiteChromeAdvice} put in the model before this handler ran, or null where
     * no advice runs (a standalone controller test): the languages the head's alternates name.
     */
    private static SiteChrome site(Model model) {
        return model.getAttribute("site") instanceof SiteChrome site ? site : null;
    }

    /**
     * Returns the tag list for this request.
     *
     * <p>Per request, never an instance field: the controller is a singleton, and a
     * {@code List<Tag>} field would be shared, unsynchronized state every concurrent request
     * races on. The underlying {@code getAllTags()} is cached, so calling it per request is
     * cheap.
     */
    private List<Tag> currentTags() {
        try {
            return tagService.getAllTags();
        } catch (Exception e) {
            log.error("Failed to get tags with error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }


    private String translateTag(String tag, String lang, List<Tag> tags) {
        log.debug("Translating tag {} for lang: {}", tag, lang);
        String translatedTag = tag;
        if (tags != null && !tags.isEmpty() && tag != null && !tag.isBlank() && lang != null && !lang.isBlank()) {
            for (Tag t : tags) {
                if (t.getName().equals(tag)) {
                    translatedTag = t.translate(lang);
                    break;
                }
            }
        }

        return translatedTag;
    }

    /**
     * At DEBUG, and guarded: building the string is pure overhead whenever the level is above
     * DEBUG. No headers on purpose -- they carry cookies and authorization headers, which must
     * not land in the log file.
     */
    private void logRequest(HttpServletRequest request) {
        if (!log.isDebugEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(request.getRequestURL()).append("|");
        sb.append("Query: ").append(request.getQueryString()).append("|");
        sb.append("RemoteIP: ").append(request.getRemoteAddr()).append("|");

        sb.append("Params: ");
        request.getParameterMap().forEach((key, value) ->
                sb.append(key).append("=").append(String.join(",", value)).append("; "));

        log.debug(sb.toString());
    }
}
