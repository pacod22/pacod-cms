package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.IssueRendition;
import io.pacod.cms.core.data.IssueStatus;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PdfEnrichmentRepository;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.studio.MgmtIssueService;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.PdfEnricher;
import io.pacod.cms.studio.jobs.JobContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TestTenantExtension binds {@code test}; Gotenberg is a stand-in answering PDFs made here: two
 * pages an article, and a front matter that grows from one page to two once it prints numbers.
 */
class PdfIssueHandlerTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-0123456789";

    @TempDir
    Path storage;

    private final GotenbergClient gotenberg = mock(GotenbergClient.class);
    private final DomainRepository domains = mock(DomainRepository.class);
    private final WebsiteRepository websites = mock(WebsiteRepository.class);
    private final JournalIssueRepository issues = mock(JournalIssueRepository.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final PdfSettingsRepository settings = mock(PdfSettingsRepository.class);
    private final MgmtIssueService issueService = mock(MgmtIssueService.class);
    private final AiAccess access = mock(AiAccess.class);
    private JournalIssue issue;
    private PdfIssueHandler handler;

    static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        handler = new PdfIssueHandler(new PdfRenderer(gotenberg, new PrintTokens(SECRET), domains, websites, "http://cms-site:8443"),
                issues, articles, settings, issueService,
                new PdfEnricher(mock(AiGateway.class), mock(PdfEnrichmentRepository.class), "@fast"), access);
        when(domains.findByWebsiteId("test")).thenReturn(List.of(new Domain("www.test.example", "test", true)));
        when(websites.findById("test")).thenReturn(Optional.of(new Website("test", "org_t", "Demo Journal")));
        when(settings.findById(PdfSettings.ID)).thenReturn(Optional.empty());
        issue = new JournalIssue();
        issue.setId(3);
        issue.setVolume(2);
        issue.setNumber(5);
        issue.setYear(2026);
        issue.setMonth(10);
        issue.setArticleIds(List.of(12, 13, 14));
        when(issues.findById(3)).thenReturn(Optional.of(issue));
        Article first = published(12);
        Article draft = new Article(13);
        draft.setStatus(ArticleStatus.DRAFT);
        when(articles.findArticleByIdIn(List.of(12, 13, 14))).thenReturn(List.of(published(14), draft, first));
        when(issueService.draftPdf(eq(3), anyString())).thenAnswer(i -> storage.resolve("private/issue-" + i.getArgument(1) + ".pdf"));
        when(issueService.publicPdf(eq(3), anyString())).thenAnswer(i -> storage.resolve("public/issue-" + i.getArgument(1) + ".pdf"));

        byte[] article = pdf(2);
        byte[] shortFront = pdf(1);
        byte[] longFront = pdf(2);
        AtomicInteger fronts = new AtomicInteger();
        when(gotenberg.render(contains("/article-print/"), anyString(), any(), any())).thenReturn(article);
        when(gotenberg.render(contains("/issue-print/"), anyString(), any(), any()))
                .thenAnswer(i -> fronts.incrementAndGet() == 1 ? shortFront : longFront);
    }

    private static Article published(int id) {
        Article article = new Article(id);
        article.setTitle("Статия " + id);
        article.setStatus(ArticleStatus.PUBLISHED);
        return article;
    }

    private static JobContext context(Map<String, String> params) {
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("test");
        job.setType(JobType.PDF_ISSUE);
        job.setParams(new java.util.LinkedHashMap<>(params));
        return new JobContext(job, mock(SiteLiveness.class));
    }

    @Test
    @DisplayName("articles in the issue's order, drafts left out; the contents settle on their length; numbered and recorded")
    void generates() throws Exception {
        JobContext context = context(Map.of("issue", "3", "format", "scientific"));
        handler.run(context);

        verify(gotenberg).render(eq("http://cms-site:8443/article-print/12?lang=bg&format=scientific&mode=code"), anyString(), any(), any());
        verify(gotenberg).render(eq("http://cms-site:8443/article-print/14?lang=bg&format=scientific&mode=code"), anyString(), any(), any());
        verify(gotenberg, never()).render(contains("/article-print/13"), anyString(), any(), any());
        // Guessed 2 → renders 1 page → recomputed for 1 → renders 2 → recomputed for 2 → holds.
        verify(gotenberg, org.mockito.Mockito.times(2)).render(eq("http://cms-site:8443/issue-print/3/front?lang=bg&ids=12,14&pages=3,5"), anyString(), any(), any());
        verify(gotenberg).render(eq("http://cms-site:8443/issue-print/3/front?lang=bg&ids=12,14&pages=2,4"), anyString(), any(), any());

        try (PDDocument document = Loader.loadPDF(storage.resolve("private/issue-bg.pdf").toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(6);
        }
        // A draft issue: nothing public.
        assertThat(storage.resolve("public/issue-bg.pdf")).doesNotExist();
        ArgumentCaptor<IssueRendition> rendition = ArgumentCaptor.forClass(IssueRendition.class);
        verify(issueService).recordRendition(eq(3), eq("bg"), rendition.capture());
        assertThat(rendition.getValue().getPages()).isEqualTo(6);
        assertThat(context.progress().log()).contains("article 13 is not published; left out.", "issue (bg): 6 pages");
        assertThat(context.progress().done()).isEqualTo(context.progress().total()).isEqualTo(3);
    }

    @Test
    @DisplayName("a published issue's PDF goes public too; a failed article fails its language, nothing written")
    void publishedAndFailing() throws Exception {
        issue.setStatus(IssueStatus.PUBLISHED);
        issue.setLanguages(List.of("bg", "en"));
        when(gotenberg.render(contains("12?lang=en"), anyString(), any(), any()))
                .thenThrow(new GotenbergClient.PdfException("Gotenberg answered 503"));
        JobContext context = context(Map.of("issue", "3"));
        handler.run(context);

        assertThat(storage.resolve("public/issue-bg.pdf")).exists();
        assertThat(storage.resolve("private/issue-en.pdf")).doesNotExist();
        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("FAILED issue (en): Gotenberg answered 503");
        verify(issueService, never()).recordRendition(eq(3), eq("en"), any());
        // The temporary pieces are gone either way.
        try (var temp = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(temp.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("pacod-issue-")).toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("no issue, an issue of drafts only, or AI off in the AI mode: refused before any render")
    void refusals() {
        assertThatThrownBy(() -> handler.run(context(Map.of("issue", "x")))).isInstanceOf(IllegalArgumentException.class);
        when(articles.findArticleByIdIn(any())).thenReturn(List.of());
        assertThatThrownBy(() -> handler.run(context(Map.of("issue", "3")))).hasMessageContaining("no published article");
        when(access.refusalFor("test")).thenReturn(Optional.of(io.pacod.cms.studio.ai.AiRefusal.NOT_SET_UP));
        assertThatThrownBy(() -> handler.run(context(Map.of("issue", "3", "mode", "llm")))).hasMessageContaining("AI is not available");
        verify(gotenberg, never()).render(anyString(), anyString(), any(), any());
    }
}
