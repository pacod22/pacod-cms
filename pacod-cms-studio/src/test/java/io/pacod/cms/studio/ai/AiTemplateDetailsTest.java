package io.pacod.cms.studio.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** One template as pacod-gw answers {@code GET /gw/v1/models/@name} ({@code ai/gw-template.json}), and hostile ones. */
public class AiTemplateDetailsTest {

    public static JsonNode fixture() throws Exception {
        try (InputStream in = AiTemplateDetailsTest.class.getResourceAsStream("/ai/gw-template.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("the spec's values, locks and omissions; the pool from the spec with pacod-gw's defaults filled in")
    void parses() throws Exception {
        AiTemplateDetails d = AiTemplateDetails.parse("@fast", fixture());
        assertThat(d.version()).isEqualTo(3);
        assertThat(d.model()).isEqualTo("openai/gpt-dev");
        assertThat(d.pool()).containsExactly(new AiTemplateDetails.PoolEntry("openai/gpt-dev", null, 3, 0),
                new AiTemplateDetails.PoolEntry("anthropic/claude-dev", null, 1, 1));
        assertThat(d.routingScheme()).isEqualTo("pool-fast");
        assertThat(d.maxOutputTokens()).isEqualTo(20480);
        assertThat(d.temperature()).isEqualTo(new AiTemplateDetails.Param("0.2", true, false));
        assertThat(d.temperature().overridesCaller()).isTrue();
        assertThat(d.topP()).isNull();
        assertThat(d.presencePenalty()).isEqualTo(new AiTemplateDetails.Param(null, false, true));
        assertThat(d.reasoningEffort()).isEqualTo(new AiTemplateDetails.Param("high", false, false));
        assertThat(d.reasoningEffort().overridesCaller()).isFalse();
        assertThat(d.reasoningBudget()).isNull();
        assertThat(d.timeoutSeconds()).isEqualTo(120);
        assertThat(d.retryMaxAttempts()).isEqualTo(2);
        assertThat(d.promptCache()).isEqualTo("passthrough");
        assertThat(d.unsupportedParams()).isNull();
    }

    @Test
    @DisplayName("an answer is untrusted: out of range, odd strings and an oversized pool read as absent or capped")
    void hostile() throws Exception {
        StringBuilder pool = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            pool.append(i == 0 ? "" : ",").append("{\"model\":\"m").append(i).append("\",\"weight\":-4,\"priority\":999}");
        }
        JsonNode answer = new ObjectMapper().readTree("{\"version\":\"3\",\"model\":\"evil\\\"><script>\","
                + "\"spec\":{\"models\":[" + pool + ",{\"model\":\"<b>\"}],\"max_output_tokens\":-1,"
                + "\"temperature\":{\"value\":7,\"locked\":true},\"top_p\":{\"value\":\"0.9\"},"
                + "\"reasoning\":{\"effort\":{\"value\":\"<script>\"},\"budget_tokens\":{\"value\":1.5},\"summary\":{\"value\":\"yes\"}},"
                + "\"timeout_seconds\":6000,\"retry_max_attempts\":0,\"prompt_cache\":\"all\",\"unsupported_params\":\"explode\"}}");
        AiTemplateDetails d = AiTemplateDetails.parse("@x", answer);
        assertThat(d.version()).isNull();
        assertThat(d.model()).isNull();
        assertThat(d.pool()).hasSize(AiTemplateDetails.MAX_POOL)
                .allSatisfy(e -> assertThat(e.weight()).isEqualTo(1)).allSatisfy(e -> assertThat(e.priority()).isZero());
        assertThat(d.maxOutputTokens()).isNull();
        assertThat(d.temperature()).isNull();
        assertThat(d.topP()).isNull();
        assertThat(d.reasoningEffort()).isNull();
        assertThat(d.reasoningBudget()).isNull();
        assertThat(d.reasoningSummary()).isNull();
        assertThat(d.timeoutSeconds()).isNull();
        assertThat(d.retryMaxAttempts()).isNull();
        assertThat(d.promptCache()).isNull();
        assertThat(d.unsupportedParams()).isNull();

        // The lite gateway's pools carry no spec worth the name: nothing but the pool.
        AiTemplateDetails lite = AiTemplateDetails.parse("@fast", new ObjectMapper().readTree(
                "{\"id\":\"@fast\",\"models\":[{\"model\":\"openai/gpt-dev\"}],\"spec\":{\"temperature\":null,\"reasoning\":{}}}"));
        assertThat(lite.pool()).extracting(AiTemplateDetails.PoolEntry::model).containsExactly("openai/gpt-dev");
        assertThat(lite.temperature()).isNull();
        assertThat(lite.maxOutputTokens()).isNull();
    }
}
