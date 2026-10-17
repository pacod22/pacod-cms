package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.site.services.PageReader;
import io.pacod.cms.site.services.SiteChromeReader;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * {@code /robots.txt} and {@code /sitemap.xml}, per website: answered for the website the Host
 * names, from its own database, so no file sits in a folder and no website's list names another's.
 *
 * <ul>
 *   <li><b>robots</b> keeps crawlers off what is not a page: the PDF renderer's print view, the
 *       banner and social redirects and beacons, search results, the JSON API. It names the
 *       sitemap by an absolute address, as the protocol requires.</li>
 *   <li><b>sitemap</b> lists the home page, the latest list, every published standing page, every
 *       category with a published article and the newest {@link #MAX_ARTICLES} published articles,
 *       through {@code ArticleRepository}'s public queries -- a draft's number is never listed --
 *       and a projection, so no row resolves its {@code @DBRef}s. Bare addresses only: the site
 *       writes no {@code hreflang} either.</li>
 *   <li>Absolute addresses come from {@code SiteSettings.baseUrl}, or the request's origin while it
 *       is blank, as the pages' {@code og:url} does.</li>
 *   <li>A database that cannot be read is a 503, which a crawler retries, never an empty sitemap,
 *       which it would believe.</li>
 * </ul>
 */
@Slf4j
@Controller
public class SitemapController {

    /** The protocol allows 50,000 addresses a file; the rest of the list fits in what is left. */
    static final int MAX_ARTICLES = 45_000;

    private static final MediaType XML = new MediaType("application", "xml", StandardCharsets.UTF_8);
    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final PageReader pages;
    private final SiteChromeReader chrome;

    public SitemapController(ArticleRepository articles, CategoryRepository categories, PageReader pages,
                             SiteChromeReader chrome) {
        this.articles = articles;
        this.categories = categories;
        this.pages = pages;
        this.chrome = chrome;
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots(HttpServletRequest request) {
        String body = """
                User-agent: *
                Disallow: /article-print/
                Disallow: /b/
                Disallow: /social/go/
                Disallow: /social/read/
                Disallow: /search
                Disallow: /web/v1/

                Sitemap: %s/sitemap.xml
                """.formatted(origin(request));
        return ResponseEntity.ok().contentType(TEXT).cacheControl(CACHE).body(body);
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap(HttpServletRequest request) {
        String origin = origin(request);
        StringBuilder xml = new StringBuilder(64 * 1024)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        try {
            entry(xml, origin + "/", null);
            entry(xml, origin + "/latest", null);
            for (Page page : pages.findAll()) {
                entry(xml, origin + "/p/" + UriUtils.encodePathSegment(page.getSlug(), StandardCharsets.UTF_8),
                        page.getLastModifiedOn());
            }
            for (Category category : categories.findAll(Sort.by("_id"))) {
                if (category.getId() != null && articles.existsByCategoriesAndStatus(category.getId(), ArticleRepository.PUBLIC)) {
                    entry(xml, origin + "/list/" + category.getId(), null);
                }
            }
            for (Article article : articles.findPublishedForSitemap(PageRequest.of(0, MAX_ARTICLES))) {
                LocalDateTime modified = article.getLastModifiedOn() != null ? article.getLastModifiedOn() : article.getDate();
                entry(xml, origin + "/article/" + article.getId(), modified);
            }
        } catch (DataAccessException e) {
            log.error("Could not read the sitemap's addresses: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "600")
                    .contentType(TEXT).body("The sitemap cannot be read right now.\n");
        }
        xml.append("</urlset>\n");
        return ResponseEntity.ok().contentType(XML).cacheControl(CACHE).body(xml.toString());
    }

    private static void entry(StringBuilder xml, String location, LocalDateTime modified) {
        xml.append("  <url><loc>").append(escape(location)).append("</loc>");
        if (modified != null) {
            // A date alone: the W3C format allows it, and it says nothing about a zone it cannot know.
            xml.append("<lastmod>").append(modified.toLocalDate()).append("</lastmod>");
        }
        xml.append("</url>\n");
    }

    private String origin(HttpServletRequest request) {
        String base = chrome.settings().baseUrlOrEmpty();
        String origin = !base.isEmpty() ? base : SiteChromeAdvice.origin(request);
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
