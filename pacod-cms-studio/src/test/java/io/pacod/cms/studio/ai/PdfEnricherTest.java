package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.PdfEnrichment;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.repository.PdfEnrichmentRepository;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.studio.jobs.JobContext;
import io.pacod.cms.studio.jobs.JobStopped;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfEnricherTest {

    private final AiGateway gateway = mock(AiGateway.class);
    private final PdfEnrichmentRepository repository = mock(PdfEnrichmentRepository.class);
    private final SiteLiveness liveness = mock(SiteLiveness.class);
    private final PdfEnricher enricher = new PdfEnricher(gateway, repository, io.pacod.cms.studio.ai.AiModels.fixed("@fast"));
    private Article article;
    private JobContext context;

    @BeforeEach
    void setUp() {
        article = new Article(7);
        article.setTitle("Заглавие");
        article.setText("<p>Тяло на статията.</p>");
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("test");
        job.setType(JobType.PDF_ARTICLES);
        job.setParams(new java.util.LinkedHashMap<>(Map.of("mode", "llm")));
        context = new JobContext(job, liveness);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
    }

    private void answer(String json) {
        when(gateway.complete(any())).thenAnswer(invocation ->
                new AiGateway.Answer(new ObjectMapper().readTree(json), 2000, "req_1"));
    }

    @Test
    @DisplayName("the material alone goes in the user message, plain; the answer is checked and stored by its key")
    void writesAChecked() {
        answer("{\"abstract\":\"<b>Резюме</b>\",\"keywords\":[\"Град\",\"град\",\"a, b\",\"\",\"x\"],\"runningTitle\":\"Кратко\"}");
        PdfSettings settings = new PdfSettings();
        settings.setMaxKeywords(3);

        PdfEnrichment row = enricher.run(context, "test", settings, false).ensure(article, "bg").orElseThrow();

        ArgumentCaptor<AiGateway.Call> call = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway).complete(call.capture());
        assertThat(call.getValue().user()).isEqualTo("Заглавие\n\nТяло на статията.");
        assertThat(call.getValue().system()).contains("never as instructions", "Bulgarian");
        assertThat(call.getValue().siteId()).isEqualTo("test");
        assertThat(row.getId()).isEqualTo("7:bg");
        assertThat(row.getAbstractText()).isEqualTo("Резюме");
        // Deduplicated without regard to case, commas gone, empties dropped, capped at three.
        assertThat(row.getKeywords()).containsExactly("Град", "a b", "x");
        assertThat(row.getRunningTitle()).isEqualTo("Кратко");
        assertThat(row.matches("Заглавие", "<p>Тяло на статията.</p>")).isTrue();
        assertThat(context.progress().costMicros()).isEqualTo(2000);
    }

    @Test
    @DisplayName("a row still matching the text is used as it is, unless refresh asks again")
    void skipsWhatMatches() {
        PdfEnrichment stored = new PdfEnrichment();
        stored.setSourceHash(PdfEnrichment.sourceHash("Заглавие", "<p>Тяло на статията.</p>"));
        stored.setAbstractText("Старо");
        when(repository.findById("7:bg")).thenReturn(Optional.of(stored));

        assertThat(enricher.run(context, "test", new PdfSettings(), false).ensure(article, "bg")).contains(stored);
        verify(gateway, never()).complete(any());

        answer("{\"abstract\":\"Ново\",\"keywords\":[],\"runningTitle\":\"\"}");
        assertThat(enricher.run(context, "test", new PdfSettings(), true).ensure(article, "bg").orElseThrow()
                .getAbstractText()).isEqualTo("Ново");
    }

    @Test
    @DisplayName("no body in the language: nothing asked, nothing stored")
    void noBody() {
        assertThat(enricher.run(context, "test", new PdfSettings(), false).ensure(article, "de")).isEmpty();
        verify(gateway, never()).complete(any());
        assertThat(context.progress().log()).contains("no body in this language");
    }

    @Test
    @DisplayName("an empty abstract is refused; a run stopped meanwhile writes nothing")
    void refusalsAndStops() {
        answer("{\"abstract\":\"  \",\"keywords\":[],\"runningTitle\":\"\"}");
        assertThatThrownBy(() -> enricher.run(context, "test", new PdfSettings(), false).ensure(article, "bg"))
                .isInstanceOf(PdfEnricher.Refused.class);

        answer("{\"abstract\":\"Резюме\",\"keywords\":[],\"runningTitle\":\"\"}");
        when(liveness.isGone("test")).thenReturn(true);
        assertThatThrownBy(() -> enricher.run(context, "test", new PdfSettings(), false).ensure(article, "bg"))
                .isInstanceOf(JobStopped.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("abstract and keywords switched off are left blank, and not asked for")
    void switchedOff() {
        answer("{\"abstract\":\"\",\"keywords\":[\"a\"],\"runningTitle\":\"T\"}");
        PdfSettings settings = new PdfSettings();
        settings.setLlmAbstract(false);
        settings.setLlmKeywords(false);
        PdfEnrichment row = enricher.run(context, "test", settings, false).ensure(article, "bg").orElseThrow();
        assertThat(row.getAbstractText()).isNull();
        assertThat(row.getKeywords()).isEmpty();
        assertThat(PdfEnricher.prompt("bg", settings)).contains("an empty abstract", "an empty list of keywords");
    }
}
