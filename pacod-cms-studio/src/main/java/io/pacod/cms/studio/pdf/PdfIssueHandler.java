package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.IssueRendition;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.PdfEnrichment;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtIssueService;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.HtmlSafety;
import io.pacod.cms.studio.ai.PdfEnricher;
import io.pacod.cms.studio.jobs.ArticleSelection;
import io.pacod.cms.studio.jobs.JobContext;
import io.pacod.cms.studio.jobs.JobHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code PDF_ISSUE}: a journal issue ({@link JournalIssue}) in one PDF per language -- cover,
 * editorial and contents, then its articles in its order, each with the issue's running head,
 * the pages numbered throughout.
 *
 * <h2>How the contents learn their page numbers</h2>
 * Each article is rendered on its own and its pages counted ({@link IssuePdfAssembler#pages}). The
 * front matter is then rendered with each article's first page in its address -- but how long the
 * front is depends on what it prints, so the numbers are recomputed until its length holds: at most
 * {@value #MAX_FRONT_RENDERS} renders, usually one (the first guess is a cover, an editorial when
 * there is one, and a page of contents). The printed numbers are the PDF's own page
 * numbers, so the contents, the stamped numbers and a viewer's page field agree.
 *
 * <h2>Whole or nothing, and safe to run again</h2>
 * Every piece goes into a temporary folder, deleted whatever happens. A language whose article
 * fails is not written at all -- an issue with a hole is not an issue -- and the next language is
 * tried. A finished PDF is written whole into the issue's private folder and, while the issue is
 * published, to its public address; then {@code renditions.<lang>} alone is set
 * ({@link MgmtIssueService#recordRendition}). Nothing is written after a failed checkpoint.
 *
 * <p>Parameters: {@code issue}; optionally {@code languages}, {@code format}, {@code mode} (else
 * the issue's, else the website's {@link PdfSettings}) and {@code refresh} ({@link PdfEnricher}).
 */
@Component
public class PdfIssueHandler implements JobHandler {

    static final int MAX_FRONT_RENDERS = 4;

    private final PdfRenderer renderer;
    private final JournalIssueRepository issues;
    private final ArticleRepository articles;
    private final PdfSettingsRepository settingsRepository;
    private final MgmtIssueService issueService;
    private final PdfEnricher enricher;
    private final AiAccess access;

    public PdfIssueHandler(PdfRenderer renderer, JournalIssueRepository issues, ArticleRepository articles,
                           PdfSettingsRepository settingsRepository, MgmtIssueService issueService,
                           PdfEnricher enricher, AiAccess access) {
        this.renderer = renderer;
        this.issues = issues;
        this.articles = articles;
        this.settingsRepository = settingsRepository;
        this.issueService = issueService;
        this.enricher = enricher;
        this.access = access;
    }

    @Override
    public JobType type() {
        return JobType.PDF_ISSUE;
    }

    @Override
    public void run(JobContext context) throws IOException {
        String siteId = TenantContext.current().siteId();
        String host = renderer.host(siteId).orElseThrow(() -> new IllegalStateException("The website has no address to render it at"));
        int issueId = issueId(context.param("issue"));
        JournalIssue issue = issues.findById(issueId)
                .orElseThrow(() -> new IllegalArgumentException("No issue " + issueId));
        PdfSettings settings = settingsRepository.findById(PdfSettings.ID).orElseGet(PdfSettings::new);
        PdfFormat format = PdfFormat.parse(context.param("format"), issue.getFormat() == null ? settings.format() : issue.getFormat());
        PdfMode mode = PdfMode.parse(context.param("mode"), issue.getMode() == null ? settings.mode() : issue.getMode());
        if (mode == PdfMode.LLM) {
            access.refusalFor(siteId).ifPresent(refusal -> {
                throw new IllegalStateException("AI is not available for this website (" + refusal + ")");
            });
        }

        List<Article> printed = printable(context, issue);
        if (printed.isEmpty()) {
            throw new IllegalArgumentException("The issue has no published article to print");
        }
        List<String> languages = languages(issue, ArticleSelection.languages(context.param("languages")));
        context.total(languages.size() * (printed.size() + 1));
        context.log("Issue " + issue.getVolume() + "/" + issue.getNumber() + ": " + printed.size() + " article(s) in "
                + String.join(", ", languages) + ", " + format.param() + ", " + mode.param());

        PdfEnricher.Run enrichment = mode == PdfMode.LLM
                ? enricher.run(context, siteId, settings, "true".equals(context.param("refresh"))) : null;
        String siteName = renderer.siteName(siteId);
        for (String lang : languages) {
            context.checkpoint();
            Path work = Files.createTempDirectory("pacod-issue-");
            try {
                language(context, siteId, host, issue, printed, lang, format, mode, settings, enrichment, siteName, work);
            } catch (PdfEnricher.Exhausted e) {
                context.log(e.getMessage());
                return;
            } catch (GotenbergClient.PdfException | AiGateway.AiException | PdfEnricher.Refused e) {
                context.fail("issue (" + lang + "): " + e.getMessage());
            } finally {
                FileSystemUtils.deleteRecursively(work);
            }
        }
    }

    private void language(JobContext context, String siteId, String host, JournalIssue issue, List<Article> printed,
                          String lang, PdfFormat format, PdfMode mode, PdfSettings settings,
                          PdfEnricher.Run enrichment, String siteName, Path work) throws IOException {
        String journal = PdfArticlesHandler.journalName(settings, lang, siteName);
        String issueLine = PdfHeaders.issueLine(journal, issue.getVolume(), issue.getNumber(), issue.getYear(),
                issue.getMonth(), lang);

        List<Path> files = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Article article : printed) {
            context.checkpoint();
            Optional<PdfEnrichment> enriched = enrichment == null ? Optional.empty() : enrichment.ensure(article, lang);
            context.checkpoint();
            String running = enriched.map(PdfEnrichment::getRunningTitle).filter(t -> !t.isBlank())
                    .orElseGet(() -> PdfArticlesHandler.runningTitle(article, lang));
            GotenbergClient.RenderOptions options = PdfRenderer.withBottomRoom(PdfRenderer.options(settings,
                    PdfHeaders.line(issueLine, running, settings.marginInches()), null));
            byte[] pdf = renderer.article(siteId, host, article.getId(), lang, format, mode, options);
            Path file = work.resolve("article-" + files.size() + ".pdf");
            Files.write(file, pdf);
            files.add(file);
            counts.add(IssuePdfAssembler.pages(file));
            context.advance();
        }

        GotenbergClient.RenderOptions frontOptions = PdfRenderer.withBottomRoom(PdfRenderer.options(settings, null, null));
        List<Integer> ids = printed.stream().map(Article::getId).toList();
        Path front = work.resolve("front.pdf");
        // A first guess -- the cover, the editorial when there is one, a page of contents -- that
        // is usually right, so the front is usually rendered once.
        int frontPages = 2 + (issue.editorial(LanguageChain.forRequest(lang)) == null ? 0 : 1);
        List<Integer> starts = List.of();
        boolean settled = false;
        for (int attempt = 0; attempt < MAX_FRONT_RENDERS && !settled; attempt++) {
            starts = starts(frontPages, counts);
            context.checkpoint();
            Files.write(front, renderer.issueFront(siteId, host, issue.getId(), lang, ids, starts, frontOptions));
            int rendered = IssuePdfAssembler.pages(front);
            settled = rendered == frontPages;
            frontPages = rendered;
        }
        if (!settled) {
            throw new GotenbergClient.PdfException("the contents did not settle on a length; nothing written");
        }

        List<IssuePdfAssembler.Part> parts = new ArrayList<>();
        for (int i = 0; i < printed.size(); i++) {
            parts.add(new IssuePdfAssembler.Part(files.get(i), plainTitle(printed.get(i), lang), starts.get(i)));
        }
        Path assembled = work.resolve("issue.pdf");
        IssuePdfAssembler.assemble(front, parts, issueLine, frontOptions.marginBottom() / 2, assembled);
        int pages = IssuePdfAssembler.pages(assembled);

        context.checkpoint();
        PdfRenderer.copy(assembled, issueService.draftPdf(issue.getId(), lang));
        boolean published = issues.findById(issue.getId()).map(JournalIssue::published).orElse(false);
        if (published) {
            PdfRenderer.copy(assembled, issueService.publicPdf(issue.getId(), lang));
        }
        issueService.recordRendition(issue.getId(), lang, new IssueRendition(LocalDateTime.now(), pages, format, mode,
                context.job().getId(), Files.size(assembled)));
        context.log("issue (" + lang + "): " + pages + " pages, " + (Files.size(assembled) / 1024) + " KB"
                + (published ? ", published" : ""));
        context.advance();
    }

    /**
     * Each article's first page when the front matter is {@code frontPages} long: the page after
     * the front and every article before it.
     */
    static List<Integer> starts(int frontPages, List<Integer> counts) {
        List<Integer> starts = new ArrayList<>(counts.size());
        int next = frontPages + 1;
        for (int count : counts) {
            starts.add(next);
            next += count;
        }
        return starts;
    }

    /** The issue's articles in its order: those a reader may see; the others logged and left out. */
    private List<Article> printable(JobContext context, JournalIssue issue) {
        Map<Integer, Article> found = articles.findArticleByIdIn(issue.getArticleIds()).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity(), (a, b) -> a));
        List<Article> printed = new ArrayList<>();
        for (Integer id : issue.getArticleIds()) {
            Article article = found.get(id);
            if (article == null) {
                context.log("No article " + id + "; left out.");
            } else if (article.getStatus() != ArticleRepository.PUBLIC) {
                context.log("article " + id + " is not published; left out.");
            } else {
                printed.add(article);
            }
        }
        return printed;
    }

    /** The issue's languages, narrowed to those asked for when any are. */
    static List<String> languages(JournalIssue issue, Set<String> only) {
        List<String> languages = issue.languagesOrOriginal().stream()
                .filter(code -> code != null && code.matches("[a-z]{2,3}"))
                .filter(code -> only.isEmpty() || only.contains(code)).distinct().toList();
        return languages.isEmpty() ? List.of(LanguageChain.original()) : languages;
    }

    private static String plainTitle(Article article, String lang) {
        String title = article.titleIn(lang);
        return HtmlSafety.text(title == null ? article.getTitle() : title);
    }

    private static int issueId(String value) {
        if (value == null || !value.matches("\\d{1,9}")) {
            throw new IllegalArgumentException("No issue named");
        }
        return Integer.parseInt(value);
    }
}
