package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.util.SocialTargets;
import io.pacod.cms.site.data.SocialView;
import io.pacod.cms.site.exceptions.PageNotFoundException;
import io.pacod.cms.site.services.CrawlerTraffic;
import io.pacod.cms.site.services.LanguageReader;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.SiteChromeReader;
import io.pacod.cms.site.services.SocialPageReader;
import io.pacod.cms.site.services.SocialSourceClassifier;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code /social}: the link-in-bio page, and the two redirects its tiles go through.
 *
 * <h2>The page</h2>
 * Rendered by {@code social.html}, which has its own {@code <head>} and loads no script: a script
 * that reloaded the page -- to add {@code ?lang}, say -- would count every visit twice and make
 * the counted request's {@code Referer} this page itself, destroying the attribution the page
 * exists for. No ad columns: {@code BannerService.forPage} is
 * never called, because a booking with an empty page list runs everywhere and a bio page is not
 * somewhere a booking was sold. {@code BannerPage.SOCIAL} is not bookable for the same reason.
 *
 * <p>A view is recorded unless the request is a bot or is this page reloading itself (the language
 * switcher). Both rules are in {@link SocialSourceClassifier}.
 *
 * <h2>The redirects</h2>
 * {@code GET /social/go/{linkId}} and {@code GET /social/read/{articleId}} -- plain GETs answered
 * with a 302, the shape {@code GET /b/{id}} has, so a tile works with no JavaScript in an in-app
 * browser. Four rules, each a deliberate copy of something that already exists here:
 * <ul>
 *   <li><b>The target is re-validated before {@code Location} is written</b>, through the
 *       {@link SocialTargets} the studio saved it through -- a document written by hand in mongosh
 *       must not make this an open redirect.</li>
 *   <li><b>They always redirect.</b> A link that has since been switched off, rescheduled or
 *       deleted -- a tile on a page someone opened an hour ago -- goes to {@code /social} rather
 *       than a 404, and is not counted.</li>
 *   <li><b>A click is counted only for {@code Sec-Fetch-Site} same-origin or absent</b>, the rule
 *       the banner beacons apply: a tile address pasted elsewhere still works, it just does not
 *       inflate the page's click-through rate. Bots are not counted either.</li>
 *   <li><b>The source comes from {@code ?src=}</b>, written into the href when the page was
 *       rendered; see {@link SocialView}.</li>
 * </ul>
 * Path names were checked against EasyList, EasyPrivacy and Fanboy's Social and Annoyance lists
 * before they were chosen. Check them again before renaming either.
 */
@Slf4j
@Controller
public class SocialController {

    private static final Pattern LANG = Pattern.compile("[a-z]{2,3}");
    private static final Pattern LANG_PREFIXED = Pattern.compile("^/[a-z]{2,3}/(article|list|p)/.*", Pattern.CASE_INSENSITIVE);

    private final SocialPageReader reader;
    private final LanguageReader languageReader;
    private final SiteMetricCollector metrics;
    private final SiteChromeReader siteChromeReader;

    public SocialController(SocialPageReader reader, LanguageReader languageReader,
                            SiteMetricCollector metrics, SiteChromeReader siteChromeReader) {
        this.reader = reader;
        this.languageReader = languageReader;
        this.metrics = metrics;
        this.siteChromeReader = siteChromeReader;
    }

    /**
     * One mapping with two patterns: {@code /social/} is what a reader types after copying the
     * address out of a bio by hand. No {@code /{lang}/} prefix -- only routes that take an
     * identifier carry one -- and {@code social} is six letters, so the prefix pattern cannot
     * shadow it.
     */
    @GetMapping({"/social", "/social/"})
    public String social(HttpServletRequest request, Model model,
                         @RequestParam(required = false) String src,
                         @RequestParam(name = "utm_source", required = false) String utmSource,
                         @RequestParam(required = false) String lang,
                         @CookieValue(name = "lang", required = false) String langCookie,
                         @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
                         @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer)
            throws PageNotFoundException {
        SocialPageReader.Snapshot snapshot = published()
                .orElseThrow(() -> new PageNotFoundException(request.getRequestURI()));
        SocialPage page = snapshot.page();

        String host = request.getServerName();
        SocialSource source = SocialSourceClassifier.classify(src, utmSource,
                request.getParameterMap().keySet(), userAgent, referer, host);
        // The site's one definition of a bot (CrawlerTraffic), read off the header rather than the
        // filter's attribute so these tests can assert on the User-Agent they send -- and the
        // preview's mark, which no header carries.
        if (CrawlerTraffic.classify(userAgent) == null && !CrawlerTraffic.isUncounted(request)
                && !SocialSourceClassifier.isOwnSocialPage(referer, host)) {
            metrics.recordSocialView(source, request);
        }

        List<Article> latest = page.isShowLatestArticles()
                ? reader.latestArticles(page.latestCount())
                : List.of();
        SocialView view = SocialView.of(page, snapshot.avatarPath(), snapshot.targets(),
                resolveLang(lang, langCookie), LocalDateTime.now(), latest, languageReader.findAll(), source,
                siteChromeReader.settings().ownHosts());

        model.addAttribute("social", view);
        // The page's language, which the chrome's messages follow (LocaleConfig).
        model.addAttribute("lang", view.htmlLang());
        return "social";
    }

    @GetMapping("/social/go/{linkId}")
    public ResponseEntity<Void> go(@PathVariable String linkId,
                                   @RequestParam(required = false) String src,
                                   @RequestParam(required = false) String lang,
                                   @RequestHeader(name = "Sec-Fetch-Site", required = false) String fetchSite,
                                   @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Optional<SocialPageReader.Snapshot> snapshot = published();
        if (snapshot.isEmpty()) {
            return redirect("/");
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<SocialLink> link = snapshot.get().page().link(linkId).filter(l -> l.isLive(now));
        if (link.isEmpty() || !SocialTargets.isValidTarget(link.get().getUrl())) {
            if (link.isPresent()) {
                log.warn("Refusing to redirect /social link {} to an invalid target", linkId);
            }
            return redirect(SocialView.PATH);
        }

        if (counts(fetchSite, userAgent)) {
            metrics.recordSocialClick(link.get().getId(), sourceOf(src));
        }
        return redirect(inLanguage(link.get().getUrl().strip(), lang, siteChromeReader.settings().ownHosts()));
    }

    /**
     * A link to the site's own content -- an article, a standing page, a category listing -- opens
     * it in the language the reader was reading {@code /social} in: {@code ?lang=} set on the
     * target. Only for our own content, because another site's query string is not ours to touch;
     * and not when the stored address already names a language in its path
     * ({@code /de/article/17}), which those pages rank above {@code ?lang} anyway and the operator
     * chose on purpose.
     */
    static String inLanguage(String target, String lang, Collection<String> ownHosts) {
        String code = LanguageChain.normalise(lang);
        if (code == null || !LANG.matcher(code).matches() || !SocialTargets.isSiteContent(target, ownHosts)) {
            return target;
        }
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(target);
            String path = builder.build().getPath();
            if (path != null && LANG_PREFIXED.matcher(path).matches()) {
                return target;
            }
            return builder.replaceQueryParam("lang", code).build().toUriString();
        } catch (IllegalArgumentException e) {
            return target;
        }
    }

    /**
     * An article tile. The target is always this site's own article, so there is nothing to
     * validate -- but a click is counted only for an article actually in the block, or the
     * statistics would carry whatever ids someone typed.
     */
    @GetMapping("/social/read/{articleId:\\d{1,9}}")
    public ResponseEntity<Void> read(@PathVariable int articleId,
                                     @RequestParam(required = false) String src,
                                     @RequestParam(required = false) String lang,
                                     @RequestHeader(name = "Sec-Fetch-Site", required = false) String fetchSite,
                                     @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        String normalised = LanguageChain.normalise(lang);
        String target = "/article/" + articleId
                + (normalised != null && LANG.matcher(normalised).matches() ? "?lang=" + normalised : "");

        Optional<SocialPage> page = published().map(SocialPageReader.Snapshot::page)
                .filter(SocialPage::isShowLatestArticles);
        if (page.isPresent() && counts(fetchSite, userAgent)
                && reader.latestArticles(page.get().latestCount()).stream()
                         .anyMatch(a -> a != null && a.getId() != null && a.getId() == articleId)) {
            metrics.recordSocialArticleClick(articleId, sourceOf(src));
        }
        return redirect(target);
    }

    private Optional<SocialPageReader.Snapshot> published() {
        return reader.page().filter(s -> s.page() != null && s.page().isPublished());
    }

    private static boolean counts(String fetchSite, String userAgent) {
        boolean sameOrigin = fetchSite == null || "same-origin".equalsIgnoreCase(fetchSite.strip());
        return sameOrigin && CrawlerTraffic.classify(userAgent) == null && !CrawlerTraffic.currentIsUncounted();
    }

    /** The page wrote the classified key; anything else that arrives here is {@code OTHER}. */
    private static SocialSource sourceOf(String src) {
        SocialSource source = SocialSource.of(src);
        return source == null ? SocialSource.DIRECT : source;
    }

    private static ResponseEntity<Void> redirect(String target) {
        URI location;
        try {
            location = URI.create(target);
        } catch (IllegalArgumentException e) {
            location = URI.create(SocialView.PATH);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location.toASCIIString()))
                // Never cached, so every tap reaches the counter; never indexed, so a crawler
                // that ignores rel="nofollow" does not list the redirect as a page of the site.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Robots-Tag", "noindex, nofollow")
                .build();
    }

    /** {@code ?lang}, then the cookie, then Bulgarian -- {@code PageController}'s order and shape. */
    static String resolveLang(String requested, String fromCookie) {
        for (String candidate : new String[]{requested, fromCookie}) {
            String normalised = LanguageChain.normalise(candidate);
            if (normalised != null && LANG.matcher(normalised).matches()) {
                return normalised;
            }
        }
        return LanguageChain.original();
    }
}
