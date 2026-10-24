package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import io.pacod.cms.site.data.ArticleModel;
import io.pacod.cms.site.data.ListItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The site's reads of journal issues, for {@code /issues} and for an issue's print front page.
 * Uncached: an issue page is rare, and a cache here would only delay a publication.
 *
 * <p>A reader sees {@link #published} issues only. {@link #anyForPrint} reads a draft too, and its
 * one caller answers only behind a valid print token. The contents list published articles only,
 * through {@code findPublishedOutlineByIdIn} and {@code findPublishedContentsByIdIn}: an article
 * unpublished after the issue went out drops from its contents rather than linking a 404.
 */
@Service
public class IssueReader {

    /**
     * One line of an issue's contents. {@code authors} is the names alone, as a table of contents
     * prints them; {@code page} is 0 on the public page, which prints none. {@code description},
     * {@code section} and the picture are the public page's ({@link #pageContents}), null in print
     * and where the article has none.
     */
    public record Entry(int articleId, String title, String authors, int page, String description, String section,
                        String imageFilename, String imageAlt) {

        public Entry(int articleId, String title, String authors, int page) {
            this(articleId, title, authors, page, null, null, null, null);
        }
    }

    /** What a template shows of an issue, computed here: SpEL can construct nothing. */
    public record IssueView(JournalIssue issue, String title, String editorial, String journalName, String issn,
                            String monthName, String coverPath) {
    }

    public static final int PAGE_SIZE = 12;

    private final JournalIssueRepository issues;
    private final ArticleRepository articles;
    private final ImageMediaRepository images;
    private final PdfSettingsRepository settings;

    public IssueReader(JournalIssueRepository issues, ArticleRepository articles, ImageMediaRepository images,
                       PdfSettingsRepository settings) {
        this.issues = issues;
        this.articles = articles;
        this.images = images;
        this.settings = settings;
    }

    public Page<JournalIssue> published(int page) {
        return issues.findPublished(PageRequest.of(Math.max(0, page), PAGE_SIZE));
    }

    public Optional<JournalIssue> published(Integer id) {
        return id == null ? Optional.empty() : issues.findPublishedById(id);
    }

    /** Any issue, drafts included: only for the print front page, behind a print token. */
    public Optional<JournalIssue> anyForPrint(Integer id) {
        return id == null ? Optional.empty() : issues.findById(id);
    }

    public IssueView view(JournalIssue issue, String lang) {
        List<String> chain = LanguageChain.forRequest(lang);
        PdfSettings pdf = settings.findById(PdfSettings.ID).orElseGet(PdfSettings::new);
        String journal = pdf.journalName(chain);
        return new IssueView(issue, issue.title(chain), issue.editorial(chain),
                journal == null || journal.isBlank() ? null : journal, pdf.validIssn(),
                monthName(issue.getMonth(), lang), coverPath(issue.getCoverImageId()));
    }

    /**
     * The published articles of {@code ids}, in that order, with {@code pages.get(i)} as the page of
     * {@code ids.get(i)} when pages are given: the print front's contents.
     */
    public List<Entry> contents(List<Integer> ids, List<Integer> pages, String lang) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, Article> found = byId(articles.findPublishedOutlineByIdIn(ids));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Article article = found.get(ids.get(i));
            if (article == null) {
                continue;
            }
            ArticleModel model = new ArticleModel(article, lang);
            entries.add(new Entry(article.getId(), model.getTitle(), model.authorNames(),
                    pages == null || i >= pages.size() ? 0 : pages.get(i)));
        }
        return entries;
    }

    /**
     * The published articles of {@code ids}, in that order, as the issue's page lists them: with
     * the summary, the section and the picture. The summary is the article's own description, never
     * {@code ArticleModel.getDescription()}, which makes one up from the date and the authors.
     */
    public List<Entry> pageContents(List<Integer> ids, String lang) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, Article> found = byId(articles.findPublishedContentsByIdIn(ids));
        List<Entry> entries = new ArrayList<>();
        for (Integer id : ids) {
            Article article = found.get(id);
            if (article == null) {
                continue;
            }
            ArticleModel model = new ArticleModel(article, lang);
            ListItem item = new ListItem(article, lang);
            entries.add(new Entry(article.getId(), model.getTitle(), model.authorNames(), 0,
                    blankToNull(item.getDescription()), blankToNull(item.kickerName()),
                    blankToNull(item.getImageFilename()), item.getImageAlt()));
        }
        return entries;
    }

    private static Map<Integer, Article> byId(List<Article> found) {
        return found.stream().collect(Collectors.toMap(Article::getId, Function.identity(), (a, b) -> a));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** Every published issue for the sitemap: ids and dates. */
    public List<JournalIssue> forSitemap() {
        return issues.findPublishedForSitemap();
    }

    private String coverPath(Integer imageId) {
        if (imageId == null) {
            return null;
        }
        return images.findById(imageId).map(ImageMedia::getFilename).filter(f -> !f.isBlank())
                .map(f -> "/assets/img/articles/" + f).orElse(null);
    }

    static String monthName(int month, String lang) {
        if (month < 1 || month > 12) {
            return "";
        }
        String name = Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag(lang == null ? "en" : lang));
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.forLanguageTag(lang == null ? "en" : lang)) + name.substring(1);
    }
}
