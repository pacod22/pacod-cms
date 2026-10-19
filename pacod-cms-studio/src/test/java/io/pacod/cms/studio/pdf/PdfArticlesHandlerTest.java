package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.PaperSize;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.repository.PdfEnrichmentRepository;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.PdfEnricher;
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
    private final WebsiteRepository websites = mock(WebsiteRepository.class);
    private final PdfSettingsRepository settings = mock(PdfSettingsRepository.class);
    private final PdfEnrichmentRepository enrichments = mock(PdfEnrichmentRepository.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final AiAccess access = mock(AiAccess.class);
    private final PrintTokens tokens = new PrintTokens(SECRET);
    private PdfArticlesHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PdfArticlesHandler(new PdfRenderer(gotenberg, tokens, domains, websites, "http://site:8443/"),
                articles, settings, new PdfEnricher(gateway, enrichments, "@fast"), access, storage.toString());
        when(settings.findById(PdfSettings.ID)).thenReturn(Optional.empty());
        when(websites.findById("test")).thenReturn(Optional.of(new Website("test", "org_t", "Demo Journal")));
        when(access.refusalFor("test")).thenReturn(Optional.empty());
        when(enrichments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
        when(gotenberg.render(anyString(), anyString(), any(), any())).thenReturn(PDF);
        JobContext context = context(Map.of("ids", "12,13,14"));
        handler.run(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<GotenbergClient.RenderOptions> options = ArgumentCaptor.forClass(GotenbergClient.RenderOptions.class);
        verify(gotenberg).render(eq("http://site:8443/article-print/12?lang=bg&format=standard&mode=code"),
                eq("http://site:8443"), headers.capture(), options.capture());
        verify(gotenberg).render(eq("http://site:8443/article-print/12?lang=de&format=standard&mode=code"),
                eq("http://site:8443"), any(), any());
        // The defaults: A4, page numbers in the footer, no running head on a standard PDF.
        assertThat(options.getValue().paper()).isEqualTo(PaperSize.A4);
        assertThat(options.getValue().headerHtml()).isNull();
        assertThat(options.getValue().footerHtml()).contains("pageNumber", "Demo Journal");
        assertThat(headers.getValue()).containsEntry("X-Forwarded-Host", "www.test.example");
        assertThat(tokens.verify(headers.getValue().get(PrintTokens.HEADER), "test", Instant.now())).isTrue();

        assertThat(Files.readAllBytes(storage.resolve("sites/test/resources/pdfs/articles/bg/article-12.pdf"))).isEqualTo(PDF);
        assertThat(storage.resolve("sites/test/resources/pdfs/articles/de/article-12.pdf")).exists();
        assertThat(context.progress().done()).isEqualTo(2);
        assertThat(context.progress().total()).isEqualTo(2);
        assertThat(context.progress().log()).contains("No article 13", "article 14 is not published; skipped.");
        verify(gotenberg, org.mockito.Mockito.never()).render(contains("article-print/14"), anyString(), any(), any());
        // Nothing half-written is left beside them.
        try (var files = Files.list(storage.resolve("sites/test/resources/pdfs/articles/bg"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("article-12.pdf");
        }
    }

    @Test
    @DisplayName("languages narrow the run; a refused render is one failed item, the rest go on")
    void narrowedAndFailing() throws Exception {
        when(gotenberg.render(contains("lang=de"), anyString(), any(), any())).thenThrow(new GotenbergClient.PdfException("Gotenberg answered 503"));
        when(gotenberg.render(contains("lang=bg"), anyString(), any(), any())).thenReturn(PDF);
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

    @Test
    @DisplayName("the scientific format reaches the print view and gets the journal's running head")
    void scientificFormat() throws Exception {
        PdfSettings custom = new PdfSettings();
        custom.setPaperSize(PaperSize.LETTER);
        custom.setPageNumbers(false);
        custom.setJournalNames(List.of(new TextItem("Вестник", "bg")));
        when(settings.findById(PdfSettings.ID)).thenReturn(Optional.of(custom));
        when(gotenberg.render(anyString(), anyString(), any(), any())).thenReturn(PDF);

        handler.run(context(Map.of("ids", "12", "languages", "bg", "format", "scientific")));

        ArgumentCaptor<GotenbergClient.RenderOptions> options = ArgumentCaptor.forClass(GotenbergClient.RenderOptions.class);
        verify(gotenberg).render(eq("http://site:8443/article-print/12?lang=bg&format=scientific&mode=code"),
                anyString(), any(), options.capture());
        assertThat(options.getValue().paper()).isEqualTo(PaperSize.LETTER);
        assertThat(options.getValue().footerHtml()).isNull();
        assertThat(options.getValue().headerHtml()).contains("Вестник");
        assertThat(options.getValue().marginTop()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    @DisplayName("the AI mode is refused at the start when AI is no longer available")
    void llmRefusedWithoutAi() {
        when(access.refusalFor("test")).thenReturn(Optional.of(io.pacod.cms.studio.ai.AiRefusal.ORGANISATION_OFF));
        assertThatThrownBy(() -> handler.run(context(Map.of("ids", "12", "mode", "llm"))))
                .hasMessageContaining("AI is not available");
        verify(gotenberg, org.mockito.Mockito.never()).render(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("in the AI mode the abstract comes first; a gateway failure fails that item, unrendered")
    void llmEnrichesBeforeRendering() throws Exception {
        when(gotenberg.render(anyString(), anyString(), any(), any())).thenReturn(PDF);
        when(enrichments.findById(anyString())).thenReturn(Optional.empty());
        when(gateway.complete(any())).thenAnswer(invocation -> {
            AiGateway.Call call = invocation.getArgument(0);
            if (call.user().contains("Text")) {
                throw new AiGateway.AiException("The gateway answered 402");
            }
            return new AiGateway.Answer(new tools.jackson.databind.ObjectMapper().readTree(
                    "{\"abstract\":\"Резюме\",\"keywords\":[\"а\",\"б\"],\"runningTitle\":\"Кратко\"}"), 1500, "req_1");
        });
        JobContext context = context(Map.of("ids", "12", "mode", "llm"));

        handler.run(context);

        verify(enrichments).save(org.mockito.ArgumentMatchers.argThat(row -> "12:bg".equals(row.getId())
                && "Резюме".equals(row.getAbstractText())));
        verify(gotenberg).render(eq("http://site:8443/article-print/12?lang=bg&format=standard&mode=llm"), anyString(), any(), any());
        verify(gotenberg, org.mockito.Mockito.never()).render(contains("lang=de"), anyString(), any(), any());
        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("FAILED article 12 (de): The gateway answered 402");
        assertThat(context.progress().costMicros()).isEqualTo(1500);
    }
}
