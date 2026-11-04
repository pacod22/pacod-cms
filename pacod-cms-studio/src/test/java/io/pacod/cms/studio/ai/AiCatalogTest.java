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

        // What a feature cannot use is still listed, with why: the list is whole, the offer is not.
        assertThat(c.unusableFor(AiFeature.TRANSLATE)).containsExactly(
                new AiCatalog.Unusable("local/no-json", "local/no-json", false, "no_json"));
        assertThat(c.unusableFor(AiFeature.VISION)).containsExactly(
                new AiCatalog.Unusable("@cheap", "@cheap", true, "no_images"),
                new AiCatalog.Unusable("local/no-json", "local/no-json", false, "no_json"),
                new AiCatalog.Unusable("local/text-only", "Text only (local/text-only)", false, "no_images"));
        assertThat(c.unusableForDefault()).isEqualTo(c.unusableFor(AiFeature.TRANSLATE));
        // Every chat model and template appears once for every feature, offered or not.
        for (AiFeature f : AiFeature.values()) {
            assertThat(c.offeredFor(f).size() + c.unusableFor(f).size()).isEqualTo(c.models().size() + c.templates().size());
        }
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

    @Test
    @DisplayName("a model's limits and the parameters it takes come with the list; hostile values read as absent")
    void modelDetails() throws Exception {
        AiCatalog.Catalog c = AiCatalog.parse(fixture());
        AiCatalog.ModelOption text = c.model("local/text-only").orElseThrow();
        assertThat(text.contextWindow()).isEqualTo(8192);
        assertThat(text.maxOutputTokens()).isEqualTo(4096);
        assertThat(text.temperature()).isEqualTo("rejected");
        assertThat(text.refusesTemperature()).isTrue();
        assertThat(text.takesNoEffort()).isTrue();
        assertThat(text.sunsetAt()).isEqualTo("2027-01-31");
        assertThat(text.successor()).isEqualTo("openai/gpt-dev");

        AiCatalog.ModelOption hostile = c.model("local/no-json").orElseThrow();
        assertThat(hostile.contextWindow()).isNull();
        assertThat(hostile.maxOutputTokens()).isNull();
        assertThat(hostile.temperature()).isNull();
        assertThat(hostile.efforts()).containsExactly("low", "high");
        assertThat(hostile.sunsetAt()).isNull();
        assertThat(hostile.successor()).isNull();
        assertThat(c.efforts()).containsExactly("low", "high");

        // The dev gateway's own entries say nothing of either: nothing is invented.
        AiCatalog.ModelOption real = c.model("openai/gpt-dev").orElseThrow();
        assertThat(real.maxOutputTokens()).isNull();
        assertThat(real.refusesTemperature()).isFalse();
        assertThat(real.takesNoEffort()).isFalse();
        assertThat(c.poolOf("@cheap")).extracting(AiCatalog.ModelOption::id).containsExactly("local/text-only");
        assertThat(c.poolOf("openai/gpt-dev")).isEmpty();
    }

    @Test
    @DisplayName("refresh asks the gateway now and renews the cache; a template is asked once a minute and forgotten with the list")
    void refreshAndTemplates() throws Exception {
        AiGateway gateway = mock(AiGateway.class);
        when(gateway.target()).thenReturn(new AiGatewayConfig.Effective("http://gw-a", "k1", AiGatewayConfig.Source.ENVIRONMENT, null, null));
        when(gateway.listModels()).thenReturn(fixture());
        when(gateway.templateSpec("@fast")).thenReturn(AiTemplateDetailsTest.fixture());
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T10:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        AiCatalog catalog = new AiCatalog(gateway, clock);

        catalog.lookup();
        catalog.refresh();
        catalog.lookup();
        verify(gateway, times(2)).listModels();

        assertThat(catalog.template("@fast").details().maxOutputTokens()).isEqualTo(20480);
        catalog.template("@fast");
        verify(gateway, times(1)).templateSpec("@fast");
        catalog.refresh();
        catalog.template("@fast");
        verify(gateway, times(2)).templateSpec("@fast");
        now.set(now.get().plus(AiCatalog.TTL).plusSeconds(1));
        catalog.template("@fast");
        verify(gateway, times(3)).templateSpec("@fast");
        catalog.forget();
        catalog.template("@fast");
        verify(gateway, times(4)).templateSpec("@fast");

        when(gateway.templateSpec("@cheap")).thenThrow(new AiGateway.AiException("The AI gateway answered 404"));
        AiCatalog.TemplateLookup failed = catalog.template("@cheap");
        assertThat(failed.details()).isNull();
        assertThat(failed.error()).contains("404");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a model keeps the pacod-broker resource the gateway links it to, and nothing that is not one")
    void brokerResource() {
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        AiCatalog.Catalog c = AiCatalog.parse(json.readTree("""
                {"models": [
                  {"id": "openai/linked", "kind": "chat", "state": "active", "broker_resource_id": "res_0a1b2c3d"},
                  {"id": "openai/bad", "kind": "chat", "state": "active", "broker_resource_id": "../admin"},
                  {"id": "openai/none", "kind": "chat", "state": "active"}
                ], "templates": []}
                """));
        assertThat(c.model("openai/linked").orElseThrow().brokerResourceId()).isEqualTo("res_0a1b2c3d");
        assertThat(c.model("openai/bad").orElseThrow().brokerResourceId()).isNull();
        assertThat(c.model("openai/none").orElseThrow().brokerResourceId()).isNull();
    }
}
