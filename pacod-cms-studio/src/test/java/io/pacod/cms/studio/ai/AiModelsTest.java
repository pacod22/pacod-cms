package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.AiModelSettings;
import io.pacod.cms.core.control.repository.AiModelSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC);

    private final AtomicReference<AiModelSettings> held = new AtomicReference<>();
    private AiModelSettingsRepository repository;
    private AiCatalog catalog;
    private AiModels models;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(AiModelSettingsRepository.class);
        when(repository.findById(AiModelSettings.ID)).thenAnswer(i -> Optional.ofNullable(held.get()));
        when(repository.save(any())).thenAnswer(i -> {
            held.set(i.getArgument(0));
            return i.getArgument(0);
        });
        doAnswer(i -> {
            held.set(null);
            return null;
        }).when(repository).deleteById(AiModelSettings.ID);
        catalog = mock(AiCatalog.class);
        when(catalog.lookup()).thenReturn(new AiCatalog.Lookup(AiCatalog.parse(AiCatalogTest.fixture()), null));
        models = new AiModels("@fast", Map.of(AiFeature.VISION, "env/vision"), repository, catalog, CLOCK);
    }

    private static Map<AiFeature, String> features(Object... pairs) {
        Map<AiFeature, String> out = new EnumMap<>(AiFeature.class);
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((AiFeature) pairs[i], (String) pairs[i + 1]);
        }
        return out;
    }

    private String refusal(AiModels.Change change) {
        try {
            models.set(change, "usr_admin");
        } catch (AiGatewayConfig.Refused e) {
            return e.code() + ":" + e.field();
        }
        return null;
    }

    @Test
    @DisplayName("nothing stored: the feature's property, else pacod.ai.model")
    void environment() {
        assertThat(models.resolve(AiFeature.TRANSLATE)).isEqualTo(new AiModels.Resolved("@fast", AiModels.Source.ENVIRONMENT));
        assertThat(models.modelFor(AiFeature.VISION)).isEqualTo("env/vision");
        assertThat(models.defaultModel()).isEqualTo("@fast");
        assertThat(AiModels.fixed(" ").defaultModel()).isEqualTo(AiModels.DEFAULT_MODEL);
    }

    @Test
    @DisplayName("a stored default replaces every property; an override replaces the default for its feature")
    void storedOverrides() {
        models.set(new AiModels.Change("openai/gpt-dev", features(AiFeature.VISION, "anthropic/claude-dev")), "usr_admin");
        assertThat(models.resolve(AiFeature.TRANSLATE)).isEqualTo(new AiModels.Resolved("openai/gpt-dev", AiModels.Source.STORED_DEFAULT));
        assertThat(models.resolve(AiFeature.VISION)).isEqualTo(new AiModels.Resolved("anthropic/claude-dev", AiModels.Source.STORED_FEATURE));
        assertThat(models.defaultModel()).isEqualTo("openai/gpt-dev");
        assertThat(held.get().getFeatures()).containsExactly(Map.entry("vision", "anthropic/claude-dev"));
        assertThat(models.stored().updatedBy()).isEqualTo("usr_admin");

        // A blank override clears it; a null default keeps it.
        models.set(new AiModels.Change(null, features(AiFeature.VISION, "")), "usr_admin");
        assertThat(models.resolve(AiFeature.VISION)).isEqualTo(new AiModels.Resolved("openai/gpt-dev", AiModels.Source.STORED_DEFAULT));
        // A blank default with no overrides left removes the document.
        models.set(new AiModels.Change("", Map.of()), "usr_admin");
        assertThat(held.get()).isNull();
        assertThat(models.modelFor(AiFeature.VISION)).isEqualTo("env/vision");
    }

    @Test
    @DisplayName("a choice the gateway does not offer, or that cannot do the feature's work, is refused by name")
    void validation() {
        assertThat(refusal(new AiModels.Change("unknown/model", Map.of()))).isEqualTo("not_offered:default");
        assertThat(refusal(new AiModels.Change("local/no-json", Map.of()))).isEqualTo("not_suitable:default");
        assertThat(refusal(new AiModels.Change(null, features(AiFeature.VISION, "local/text-only")))).isEqualTo("not_suitable:vision");
        // A default without images would hand alt text a model that cannot look: named by the feature.
        assertThat(refusal(new AiModels.Change("local/text-only", Map.of()))).isEqualTo("default_not_suitable:vision");
        assertThat(refusal(new AiModels.Change(null, Map.of()))).isEqualTo("unchanged:default");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("only what changes is checked: a choice the gateway has since dropped does not block another save")
    void staleEntryDoesNotBlock() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("pdf", "gone/model");
        AiModelSettings doc = new AiModelSettings();
        doc.setFeatures(stored);
        held.set(doc);

        models.set(new AiModels.Change(null, features(AiFeature.PDF, "gone/model", AiFeature.TRANSLATE, "@fast")), "usr_admin");
        assertThat(models.modelFor(AiFeature.TRANSLATE)).isEqualTo("@fast");
        assertThat(models.modelFor(AiFeature.PDF)).isEqualTo("gone/model");
    }

    @Test
    @DisplayName("clearing an override hands the feature to the default, which must then suit it")
    void clearingAnOverrideChecksTheDefault() {
        AiModelSettings doc = new AiModelSettings();
        doc.setDefaultModel("local/text-only");
        doc.setFeatures(new LinkedHashMap<>(Map.of("vision", "openai/gpt-dev")));
        held.set(doc);
        assertThat(refusal(new AiModels.Change(null, features(AiFeature.VISION, "")))).isEqualTo("default_not_suitable:vision");
    }

    @Test
    @DisplayName("without the gateway's list nothing new is chosen, but going back to the environment always works")
    void noCatalogue() {
        when(catalog.lookup()).thenReturn(new AiCatalog.Lookup(null, "unreachable"));
        assertThat(refusal(new AiModels.Change("@fast", Map.of()))).isEqualTo("catalogue_unavailable:default");

        AiModelSettings doc = new AiModelSettings();
        doc.setDefaultModel("openai/gpt-dev");
        held.set(doc);
        models.clear();
        assertThat(held.get()).isNull();
        assertThat(models.defaultModel()).isEqualTo("@fast");
    }

    @Test
    @DisplayName("an unreadable control plane falls back to the properties")
    void readFailure() {
        when(repository.findById(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(models.resolve(AiFeature.PDF)).isEqualTo(new AiModels.Resolved("@fast", AiModels.Source.ENVIRONMENT));
    }

    @Test
    @DisplayName("a fixed set of models cannot be changed")
    void fixed() {
        assertThatThrownBy(() -> AiModels.fixed("@fast").set(new AiModels.Change("x", Map.of()), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
