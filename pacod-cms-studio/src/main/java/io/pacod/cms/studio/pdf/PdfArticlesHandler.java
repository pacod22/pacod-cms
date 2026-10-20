package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.PdfEnrichment;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.HtmlSafety;
import io.pacod.cms.studio.ai.PdfEnricher;
import io.pacod.cms.studio.jobs.ArticleSelection;
import io.pacod.cms.studio.jobs.JobContext;
import io.pacod.cms.studio.jobs.JobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code PDF_ARTICLES}: each article's print view ({@code /article-print/{id}}), rendered by
 * Gotenberg into {@code resources/pdfs/articles/<lang>/article-<id>.pdf} in the website's folder
 * -- once per language the article is written in: its original and every translation.
 *
 * <p>Parameters: the articles and, optionally, the languages ({@link ArticleSelection}); the
 * {@code format} ({@link PdfFormat}) and {@code mode} ({@link PdfMode}), which the studio resolves
 * against the website's {@link PdfSettings} when it queues the job and this handler resolves again
 * for a job queued without them; and {@code refresh}, which asks the gateway again where an
 * abstract already matches. The paper, margins, running header and page numbers come from the
 * settings as they stand when the job runs.
 *
 * <p>In {@code mode=llm} each article's abstract is written first ({@link PdfEnricher}); a failure
 * there fails that article and language, which is not rendered. Each article has one PDF per
 * language whatever its format: the latest render wins.
 *
 * <p>A file is written whole (a temporary file, then a move) and overwritten by a later run, and
 * an abstract that still matches is not asked for again: running again is safe.
 */
@Component
public class PdfArticlesHandler implements JobHandler {

    private final PdfRenderer renderer;
    private final ArticleRepository articles;
    private final PdfSettingsRepository settingsRepository;
    private final PdfEnricher enricher;
    private final AiAccess access;
    private final String storageRoot;

    public PdfArticlesHandler(PdfRenderer renderer, ArticleRepository articles,
                              PdfSettingsRepository settingsRepository, PdfEnricher enricher, AiAccess access,
                              @Value("${pacod.storage.root:}") String storageRoot) {
        this.renderer = renderer;
        this.articles = articles;
        this.settingsRepository = settingsRepository;
        this.enricher = enricher;
        this.access = access;
        this.storageRoot = storageRoot;
    }

    @Override
    public JobType type() {
        return JobType.PDF_ARTICLES;
    }

    @Override
    public void run(JobContext context) throws IOException {
        String siteId = TenantContext.current().siteId();
        String host = renderer.host(siteId).orElseThrow(() -> new IllegalStateException("The website has no address to render it at"));
        PdfSettings settings = settingsRepository.findById(PdfSettings.ID).orElseGet(PdfSettings::new);
        PdfFormat format = PdfFormat.parse(context.param("format"), settings.format());
        PdfMode mode = PdfMode.parse(context.param("mode"), settings.mode());
        if (mode == PdfMode.LLM) {
            access.refusalFor(siteId).ifPresent(refusal -> {
                throw new IllegalStateException("AI is not available for this website (" + refusal + ")");
            });
        }
        List<Integer> ids = ArticleSelection.articleIds(context);
        Set<String> only = ArticleSelection.languages(context.param("languages"));
        context.log(ids.size() + " article(s) at " + host + ", " + format.param() + ", " + mode.param());

        List<Item> work = new ArrayList<>();
        for (Integer id : ids) {
            Optional<Article> article = articles.findById(id);
            if (article.isEmpty()) {
                context.log("No article " + id + "; skipped.");
                continue;
            }
            // A PDF lands in the website's public storage, and the print view answers 404 for
            // anything a reader may not see: an unpublished article is not printed.
            if (article.get().getStatus() != ArticleRepository.PUBLIC) {
                context.log("article " + id + " is not published; skipped.");
                continue;
            }
            Set<String> languages = new LinkedHashSet<>();
            languages.add(LanguageChain.original());
            languages.addAll(article.get().translatedIn());
            for (String lang : languages) {
                if (only.isEmpty() || only.contains(lang)) {
                    work.add(new Item(article.get(), lang));
                }
            }
        }
        context.total(work.size());
        PdfEnricher.Run enrichment = mode == PdfMode.LLM
                ? enricher.run(context, siteId, settings, "true".equals(context.param("refresh"))) : null;
        String siteName = renderer.siteName(siteId);
        for (Item item : work) {
            context.checkpoint();
            int id = item.article().getId();
            String label = "article " + id + " (" + item.lang() + ")";
            try {
                Optional<PdfEnrichment> enriched = enrichment == null ? Optional.empty() : enrichment.ensure(item.article(), item.lang());
                context.checkpoint();
                byte[] pdf = renderer.article(siteId, host, id, item.lang(), format, mode,
                        options(settings, format, item, enriched, siteName));
                context.checkpoint();
                PdfRenderer.write(SiteStorage.siteRoot(storageRoot).resolve(SiteStorage.articlePdf(id, item.lang())), pdf);
                context.log(label + ": " + (pdf.length / 1024) + " KB");
                context.advance();
            } catch (PdfEnricher.Exhausted e) {
                context.log(e.getMessage());
                return;
            } catch (GotenbergClient.PdfException | AiGateway.AiException | PdfEnricher.Refused e) {
                context.fail(label + ": " + e.getMessage());
            }
        }
    }

    /**
     * A scientific PDF's running head names the journal and the article; any PDF's footer the
     * journal and the page, each as the settings ask.
     */
    private static GotenbergClient.RenderOptions options(PdfSettings settings, PdfFormat format, Item item,
                                                         Optional<PdfEnrichment> enriched, String siteName) {
        String journal = journalName(settings, item.lang(), siteName);
        String header = null;
        if (format == PdfFormat.SCIENTIFIC && settings.isRunningHeader()) {
            String running = enriched.map(PdfEnrichment::getRunningTitle).filter(t -> !t.isBlank())
                    .orElseGet(() -> runningTitle(item.article(), item.lang()));
            header = PdfHeaders.line(journal, running, settings.marginInches());
        }
        String footer = settings.isPageNumbers() ? PdfHeaders.pageFooter(journal, settings.marginInches()) : null;
        return PdfRenderer.options(settings, header, footer);
    }

    /** The journal's name in a language, else the website's. */
    static String journalName(PdfSettings settings, String lang, String siteName) {
        String name = settings.journalName(LanguageChain.forRequest(lang));
        return name == null || name.isBlank() ? siteName : name;
    }

    /** The article's title in a language as plain text, at most 80 characters: a running head's. */
    static String runningTitle(Article article, String lang) {
        String title = article.titleIn(lang);
        String plain = HtmlSafety.text(title == null ? article.getTitle() : title);
        return plain.length() > 80 ? plain.substring(0, 79).strip() + "…" : plain;
    }

    private record Item(Article article, String lang) {
    }
}
