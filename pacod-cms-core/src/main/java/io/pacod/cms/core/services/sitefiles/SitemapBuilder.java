package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFileSetting;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The generated {@code sitemap.xml}: the home page, the latest list, every published standing page,
 * every category with a published article, {@code /issues} and every published journal issue, and
 * the newest {@link #MAX_ARTICLES} published articles -- through {@code ArticleRepository}'s public
 * queries, so a draft's number is never listed, and a projection, so no row resolves its
 * {@code @DBRef}s. Bare addresses only: the pages' own heads name their translations.
 *
 * <p>With additions ({@link SiteFileMode#AUTO_PLUS}), the exclusions leave out what they name and
 * the additions' addresses follow -- a path made absolute on the origin, an absolute address kept
 * only on the website's own hosts -- each once, {@link SiteFileRules#MAX_URLS} at most in all.
 *
 * <p>A {@code DataAccessException} propagates: the site answers 503, which a crawler retries,
 * never an empty sitemap, which it would believe.
 */
@Component
public class SitemapBuilder {

    /** The protocol allows 50,000 addresses a file; the rest of the list fits in what is left. */
    public static final int MAX_ARTICLES = 45_000;

    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final PageRepository pages;
    private final JournalIssueRepository issues;

    public SitemapBuilder(ArticleRepository articles, CategoryRepository categories, PageRepository pages,
                          JournalIssueRepository issues) {
        this.articles = articles;
        this.categories = categories;
        this.pages = pages;
        this.issues = issues;
    }

    /** {@code ownHosts} are the hosts an addition's absolute address may name. */
    public String build(String origin, SiteFiles files, Set<String> ownHosts) {
        SiteFileSetting setting = files.setting(SiteFileKind.SITEMAP);
        boolean plus = files.modeOf(SiteFileKind.SITEMAP) == SiteFileMode.AUTO_PLUS;
        Entries entries = new Entries(origin, plus ? PathExclusions.of(setting.getExclusions()) : PathExclusions.none());

        entries.add("/", null);
        entries.add("/latest", null);
        for (Page page : pages.findByPublishedTrueOrderBySortOrderAscSlugAsc()) {
            entries.add("/p/" + UriUtils.encodePathSegment(page.getSlug(), StandardCharsets.UTF_8), page.getLastModifiedOn());
        }
        for (Category category : categories.findAll(Sort.by("_id"))) {
            if (category.getId() != null && articles.existsByCategoriesAndStatus(category.getId(), ArticleRepository.PUBLIC)) {
                entries.add("/list/" + category.getId(), null);
            }
        }
        var published = issues.findPublishedForSitemap();
        if (!published.isEmpty()) {
            entries.add("/issues", null);
            for (JournalIssue issue : published) {
                entries.add("/issues/" + issue.getId(),
                        issue.getLastModifiedOn() != null ? issue.getLastModifiedOn() : issue.getPublishedOn());
            }
        }
        for (Article article : articles.findPublishedForSitemap(PageRequest.of(0, MAX_ARTICLES))) {
            entries.add("/article/" + article.getId(),
                    article.getLastModifiedOn() != null ? article.getLastModifiedOn() : article.getDate());
        }
        if (plus) {
            for (String line : SiteFileRules.lines(setting.getAdditions())) {
                if (SiteFileRules.isPath(line)) {
                    entries.addAbsolute(origin + line);
                } else {
                    String host = SiteFileRules.httpHost(line);
                    if (host != null && ownHosts.contains(host)) {
                        entries.addAbsolute(line);
                    }
                }
            }
        }
        return entries.xml();
    }

    private static final class Entries {

        private final String origin;
        private final PathExclusions exclusions;
        private final Set<String> seen = new LinkedHashSet<>();
        private final StringBuilder xml = new StringBuilder(64 * 1024)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"" + SiteFileRules.SITEMAP_NS + "\">\n");

        Entries(String origin, PathExclusions exclusions) {
            this.origin = origin;
            this.exclusions = exclusions;
        }

        void add(String path, LocalDateTime modified) {
            if (!exclusions.excludes(path)) {
                entry(origin + path, modified);
            }
        }

        void addAbsolute(String location) {
            entry(location, null);
        }

        private void entry(String location, LocalDateTime modified) {
            if (seen.size() >= SiteFileRules.MAX_URLS || !seen.add(location)) {
                return;
            }
            xml.append("  <url><loc>").append(escape(location)).append("</loc>");
            if (modified != null) {
                // A date alone: the W3C format allows it, and it says nothing about a zone it cannot know.
                xml.append("<lastmod>").append(modified.toLocalDate()).append("</lastmod>");
            }
            xml.append("</url>\n");
        }

        String xml() {
            return xml + "</urlset>\n";
        }
    }

    public static String escape(String text) {
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
