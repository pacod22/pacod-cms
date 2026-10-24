package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.site.exceptions.IssueNotFoundException;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.IssueReader;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.SitePdfs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The journal's issues: {@code /issues}, the archive, and {@code /issues/{id}}, one issue. Published
 * issues only; a draft's address is a 404 like an unknown one. No {@code /{lang}/} prefix, like
 * {@code /search}: the language is {@code ?lang}, then the {@code lang} cookie, then the
 * website's own.
 *
 * <p>An issue's PDFs are linked only once they exist ({@link SitePdfs#issue}), one per language
 * the studio generated. Both pages carry the advertising and the traffic mark of a listing
 * ({@link BannerPage#LIST}).
 */
@Controller
public class IssueController {

    private final IssueReader reader;
    private final SitePdfs pdfs;
    private final BannerService bannerService;
    private final SiteMetricCollector metrics;

    public IssueController(IssueReader reader, SitePdfs pdfs, BannerService bannerService, SiteMetricCollector metrics) {
        this.reader = reader;
        this.pdfs = pdfs;
        this.bannerService = bannerService;
        this.metrics = metrics;
    }

    @GetMapping("/issues")
    public String list(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String lang,
                       @CookieValue(name = "lang", required = false) String langCookie,
                       HttpServletRequest request, Model model) {
        String resolved = resolveLang(lang, langCookie);
        Page<JournalIssue> issues = reader.published(page);
        model.addAttribute("lang", resolved);
        model.addAttribute("issues", issues);
        model.addAttribute("views", issues.getContent().stream().map(i -> reader.view(i, resolved)).toList());
        rendered(model, request);
        return "issues";
    }

    @GetMapping("/issues/{id}")
    public String issue(@PathVariable Integer id, @RequestParam(required = false) String lang,
                        @CookieValue(name = "lang", required = false) String langCookie,
                        HttpServletRequest request, Model model) throws IssueNotFoundException {
        JournalIssue issue = reader.published(id).orElseThrow(() -> new IssueNotFoundException(id));
        String resolved = resolveLang(lang, langCookie);
        Map<String, String> files = new LinkedHashMap<>();
        for (String code : issue.getRenditions().keySet()) {
            pdfs.issue(issue.getId(), code).ifPresent(path -> files.put(code, path));
        }
        model.addAttribute("lang", resolved);
        model.addAttribute("view", reader.view(issue, resolved));
        model.addAttribute("entries", reader.pageContents(issue.getArticleIds(), resolved));
        model.addAttribute("pdfs", files);
        rendered(model, request);
        return "issue";
    }

    private void rendered(Model model, HttpServletRequest request) {
        model.addAttribute("topBanners", bannerService.forPage(BannerPlacement.TOP, BannerPage.LIST));
        model.addAttribute("sidebarBanners", bannerService.forPage(BannerPlacement.SIDEBAR, BannerPage.LIST));
        metrics.recordPageView(BannerPage.LIST, request);
    }

    /** {@code PageController}'s precedence: {@code ?lang}, the cookie, the website's own. */
    private static String resolveLang(String requested, String fromCookie) {
        String candidate = (requested != null && !requested.isBlank()) ? requested
                : (fromCookie != null && !fromCookie.isBlank()) ? fromCookie
                : LanguageChain.original();
        String normalised = LanguageChain.normalise(candidate);
        return normalised != null ? normalised : LanguageChain.original();
    }
}
