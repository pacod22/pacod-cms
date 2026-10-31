package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.AiModelSettings;
import io.pacod.cms.core.control.repository.AiModelSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which model or template each AI feature calls, resolved per call: the installation's stored
 * choice ({@link AiModelSettings}: the feature's override, else the default), else the feature's
 * property ({@code pacod.ai.models.<feature>}), else {@code pacod.ai.model} ({@code @fast}).
 * Chosen on the platform screen or through {@code /internal/v1/ai-models}, from what the gateway
 * offers ({@link AiCatalog}); applied from the next call on, no restart.
 *
 * <ul>
 *   <li><b>Only what changes is checked</b> against the catalogue, feature by feature: a stored
 *       choice the gateway has since dropped must not block an unrelated save (the screen posts
 *       every select). A feature whose model moves -- also indirectly, a cleared override handing
 *       it to the default -- needs the new one offered and suitable for it.</li>
 *   <li><b>{@link #clear} asks nobody</b>: going back to the properties must work while the
 *       gateway is down.</li>
 *   <li>Read fresh behind a {@link #MEMO}; an unreadable control plane falls back to the
 *       properties, logged at ERROR.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiModels {

    static final Duration MEMO = Duration.ofSeconds(5);
    public static final String DEFAULT_MODEL = "@fast";

    /** Where a feature's model comes from. */
    public enum Source { STORED_FEATURE, STORED_DEFAULT, ENVIRONMENT }

    public record Resolved(String model, Source source) {
    }

    /**
     * A change: {@code defaultModel} null keeps the stored default, blank clears it;
     * {@code features} names only the features to change, a blank value clearing the override.
     */
    public record Change(String defaultModel, Map<AiFeature, String> features) {
    }

    /** The stored choice as it stands, for a screen or an answer. */
    public record Stored(String defaultModel, Map<AiFeature, String> features, Instant updatedOn, String updatedBy) {
    }

    private final String envDefault;
    private final Map<AiFeature, String> envFeatures;
    private final AiModelSettingsRepository repository;
    private final AiCatalog catalog;
    private final Clock clock;
    private volatile Stored memo;
    private volatile Instant memoUntil = Instant.MIN;

    @Autowired
    public AiModels(Environment env, AiModelSettingsRepository repository, AiCatalog catalog) {
        this(env.getProperty("pacod.ai.model", DEFAULT_MODEL), properties(env), repository, catalog, Clock.systemUTC());
    }

    AiModels(String envDefault, Map<AiFeature, String> envFeatures, AiModelSettingsRepository repository,
             AiCatalog catalog, Clock clock) {
        this.envDefault = blank(envDefault) ? DEFAULT_MODEL : envDefault.strip();
        this.envFeatures = new EnumMap<>(AiFeature.class);
        envFeatures.forEach((f, m) -> {
            if (!blank(m)) {
                this.envFeatures.put(f, m.strip());
            }
        });
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** One model for every feature, nothing stored: for tests and hand-built consumers. */
    public static AiModels fixed(String model) {
        return new AiModels(model, Map.of(), null, null, Clock.systemUTC());
    }

    private static Map<AiFeature, String> properties(Environment env) {
        Map<AiFeature, String> out = new EnumMap<>(AiFeature.class);
        for (AiFeature f : AiFeature.values()) {
            String v = env.getProperty(f.property());
            if (!blank(v)) {
                out.put(f, v);
            }
        }
        return out;
    }

    /** The model {@code feature} calls now. */
    public String modelFor(AiFeature feature) {
        return resolve(feature).model();
    }

    public Resolved resolve(AiFeature feature) {
        Stored s = stored();
        String override = s.features().get(feature);
        if (!blank(override)) {
            return new Resolved(override, Source.STORED_FEATURE);
        }
        if (!blank(s.defaultModel())) {
            return new Resolved(s.defaultModel(), Source.STORED_DEFAULT);
        }
        return new Resolved(envFeatures.getOrDefault(feature, envDefault), Source.ENVIRONMENT);
    }

    /** The default in effect (the connection test's model). */
    public String defaultModel() {
        Stored s = stored();
        return blank(s.defaultModel()) ? envDefault : s.defaultModel();
    }

    /** {@code pacod.ai.model}, what a blank default falls back to. */
    public String environmentDefault() {
        return envDefault;
    }

    public Stored stored() {
        Instant now = clock.instant();
        Stored cached = memo;
        if (cached != null && now.isBefore(memoUntil)) {
            return cached;
        }
        Stored fresh = read();
        memo = fresh;
        memoUntil = now.plus(MEMO);
        return fresh;
    }

    private Stored read() {
        if (repository != null) {
            try {
                Optional<AiModelSettings> doc = repository.findById(AiModelSettings.ID);
                if (doc.isPresent()) {
                    return toStored(doc.get());
                }
            } catch (DataAccessException e) {
                log.error("Could not read the AI model settings, using the properties: {}", e.getMessage());
            }
        }
        return new Stored(null, Map.of(), null, null);
    }

    private static Stored toStored(AiModelSettings doc) {
        Map<AiFeature, String> features = new EnumMap<>(AiFeature.class);
        if (doc.getFeatures() != null) {
            doc.getFeatures().forEach((k, v) -> AiFeature.of(k).ifPresent(f -> {
                if (!blank(v)) {
                    features.put(f, v.strip());
                }
            }));
        }
        return new Stored(blank(doc.getDefaultModel()) ? null : doc.getDefaultModel().strip(), features,
                doc.getUpdatedOn(), doc.getUpdatedBy());
    }

    /**
     * Applies {@code change}, checking against the gateway's list what it moves.
     *
     * @throws AiGatewayConfig.Refused {@code unchanged}, {@code catalogue_unavailable},
     *                                 {@code not_offered}, {@code not_suitable}, or
     *                                 {@code default_not_offered}/{@code default_not_suitable}
     *                                 for a feature the default would serve; the field is the
     *                                 feature's key or {@code default}
     */
    public Stored set(Change change, String by) {
        if (repository == null) {
            throw new IllegalStateException("No settings store");
        }
        Stored before = read();
        String newDefault = change.defaultModel() == null ? before.defaultModel()
                : blank(change.defaultModel()) ? null : change.defaultModel().strip();
        Map<AiFeature, String> newFeatures = new EnumMap<>(AiFeature.class);
        newFeatures.putAll(before.features());
        if (change.features() != null) {
            change.features().forEach((f, m) -> {
                if (blank(m)) {
                    newFeatures.remove(f);
                } else {
                    newFeatures.put(f, m.strip());
                }
            });
        }
        if (Objects.equals(newDefault, before.defaultModel()) && newFeatures.equals(before.features())) {
            throw new AiGatewayConfig.Refused("unchanged", "default");
        }

        AiCatalog.Lookup lookup = null;
        if (newDefault != null && !newDefault.equals(before.defaultModel())) {
            lookup = catalog.lookup();
            check(lookup, newDefault, AiFeature.TRANSLATE, "default");
        }
        for (AiFeature f : AiFeature.values()) {
            String was = storedModel(before.defaultModel(), before.features(), f);
            String now = storedModel(newDefault, newFeatures, f);
            if (now != null && !now.equals(was)) {
                if (lookup == null) {
                    lookup = catalog.lookup();
                }
                // Named by the feature even when the default moved it, with its own code then: the
                // admin chose nothing for that feature, and must learn it needs a choice of its own.
                try {
                    check(lookup, now, f, f.key());
                } catch (AiGatewayConfig.Refused e) {
                    if (!newFeatures.containsKey(f) && !"catalogue_unavailable".equals(e.code())) {
                        throw new AiGatewayConfig.Refused("default_" + e.code(), f.key());
                    }
                    throw e;
                }
            }
        }

        if (newDefault == null && newFeatures.isEmpty()) {
            repository.deleteById(AiModelSettings.ID);
        } else {
            AiModelSettings doc = new AiModelSettings();
            doc.setDefaultModel(newDefault);
            Map<String, String> stored = new LinkedHashMap<>();
            newFeatures.forEach((f, m) -> stored.put(f.key(), m));
            doc.setFeatures(stored);
            doc.setUpdatedOn(clock.instant());
            doc.setUpdatedBy(by);
            repository.save(doc);
        }
        forget();
        return stored();
    }

    /** Forgets the stored choice: the properties apply again. Asks no gateway. */
    public void clear() {
        if (repository == null) {
            throw new IllegalStateException("No settings store");
        }
        repository.deleteById(AiModelSettings.ID);
        forget();
    }

    private static void check(AiCatalog.Lookup lookup, String model, AiFeature feature, String field) {
        Optional<AiCatalog.Catalog> c = lookup.found();
        if (c.isEmpty()) {
            throw new AiGatewayConfig.Refused("catalogue_unavailable", field);
        }
        if (!c.get().has(model)) {
            throw new AiGatewayConfig.Refused("not_offered", field);
        }
        if (!c.get().suits(model, feature)) {
            throw new AiGatewayConfig.Refused("not_suitable", field);
        }
    }

    /** What the stored entries alone give {@code f}; null where the properties decide. */
    private static String storedModel(String defaultModel, Map<AiFeature, String> features, AiFeature f) {
        String override = features.get(f);
        return override != null ? override : defaultModel;
    }

    private void forget() {
        memo = null;
        memoUntil = Instant.MIN;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
