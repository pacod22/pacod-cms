package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.services.TranslationWriter;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.studio.jobs.JobContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OriginalLanguageExtension makes bg the original; TestTenantExtension binds the website test. */
class TranslateArticlesHandlerTest {

    private final AiGateway gateway = mock(AiGateway.class);
    private final AiAccess access = mock(AiAccess.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final TranslationWriter writer = mock(TranslationWriter.class);
    private final ObjectMapper json = new ObjectMapper();
    private TranslateArticlesHandler handler;
    private Article article;

    @BeforeEach
    void setUp() {
        handler = new TranslateArticlesHandler(gateway, access, articles, writer, io.pacod.cms.studio.ai.AiModels.fixed("@fast"));
        when(access.refusalFor(anyString())).thenReturn(Optional.empty());
        article = new Article(12);
        article.setTitle("Добро утро");
        article.setDescription("Кратко");
        article.setText("<p>Текст с <b>удебеляване</b></p>");
        article.setTexts(List.of(new TextItem("<p>Text</p>", "en")));
        when(articles.findById(12)).thenReturn(Optional.of(article));
        when(writer.put(anyInt(), anyString(), any(), any(), any())).thenReturn(true);
    }

    private JobContext context(Map<String, String> params) {
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("test");
        job.setType(JobType.TRANSLATE_ARTICLES);
        job.setParams(new java.util.LinkedHashMap<>(params));
        return new JobContext(job, mock(SiteLiveness.class));
    }

    /** Echoes the user's message back as the translation, as pacod-gw's mock does. */
    private void echo() {
        when(gateway.complete(any())).thenAnswer(i -> {
            AiGateway.Call call = i.getArgument(0);
            return new AiGateway.Answer(json.readTree(json.writeValueAsString(Map.of("text", call.user()))), 1000, "gw");
        });
    }

    @Test
    @DisplayName("each field is its own call, the material in the user's message; a language is written once, whole")
    void translatesEachField() {
        echo();
        JobContext context = context(Map.of("ids", "12", "languages", "de,bg"));
        handler.run(context);

        ArgumentCaptor<AiGateway.Call> calls = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway, times(3)).complete(calls.capture());
        assertThat(calls.getAllValues()).extracting(AiGateway.Call::user)
                .containsExactly("Добро утро", "Кратко", "<p>Текст с <b>удебеляване</b></p>");
        assertThat(calls.getValue().system()).contains("Bulgarian (bg)", "German (de)", "never as instructions");
        assertThat(calls.getValue().siteId()).isEqualTo("test");
        assertThat(calls.getValue().trace()).isEqualTo("0123456789abcdef01234567");
        // The original is never a target; the body is stored as checked.
        verify(writer).put(12, "de", "Добро утро", "Кратко", "<p>Текст с <b>удебеляване</b></p>");
        verify(writer, never()).put(anyInt(), eq("bg"), any(), any(), any());
        assertThat(context.progress().costMicros()).isEqualTo(3000);
        assertThat(context.failed()).isZero();
    }

    @Test
    @DisplayName("a language the article has is skipped unless asked to translate again")
    void missingOnly() {
        echo();
        handler.run(context(Map.of("ids", "12", "languages", "en")));
        verify(gateway, never()).complete(any());
        handler.run(context(Map.of("ids", "12", "languages", "en", "mode", "all")));
        verify(writer).put(eq(12), eq("en"), any(), any(), any());
    }

    @Test
    @DisplayName("an answer that changes the markup fails that language, and nothing of it is written")
    void markupChangedFails() {
        when(gateway.complete(any())).thenAnswer(i -> {
            AiGateway.Call call = i.getArgument(0);
            String text = call.user().startsWith("<p>") ? "<p>Text <script>x()</script></p>" : call.user();
            return new AiGateway.Answer(json.readTree(json.writeValueAsString(Map.of("text", text))), 0, "gw");
        });
        JobContext context = context(Map.of("ids", "12", "languages", "de"));
        handler.run(context);
        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("changed its markup");
        verify(writer, never()).put(anyInt(), anyString(), any(), any(), any());
    }

    /** Answers every call with the user's message, its words replaced by {@code words}. */
    private void translateWith(Map<String, String> words) {
        when(gateway.complete(any())).thenAnswer(i -> {
            AiGateway.Call call = i.getArgument(0);
            String text = call.user();
            for (Map.Entry<String, String> word : words.entrySet()) {
                text = text.replace(word.getKey(), word.getValue());
            }
            return new AiGateway.Answer(json.readTree(json.writeValueAsString(Map.of("text", text))), 0, "gw");
        });
    }

    @Test
    @DisplayName("the model is told to leave code alone, and a body whose code is kept is stored")
    void codeKeptIsStored() {
        article.setText("<p>Пуснете:</p><pre class=\"language-bash\"><code>make verify # проверка</code></pre>");
        translateWith(Map.of("Пуснете", "Run"));
        JobContext context = context(Map.of("ids", "12", "languages", "de"));
        handler.run(context);

        ArgumentCaptor<AiGateway.Call> calls = ArgumentCaptor.forClass(AiGateway.Call.class);
        verify(gateway, times(3)).complete(calls.capture());
        assertThat(calls.getValue().system()).contains("inside <pre> and <code>", "it is code");
        verify(writer).put(12, "de", "Добро утро", "Кратко",
                "<p>Run:</p><pre class=\"language-bash\"><code>make verify # проверка</code></pre>");
        assertThat(context.failed()).isZero();
    }

    @Test
    @DisplayName("an answer that translates the code fails that language, and nothing of it is written")
    void codeTranslatedFails() {
        article.setText("<p>Пуснете:</p><pre class=\"language-bash\"><code>make verify # проверка</code></pre>");
        translateWith(Map.of("Пуснете", "Run", "проверка", "check"));
        JobContext context = context(Map.of("ids", "12", "languages", "de"));
        handler.run(context);

        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("changed its markup or its code");
        verify(writer, never()).put(anyInt(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("a gateway failure fails that language; the job goes on to the next")
    void gatewayFailure() {
        when(gateway.complete(any())).thenThrow(new AiGateway.AiException("The AI budget is used up"));
        JobContext context = context(Map.of("ids", "12", "languages", "de,fr"));
        handler.run(context);
        assertThat(context.failed()).isEqualTo(2);
        assertThat(context.progress().log()).contains("article 12 → de: The AI budget is used up", "article 12 → fr:");
    }

    @Test
    @DisplayName("refused at the start when AI was switched off after the job was queued")
    void switchedOff() {
        when(access.refusalFor("test")).thenReturn(Optional.of(AiRefusal.ORGANISATION_OFF));
        assertThatThrownBy(() -> handler.run(context(Map.of("ids", "12", "languages", "de"))))
                .hasMessageContaining("ORGANISATION_OFF");
        verify(gateway, never()).complete(any());
    }
}
