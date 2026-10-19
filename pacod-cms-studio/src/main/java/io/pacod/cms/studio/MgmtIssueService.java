package io.pacod.cms.studio;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.IssueRendition;
import io.pacod.cms.core.data.IssueSource;
import io.pacod.cms.core.data.IssueStatus;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.data.TextItems;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.studio.pdf.PdfRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The studio's journal issues ({@link JournalIssue}): creating, editing, filling from a month,
 * ordering, publishing and deleting them, and where their PDFs are.
 *
 * <h2>Targeted updates, never a whole save</h2>
 * The issue job writes {@code renditions.<lang>} while an editor may be saving the form; each side
 * sets only its own fields ({@link #save}, {@link #recordRendition}), so neither can undo the other.
 *
 * <h2>Two homes for a PDF</h2>
 * Every generated PDF is kept under {@code private/} ({@link SiteStorage#issueDraftPdf}), which no
 * public address names; publishing copies it to {@code resources/} ({@link SiteStorage#issuePdf}),
 * where the site serves it, and unpublishing deletes that copy. Both are idempotent.
 */
@Slf4j
@Service
public class MgmtIssueService {

    /** An article as the issue editor lists it: published, a draft, or gone. */
    public record ArticleRow(int id, String title, LocalDateTime date, ArticleStatus status, boolean missing) {

        public boolean included() {
            return !missing && status == ArticleRepository.PUBLIC;
        }
    }

    /** The fields of the issue form. */
    public record IssueEdit(int volume, int number, int year, int month, Map<String, String> titles,
                            Map<String, String> editorials, Integer coverImageId, PdfFormat format, PdfMode mode,
                            List<String> languages, List<Integer> articleIds) {
    }

    private final JournalIssueRepository issues;
    private final ArticleRepository articles;
    private final ImageMediaRepository images;
    private final MongoTemplate template;
    private final MgmtPdfSettingsService settings;
    private final String storageRoot;

    public MgmtIssueService(JournalIssueRepository issues, ArticleRepository articles, ImageMediaRepository images,
                            MongoTemplate template, MgmtPdfSettingsService settings,
                            @Value("${pacod.storage.root:}") String storageRoot) {
        this.issues = issues;
        this.articles = articles;
        this.images = images;
        this.template = template;
        this.settings = settings;
        this.storageRoot = storageRoot;
    }

    public Page<JournalIssue> list(int page) {
        return issues.findAllIssues(PageRequest.of(Math.max(0, page), 30));
    }

    public JournalIssue find(int id) {
        return issues.findById(id).orElseThrow(() -> new MgmtNotFoundException("issues.error.notFound", id));
    }

    /** The next free volume and number, from the newest issue: what a new issue's form starts with. */
    public JournalIssue suggestion() {
        JournalIssue next = new JournalIssue();
        LocalDate today = LocalDate.now();
        next.setYear(today.getYear());
        next.setMonth(today.getMonthValue());
        List<JournalIssue> newest = issues.findAllIssues(PageRequest.of(0, 1)).getContent();
        if (newest.isEmpty()) {
            next.setVolume(1);
            next.setNumber(1);
        } else {
            next.setVolume(newest.getFirst().getVolume());
            next.setNumber(newest.getFirst().getNumber() + 1);
        }
        return next;
    }

    /** A new, empty draft; its id the highest plus one, inserted so a race is a duplicate key, never an overwrite. */
    public JournalIssue create(int volume, int number, int year, int month) {
        checkFields(volume, number, year, month);
        List<JournalIssue> top = issues.getMaxId(PageRequest.of(0, 1));
        JournalIssue issue = new JournalIssue();
        issue.setId(top.isEmpty() || top.getFirst().getId() == null ? 1 : top.getFirst().getId() + 1);
        issue.setVolume(volume);
        issue.setNumber(number);
        issue.setYear(year);
        issue.setMonth(month);
        issue.setCreatedOn(LocalDateTime.now());
        issue.setLastModifiedOn(issue.getCreatedOn());
        try {
            return issues.insert(issue);
        } catch (DuplicateKeyException e) {
            throw new MgmtOperationException("issues.error.duplicate", volume, number);
        }
    }

    /** Writes the form's fields, and only those. */
    public void save(int id, IssueEdit edit) {
        JournalIssue issue = find(id);
        checkFields(edit.volume(), edit.number(), edit.year(), edit.month());
        List<Integer> ids = checkArticles(edit.articleIds());
        if (edit.coverImageId() != null && !images.existsById(edit.coverImageId())) {
            throw new MgmtOperationException("issues.error.cover", edit.coverImageId());
        }
        List<String> offered = settings.offeredCodes();
        Update update = new Update()
                .set("volume", edit.volume()).set("number", edit.number())
                .set("year", edit.year()).set("month", edit.month())
                .set("titles", texts(issue.getTitles(), edit.titles(), offered))
                .set("editorials", texts(issue.getEditorials(), edit.editorials(), offered))
                .set("coverImageId", edit.coverImageId())
                .set("format", edit.format()).set("mode", edit.mode())
                .set("languages", edit.languages().stream().filter(offered::contains).distinct().toList())
                .set("articleIds", ids)
                .set("lastModifiedOn", LocalDateTime.now());
        if (!ids.equals(issue.getArticleIds())) {
            update.set("source", IssueSource.SELECTION);
        }
        try {
            template.updateFirst(byId(id), update, JournalIssue.class);
        } catch (DuplicateKeyException e) {
            throw new MgmtOperationException("issues.error.duplicate", edit.volume(), edit.number());
        }
    }

    /** Replaces the article list with the month's published articles, oldest first; how many. */
    public int fillMonth(int id) {
        JournalIssue issue = find(id);
        LocalDateTime from = LocalDate.of(issue.getYear(), issue.getMonth(), 1).atStartOfDay();
        List<Integer> ids = articles.findPublishedIdsBetween(from, from.plusMonths(1)).stream()
                .map(Article::getId).limit(JournalIssue.MAX_ARTICLES).toList();
        template.updateFirst(byId(id), new Update().set("articleIds", ids).set("source", IssueSource.MONTH)
                .set("lastModifiedOn", LocalDateTime.now()), JournalIssue.class);
        return ids.size();
    }

    /** Moves an article one place up ({@code delta < 0}) or down. */
    public void move(int id, int articleId, int delta) {
        List<Integer> ids = new ArrayList<>(find(id).getArticleIds());
        int at = ids.indexOf(articleId);
        int to = at + Integer.signum(delta);
        if (at < 0 || to < 0 || to >= ids.size()) {
            return;
        }
        ids.add(to, ids.remove(at));
        setArticles(id, ids);
    }

    public void remove(int id, int articleId) {
        List<Integer> ids = new ArrayList<>(find(id).getArticleIds());
        if (ids.remove(Integer.valueOf(articleId))) {
            setArticles(id, ids);
        }
    }

    /** Publishes, then copies every generated PDF to its public address; whether there was any. */
    public boolean publish(int id) throws IOException {
        find(id);
        template.updateFirst(byId(id), new Update().set("status", IssueStatus.PUBLISHED)
                .set("publishedOn", LocalDateTime.now()).set("lastModifiedOn", LocalDateTime.now()), JournalIssue.class);
        boolean any = false;
        for (String lang : languagesWithFiles(find(id))) {
            Path draft = draftPdf(id, lang);
            if (Files.isRegularFile(draft)) {
                PdfRenderer.copy(draft, publicPdf(id, lang));
                any = true;
            }
        }
        return any;
    }

    /** Back to a draft, its public PDFs deleted; the private ones stay. */
    public void unpublish(int id) throws IOException {
        JournalIssue issue = find(id);
        template.updateFirst(byId(id), new Update().set("status", IssueStatus.DRAFT)
                .set("lastModifiedOn", LocalDateTime.now()), JournalIssue.class);
        for (String lang : languagesWithFiles(issue)) {
            Files.deleteIfExists(publicPdf(id, lang));
        }
    }

    /** The issue and every PDF of it. The articles stay. */
    public void delete(int id) throws IOException {
        JournalIssue issue = find(id);
        issues.deleteById(id);
        for (String lang : languagesWithFiles(issue)) {
            Files.deleteIfExists(publicPdf(id, lang));
            Files.deleteIfExists(draftPdf(id, lang));
        }
    }

    /** The issue job's record of one language's PDF: {@code renditions.<lang>}, nothing else. */
    public void recordRendition(int id, String lang, IssueRendition rendition) {
        if (lang == null || !lang.matches("[a-z]{2,3}")) {
            throw new IllegalArgumentException("Not a language code: " + lang);
        }
        template.updateFirst(byId(id), new Update().set("renditions." + lang, rendition), JournalIssue.class);
    }

    /** Where the issue's PDF in a language is kept, published or not. */
    public Path draftPdf(int id, String lang) {
        return SiteStorage.siteRoot(storageRoot).resolve(SiteStorage.issueDraftPdf(id, lang));
    }

    public Path publicPdf(int id, String lang) {
        return SiteStorage.siteRoot(storageRoot).resolve(SiteStorage.issuePdf(id, lang));
    }

    /** The issue's articles in its order, each with its title, date and status, or marked gone. */
    public List<ArticleRow> rows(List<Integer> ids, String lang) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, Article> found = articles.findOutlineByIdIn(ids).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity(), (a, b) -> a));
        List<ArticleRow> rows = new ArrayList<>();
        for (Integer articleId : ids) {
            Article article = found.get(articleId);
            if (article == null) {
                rows.add(new ArticleRow(articleId, null, null, null, true));
            } else {
                String title = article.titleIn(lang);
                rows.add(new ArticleRow(articleId, title == null ? article.getTitle() : title, article.getDate(),
                        article.getStatus(), false));
            }
        }
        return rows;
    }

    private void setArticles(int id, List<Integer> ids) {
        template.updateFirst(byId(id), new Update().set("articleIds", ids).set("source", IssueSource.SELECTION)
                .set("lastModifiedOn", LocalDateTime.now()), JournalIssue.class);
    }

    private static Set<String> languagesWithFiles(JournalIssue issue) {
        Set<String> codes = new LinkedHashSet<>(issue.getRenditions().keySet());
        codes.addAll(issue.languagesOrOriginal());
        codes.removeIf(code -> code == null || !code.matches("[a-z]{2,3}"));
        return codes;
    }

    private static List<TextItem> texts(List<TextItem> stored, Map<String, String> posted, Collection<String> offered) {
        List<TextItem> items = stored;
        for (String code : offered) {
            if (posted.containsKey(code)) {
                items = TextItems.put(items, code, posted.get(code));
            }
        }
        return items;
    }

    private static void checkFields(int volume, int number, int year, int month) {
        if (volume < 1 || volume > 9999 || number < 1 || number > 999 || year < 1900 || year > 2999
                || month < 1 || month > 12) {
            throw new MgmtOperationException("issues.error.fields");
        }
    }

    private static List<Integer> checkArticles(List<Integer> ids) {
        List<Integer> distinct = ids.stream().distinct().toList();
        if (distinct.size() > JournalIssue.MAX_ARTICLES) {
            throw new MgmtOperationException("issues.error.tooMany", JournalIssue.MAX_ARTICLES);
        }
        return distinct;
    }

    private static Query byId(int id) {
        return Query.query(Criteria.where("_id").is(id));
    }
}
