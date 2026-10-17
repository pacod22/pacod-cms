package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.jobs.ArticleSelection;
import io.pacod.cms.studio.jobs.JobContext;
import io.pacod.cms.studio.jobs.JobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code PDF_ARTICLES}: each article's print view ({@code /article-print/{id}}), rendered by
 * Gotenberg into {@code resources/pdfs/articles/<lang>/article-<id>.pdf} in the website's folder
 * -- once per language the article is written in: its original and every translation.
 *
 * <p>Gotenberg reaches the site on the compose network ({@code pacod.pdf.site-url},
 * {@code http://site:8443}), where no host names a website, so every request of the render carries
 * {@code X-Forwarded-Host} -- one of the website's own hosts, which the site resolves exactly as
 * Caddy's forwarded host -- and the {@code X-Pacod-Print} token that keeps it uncounted. A file is
 * written whole (a temporary file, then a move) and overwritten by a later run: running again is
 * safe.
 *
 * <p>Parameters: the articles and, optionally, the languages ({@link ArticleSelection}).
 */
@Component
public class PdfArticlesHandler implements JobHandler {

    private final GotenbergClient gotenberg;
    private final PrintTokens tokens;
    private final ArticleRepository articles;
    private final DomainRepository domains;
    private final String siteUrl;
    private final String storageRoot;

    public PdfArticlesHandler(GotenbergClient gotenberg, PrintTokens tokens, ArticleRepository articles,
                              DomainRepository domains,
                              @Value("${pacod.pdf.site-url:http://site:8443}") String siteUrl,
                              @Value("${pacod.storage.root:}") String storageRoot) {
        this.gotenberg = gotenberg;
        this.tokens = tokens;
        this.articles = articles;
        this.domains = domains;
        this.siteUrl = siteUrl.strip().replaceAll("/+$", "");
        this.storageRoot = storageRoot;
    }

    @Override
    public JobType type() {
        return JobType.PDF_ARTICLES;
    }

    @Override
    public void run(JobContext context) throws IOException {
        String siteId = TenantContext.current().siteId();
        String host = host(siteId).orElseThrow(() -> new IllegalStateException("The website has no address to render it at"));
        List<Integer> ids = ArticleSelection.articleIds(context);
        Set<String> only = ArticleSelection.languages(context.param("languages"));
        context.log(ids.size() + " article(s) at " + host);

        List<String[]> work = new ArrayList<>();
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
                    work.add(new String[]{id.toString(), lang});
                }
            }
        }
        context.total(work.size());
        for (String[] item : work) {
            context.checkpoint();
            int id = Integer.parseInt(item[0]);
            String lang = item[1];
            try {
                byte[] pdf = gotenberg.render(siteUrl + "/article-print/" + id + "?lang=" + lang, siteUrl,
                        Map.of("X-Forwarded-Host", host, PrintTokens.HEADER, tokens.mint(siteId)));
                context.checkpoint();
                write(SiteStorage.siteRoot(storageRoot).resolve(SiteStorage.articlePdf(id, lang)), pdf);
                context.log("article " + id + " (" + lang + "): " + (pdf.length / 1024) + " KB");
                context.advance();
            } catch (GotenbergClient.PdfException e) {
                context.fail("article " + id + " (" + lang + "): " + e.getMessage());
            }
        }
    }

    /** The website's primary host, else any. */
    private Optional<String> host(String siteId) {
        return domains.findByWebsiteId(siteId).stream()
                .sorted(Comparator.comparing(Domain::isPrimary).reversed())
                .map(Domain::getHost).findFirst();
    }

    private static void write(Path file, byte[] pdf) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".article-", ".pdf.part");
        try {
            Files.write(temporary, pdf);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
