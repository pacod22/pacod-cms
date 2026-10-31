package io.pacod.cms.studio.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The gateway's list, as pacod-gw writes it ({@code ai/gw-models.json}, the dev gateway's answer plus hostile entries). */
public class AiCatalogTest {

    public static JsonNode fixture() throws Exception {
        try (InputStream in = AiCatalogTest.class.getResourceAsStream("/ai/gw-models.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("callable chat models and templates only; a hostile id is dropped")
    void parses() throws Exception {
        AiCatalog.Catalog c = AiCatalog.parse(fixture());
        assertThat(c.models()).extracting(AiCatalog.ModelOption::id)
                .containsExactly("anthropic/claude-dev", "openai/gpt-dev", "local/text-only", "local/no-json");
        assertThat(c.model("local/text-only").orElseThrow().deprecated()).isTrue();
        assertThat(c.model("local/text-only").orElseThrow().displayName()).isEqualTo("Text only");
        assertThat(c.templates()).extracting(AiCatalog.TemplateOption::id).containsExactly("@fast", "@cheap");
        assertThat(c.template("@fast").orElseThrow().poolModels()).containsExactly("openai/gpt-dev", "anthropic/claude-dev");
    }

    @Test
    @DisplayName("each feature is offered what it can use: a JSON answer always, images for alt text")
    void suitability() throws Exception {
        AiCatalog.Catalog c = AiCatalog.parse(fixture());
        assertThat(c.offeredFor(AiFeature.TRANSLATE)).extracting(AiCatalog.Choice::id)
                .containsExactly("@cheap", "@fast", "anthropic/claude-dev", "local/text-only", "openai/gpt-dev");
        // No vision: the text-only model, and the template whose visible pool holds it.
        assertThat(c.offeredFor(AiFeature.VISION)).extracting(AiCatalog.Choice::id)
                .containsExactly("@fast", "anthropic/claude-dev", "openai/gpt-dev");
        assertThat(c.suits("local/no-json", AiFeature.TRANSLATE)).isFalse();
        assertThat(c.has("local/no-json")).isTrue();
        assertThat(c.has("old/retired")).isFalse();
        // A pool model the key cannot see is the gateway's to route.
        assertThat(c.suits("@cheap", AiFeature.PDF)).isTrue();
        assertThat(c.offeredForDefault()).isEqualTo(c.offeredFor(AiFeature.TRANSLATE));
        assertThat(c.offeredFor(AiFeature.TRANSLATE)).filteredOn(AiCatalog.Choice::deprecated)
                .extracting(AiCatalog.Choice::id).containsExactly("local/text-only");
    }

    @Test
    @DisplayName("cached a minute per gateway; a new address or key is asked at once; a failure says why")
    void caching() throws Exception {
        AiGateway gateway = mock(AiGateway.class);
        AtomicReference<AiGatewayConfig.Effective> target = new AtomicReference<>(
                new AiGatewayConfig.Effective("http://gw-a", "k1", AiGatewayConfig.Source.ENVIRONMENT, null, null));
        when(gateway.target()).thenAnswer(i -> target.get());
        when(gateway.listModels()).thenReturn(fixture());
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T10:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        AiCatalog catalog = new AiCatalog(gateway, clock);

        assertThat(catalog.current()).isPresent();
        catalog.current();
        verify(gateway, times(1)).listModels();
        target.set(new AiGatewayConfig.Effective("http://gw-a", "k2", AiGatewayConfig.Source.STORED, null, null));
        catalog.current();
        verify(gateway, times(2)).listModels();
        now.set(now.get().plus(AiCatalog.TTL).plusSeconds(1));
        catalog.current();
        verify(gateway, times(3)).listModels();

        when(gateway.listModels()).thenThrow(new AiGateway.AiException("The AI gateway refused the key"));
        catalog.forget();
        AiCatalog.Lookup failed = catalog.lookup();
        assertThat(failed.found()).isEmpty();
        assertThat(failed.error()).contains("refused the key");
    }
}
