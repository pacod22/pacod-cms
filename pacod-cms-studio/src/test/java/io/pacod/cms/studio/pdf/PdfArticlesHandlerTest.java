package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.studio.jobs.JobContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TestTenantExtension binds {@code test}, and OriginalLanguageExtension makes {@code bg} the
 * original: an article translated into German is two PDFs.
 */
class PdfArticlesHandlerTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-0123456789";
    private static final byte[] PDF = "%PDF-1.7 test".getBytes();

    @TempDir
    Path storage;

    private final GotenbergClient gotenberg = mock(GotenbergClient.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final DomainRepository domains = mock(DomainRepository.class);
    private final PrintTokens tokens = new PrintTokens(SECRET);
    private PdfArticlesHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PdfArticlesHandler(gotenberg, tokens, articles, domains, "http://site:8443/", storage.toString());
        when(domains.findByWebsiteId("test")).thenReturn(List.of(new Domain("test.example", "test", false),
                new Domain("www.test.example", "test", true)));
        Article article = new Article(12);
        article.setStatus(io.pacod.cms.core.data.ArticleStatus.PUBLISHED);
        article.setText("<p>Текст</p>");
        article.setTexts(List.of(new TextItem("<p>Text</p>", "de")));
        when(articles.findById(12)).thenReturn(Optional.of(article));
        when(articles.findById(13)).thenReturn(Optional.empty());
        Article draft = new Article(14);
        draft.setStatus(io.pacod.cms.core.data.ArticleStatus.DRAFT);
        when(articles.findById(14)).thenReturn(Optional.of(draft));
    }

    private static JobContext context(Map<String, String> params) {
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("test");
        job.setType(JobType.PDF_ARTICLES);
        job.setParams(new java.util.LinkedHashMap<>(params));
        return new JobContext(job, mock(SiteLiveness.class));
    }

    @Test
    @DisplayName("one PDF per language the article is written in, through the print view of the website's primary host")
    void everyLanguage() throws Exception {
        when(gotenberg.render(anyString(), anyString(), any())).thenReturn(PDF);
        JobContext context = context(Map.of("ids", "12,13,14"));
        handler.run(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(gotenberg).render(eq("http://site:8443/article-print/12?lang=bg"), eq("http://site:8443"), headers.capture());
        verify(gotenberg).render(eq("http://site:8443/article-print/12?lang=de"), eq("http://site:8443"), any());
        assertThat(headers.getValue()).containsEntry("X-Forwarded-Host", "www.test.example");
        assertThat(tokens.verify(headers.getValue().get(PrintTokens.HEADER), "test", Instant.now())).isTrue();

        assertThat(Files.readAllBytes(storage.resolve("sites/test/resources/pdfs/articles/bg/article-12.pdf"))).isEqualTo(PDF);
        assertThat(storage.resolve("sites/test/resources/pdfs/articles/de/article-12.pdf")).exists();
        assertThat(context.progress().done()).isEqualTo(2);
        assertThat(context.progress().total()).isEqualTo(2);
        assertThat(context.progress().log()).contains("No article 13", "article 14 is not published; skipped.");
        verify(gotenberg, org.mockito.Mockito.never()).render(contains("article-print/14"), anyString(), any());
        // Nothing half-written is left beside them.
        try (var files = Files.list(storage.resolve("sites/test/resources/pdfs/articles/bg"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("article-12.pdf");
        }
    }

    @Test
    @DisplayName("languages narrow the run; a refused render is one failed item, the rest go on")
    void narrowedAndFailing() throws Exception {
        when(gotenberg.render(contains("lang=de"), anyString(), any())).thenThrow(new GotenbergClient.PdfException("Gotenberg answered 503"));
        when(gotenberg.render(contains("lang=bg"), anyString(), any())).thenReturn(PDF);
        JobContext context = context(Map.of("ids", "12", "languages", "de"));
        handler.run(context);
        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("FAILED article 12 (de): Gotenberg answered 503");
        assertThat(storage.resolve("sites/test/resources/pdfs/articles/bg/article-12.pdf")).doesNotExist();
    }

    @Test
    @DisplayName("no articles named, too many, or no address: refused before any render")
    void refusals() {
        assertThatThrownBy(() -> handler.run(context(Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.run(context(Map.of("from", "1", "to", "1000"))))
                .hasMessageContaining("At most");
        when(domains.findByWebsiteId("test")).thenReturn(List.of());
        assertThatThrownBy(() -> handler.run(context(Map.of("ids", "12")))).hasMessageContaining("no address");
    }
}
