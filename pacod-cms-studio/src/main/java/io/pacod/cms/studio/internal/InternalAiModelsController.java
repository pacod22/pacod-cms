package io.pacod.cms.studio.internal;

import io.pacod.cms.studio.access.PlatformPermission;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.ai.AiCatalog;
import io.pacod.cms.studio.ai.AiFeature;
import io.pacod.cms.studio.ai.AiGatewayConfig;
import io.pacod.cms.studio.ai.AiModels;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which model or template the AI features call, for scripts: {@code GET}, {@code PUT} and
 * {@code DELETE /internal/v1/ai-models}, as the platform screen chooses them -- the same
 * {@link AiModels}, checked against the same list from the gateway, which {@code GET} includes.
 *
 * <p>{@code PUT {"default": "@fast", "features": {"vision": "openai/gpt-dev", "pdf": null}}}: an
 * absent {@code default} keeps it and {@code ""} clears it; in {@code features} only the named
 * features change, {@code null} or {@code ""} clearing the override.
 */
@PlatformPermission(read = "platform.manage")
@RestController
@RequestMapping(InternalAiModelsController.PATH)
public class InternalAiModelsController {

    public static final String PATH = "/internal/v1/ai-models";

    private final AiModels models;
    private final AiCatalog catalog;

    public InternalAiModelsController(AiModels models, AiCatalog catalog) {
        this.models = models;
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> show() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(describe());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> set(@RequestBody(required = false) JsonNode body) {
        if (body != null && !body.isObject() && !body.isNull()) {
            return refuse("bad_request", "body", "The body must be a JSON object");
        }
        String defaultModel = null;
        Map<AiFeature, String> features = new EnumMap<>(AiFeature.class);
        if (body != null && body.isObject()) {
            JsonNode d = body.get("default");
            if (d != null && !d.isNull()) {
                if (!d.isString()) {
                    return refuse("bad_request", "default", "default must be a string");
                }
                defaultModel = d.asString();
            }
            JsonNode f = body.get("features");
            if (f != null && !f.isNull()) {
                if (!f.isObject()) {
                    return refuse("bad_request", "features", "features must be an object");
                }
                for (Map.Entry<String, JsonNode> e : f.properties()) {
                    AiFeature feature = AiFeature.of(e.getKey()).orElse(null);
                    if (feature == null) {
                        return refuse("unknown_feature", "features", "features may name " + keys());
                    }
                    JsonNode v = e.getValue();
                    if (!v.isNull() && !v.isString()) {
                        return refuse("bad_request", feature.key(), "a feature's model must be a string or null");
                    }
                    features.put(feature, v.isNull() ? "" : v.asString());
                }
            }
        }
        try {
            models.set(new AiModels.Change(defaultModel, features), StudioPrincipals.currentSubject());
        } catch (AiGatewayConfig.Refused e) {
            return refuse(e.code(), e.field(), message(e.code(), e.field()));
        }
        return show();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        models.clear();
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> describe() {
        AiModels.Stored stored = models.stored();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("default", Map.of("model", models.defaultModel(),
                "source", stored.defaultModel() == null ? "environment" : "stored"));
        Map<String, Object> features = new LinkedHashMap<>();
        for (AiFeature f : AiFeature.values()) {
            AiModels.Resolved r = models.resolve(f);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("model", r.model());
            entry.put("source", r.source().name().toLowerCase(Locale.ROOT));
            entry.put("override", stored.features().get(f));
            features.put(f.key(), entry);
        }
        body.put("features", features);
        AiCatalog.Lookup lookup = catalog.lookup();
        body.put("catalog", lookup.found().map(InternalAiModelsController::catalog).orElse(null));
        body.put("catalogError", lookup.error());
        body.put("updatedOn", stored.updatedOn() == null ? null : stored.updatedOn().toString());
        body.put("updatedBy", stored.updatedBy());
        return body;
    }

    private static Map<String, Object> catalog(AiCatalog.Catalog c) {
        List<Map<String, Object>> models = new ArrayList<>();
        for (AiCatalog.ModelOption m : c.models()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.id());
            entry.put("provider", m.provider());
            entry.put("vision", m.vision());
            entry.put("structuredOutput", m.structuredOutput());
            entry.put("deprecated", m.deprecated());
            entry.put("suits", suits(c, m.id()));
            models.add(entry);
        }
        List<Map<String, Object>> templates = new ArrayList<>();
        for (AiCatalog.TemplateOption t : c.templates()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", t.id());
            entry.put("model", t.primaryModel());
            entry.put("models", t.poolModels());
            entry.put("suits", suits(c, t.id()));
            templates.add(entry);
        }
        return Map.of("models", models, "templates", templates);
    }

    /** The features {@code id} may be chosen for. */
    private static List<String> suits(AiCatalog.Catalog c, String id) {
        List<String> out = new ArrayList<>();
        for (AiFeature f : AiFeature.values()) {
            if (c.suits(id, f)) {
                out.add(f.key());
            }
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> refuse(String code, String field, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        body.put("field", field);
        return ResponseEntity.status(422).cacheControl(CacheControl.noStore()).body(body);
    }

    private static String keys() {
        return String.join(", ", java.util.Arrays.stream(AiFeature.values()).map(AiFeature::key).toList());
    }

    private static String message(String code, String field) {
        return switch (code) {
            case "unchanged" -> "no model changes";
            case "catalogue_unavailable" -> "the gateway's list of models cannot be read now; try again";
            case "not_offered" -> "the gateway does not offer the model chosen for " + field;
            case "not_suitable" -> "the model chosen for " + field
                    + " cannot answer with a JSON schema" + ("vision".equals(field) ? " or take images" : "");
            case "default_not_offered" -> field + " would use the default, which the gateway does not offer; choose a model for "
                    + field;
            case "default_not_suitable" -> field + " would use the default, which cannot do its work; choose a model for " + field;
            default -> code;
        };
    }
}
