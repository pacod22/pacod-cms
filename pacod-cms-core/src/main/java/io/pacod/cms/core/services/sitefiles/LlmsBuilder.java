package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFileSetting;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The generated {@code llms.txt} (llmstxt.org) and {@code llms-full.txt}, in the language the
 * content is written in ({@link LanguageChain#original()}).
 *
 * <ul>
 *   <li><b>llms.txt</b>: {@code # name}, {@code > tagline}, the description, then link lists --
 *       Pages, Sections (a category with a published article), Issues, Articles (the newest
 *       {@link SiteFiles#llmsArticleLimit()}, each with its description) -- and Optional, linking
 *       {@code llms-full.txt} while it is on.</li>
 *   <li><b>llms-full.txt</b>: the same head, then every published standing page and the newest
 *       {@link SiteFiles#llmsFullArticleLimit()} articles whole, their bodies as Markdown
 *       ({@link HtmlMarkdown}); at most {@link #FULL_MAX_BYTES}, said in the file when cut.</li>
 * </ul>
 *
 * Only what a reader may see: published pages, issues and articles, through the repositories'
 * public queries -- projected, so no {@code @DBRef} is resolved. With additions, the exclusions
 * leave out the addresses they name and the operator's Markdown follows at the end.
 */
@Component
public class LlmsBuilder {

    /** {@code llms-full.txt}'s ceiling: past it the newest articles are kept and the rest left out. */
    public static final int FULL_MAX_BYTES = 5 * 1024 * 1024;

    private static final int MAX_ISSUES = 200;

    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final PageRepository pages;
    private final JournalIssueRepository issues;
    private final SiteSettingsRepository settings;

    public LlmsBuilder(ArticleRepository articles, CategoryRepository categories, PageRepository pages,
                       JournalIssueRepository issues, SiteSettingsRepository settings) {
        this.articles = articles;
        this.categories = categories;
        this.pages = pages;
        this.issues = issues;
        this.settings = settings;
    }

    public String index(String origin, SiteFiles files) {
        SiteFileSetting setting = files.setting(SiteFileKind.LLMS);
        boolean plus = files.modeOf(SiteFileKind.LLMS) == SiteFileMode.AUTO_PLUS;
        PathExclusions exclusions = plus ? PathExclusions.of(setting.getExclusions()) : PathExclusions.none();
        List<String> chain = LanguageChain.forRequest(null);

        StringBuilder out = head(origin, chain);
        StringBuilder section = new StringBuilder();
        for (Page page : pages.findByPublishedTrueOrderBySortOrderAscSlugAsc()) {
            String path = pagePath(page);
            String title = page.title(chain);
            if (!exclusions.excludes(path) && title != null && !title.isBlank()) {
                link(section, title, origin + path, page.description(chain));
            }
        }
        section(out, "Pages", section);
        for (Category category : categories.findAll(Sort.by("_id"))) {
            String path = "/list/" + category.getId();
            if (category.getId() != null && !exclusions.excludes(path) && category.getName() != null
                    && articles.existsByCategoriesAndStatus(category.getId(), ArticleRepository.PUBLIC)) {
                link(section, category.getName(), origin + path, null);
            }
        }
        section(out, "Sections", section);
        for (JournalIssue issue : issues.findPublished(PageRequest.of(0, MAX_ISSUES))) {
            String path = "/issues/" + issue.getId();
            if (!exclusions.excludes(path)) {
                link(section, issueTitle(issue, chain), origin + path, null);
            }
        }
        section(out, "Issues", section);
        if (files.llmsArticleLimit() > 0) {
            for (Article article : articles.findPublishedIndexForLlms(PageRequest.of(0, files.llmsArticleLimit()))) {
                String path = "/article/" + article.getId();
                if (!exclusions.excludes(path) && article.getTitle() != null && !article.getTitle().isBlank()) {
                    link(section, article.getTitle(), origin + path, article.getDescription());
                }
            }
        }
        section(out, "Articles", section);
        if (files.isOn(SiteFileKind.LLMS_FULL)) {
            link(section, "Full text", origin + SiteFileKind.LLMS_FULL.path(),
                    "the standing pages and the newest articles in full, as Markdown");
        }
        section(out, "Optional", section);
        if (plus) {
            additions(out, setting.getAdditions());
        }
        return out.toString().stripTrailing() + "\n";
    }

    public String full(String origin, SiteFiles files) {
        SiteFileSetting setting = files.setting(SiteFileKind.LLMS_FULL);
        boolean plus = files.modeOf(SiteFileKind.LLMS_FULL) == SiteFileMode.AUTO_PLUS;
        PathExclusions exclusions = plus ? PathExclusions.of(setting.getExclusions()) : PathExclusions.none();
        List<String> chain = LanguageChain.forRequest(null);

        StringBuilder body = new StringBuilder();
        int budget = FULL_MAX_BYTES - 4 * 1024 - (plus ? bytes(setting.getAdditions()) : 0);
        boolean cut = false;
        StringBuilder pageSection = new StringBuilder();
        for (Page page : pages.findByPublishedTrueOrderBySortOrderAscSlugAsc()) {
            String path = pagePath(page);
            String title = page.title(chain);
            if (exclusions.excludes(path) || title == null || title.isBlank()) {
                continue;
            }
            String entry = entry(title, origin + path, null, page.description(chain),
                    HtmlMarkdown.convert(page.body(chain), origin));
            if (bytes(entry) > budget) {
                cut = true;
                break;
            }
            budget -= bytes(entry);
            pageSection.append(entry);
        }
        section(body, "Pages", pageSection);
        StringBuilder articleSection = new StringBuilder();
        int count = 0;
        if (!cut && files.llmsFullArticleLimit() > 0) {
            for (Article article : articles.findPublishedForLlms(PageRequest.of(0, files.llmsFullArticleLimit()))) {
                String path = "/article/" + article.getId();
                if (exclusions.excludes(path) || article.getTitle() == null || article.getTitle().isBlank()) {
                    continue;
                }
                String entry = entry(article.getTitle(), origin + path,
                        article.getDate() != null ? article.getDate().toLocalDate().toString() : null,
                        article.getDescription(), HtmlMarkdown.convert(article.getText(), origin));
                if (bytes(entry) > budget) {
                    cut = true;
                    break;
                }
                budget -= bytes(entry);
                articleSection.append(entry);
                count++;
            }
        }
        section(body, "Articles", articleSection);

        StringBuilder out = head(origin, chain);
        if (cut) {
            out.append("This file is cut at ").append(FULL_MAX_BYTES / (1024 * 1024)).append(" MB: it carries the newest ")
                    .append(count).append(" articles. Every article is listed at ").append(origin)
                    .append(SiteFileKind.SITEMAP.path()).append(".\n\n");
        }
        out.append(body);
        if (plus) {
            additions(out, setting.getAdditions());
        }
        return out.toString().stripTrailing() + "\n";
    }

    private StringBuilder head(String origin, List<String> chain) {
        SiteSettings site = settings.findById(SiteSettings.ID).orElseGet(SiteSettings::new);
        String name = site.name(chain);
        if (name == null || name.isBlank()) {
            String host = SiteFileRules.httpHost(origin);
            name = host != null ? host : "Website";
        }
        StringBuilder out = new StringBuilder("# ").append(oneLine(name)).append("\n\n");
        String tagline = site.tagline(chain);
        if (tagline != null && !tagline.isBlank()) {
            out.append("> ").append(oneLine(tagline)).append("\n\n");
        }
        String description = site.description(chain);
        if (description != null && !description.isBlank()) {
            out.append(description.strip()).append("\n\n");
        }
        return out;
    }

    private static String entry(String title, String url, String date, String description, String markdown) {
        StringBuilder entry = new StringBuilder("### ").append(oneLine(title)).append("\n\n")
                .append("Source: ").append(url).append('\n');
        if (date != null) {
            entry.append("Published: ").append(date).append('\n');
        }
        entry.append('\n');
        if (description != null && !description.isBlank()) {
            entry.append(oneLine(description)).append("\n\n");
        }
        if (!markdown.isEmpty()) {
            entry.append(markdown).append("\n\n");
        }
        return entry.toString();
    }

    private static void link(StringBuilder section, String title, String url, String description) {
        section.append("- [").append(oneLine(title).replace("]", "\\]")).append("](").append(url).append(')');
        if (description != null && !description.isBlank()) {
            section.append(": ").append(oneLine(description));
        }
        section.append('\n');
    }

    /** Appends {@code ## name} and the section's lines, if it has any, and empties the builder. */
    private static void section(StringBuilder out, String name, StringBuilder section) {
        if (!section.isEmpty()) {
            out.append("## ").append(name).append("\n\n").append(section.toString().stripTrailing()).append("\n\n");
        }
        section.setLength(0);
    }

    private static void additions(StringBuilder out, String additions) {
        if (additions != null && !additions.isBlank()) {
            out.append(additions.strip()).append("\n\n");
        }
    }

    private static String pagePath(Page page) {
        return "/p/" + UriUtils.encodePathSegment(page.getSlug(), StandardCharsets.UTF_8);
    }

    private static String issueTitle(JournalIssue issue, List<String> chain) {
        String title = issue.title(chain);
        String number = "Vol. " + issue.getVolume() + ", No. " + issue.getNumber() + " (" + issue.getYear() + ")";
        return title == null || title.isBlank() ? number : title + " -- " + number;
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
