package io.pacod.cms.studio.internal;

import io.pacod.cms.studio.access.PlatformPermission;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.AiGatewayConfig;
import io.pacod.cms.studio.ai.AiModels;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The installation's pacod-gw address and key, for scripts (pacod-demo's bootstrap, say):
 * {@code GET}, {@code PUT} and {@code DELETE /internal/v1/ai-gateway}, as the platform screen sets
 * them -- the same {@link AiGatewayConfig}, the same pairing rule, applied from the next call on.
 * Reached with a pacod-id service token holding {@code platform.manage}
 * ({@code MgmtSecurityConfig.internalApiFilterChain}).
 *
 * <p>The key is never answered back, only {@code keyHint}, its last characters.
 */
@PlatformPermission(read = "platform.manage")
@RestController
@RequestMapping(InternalAiGatewayController.PATH)
public class InternalAiGatewayController {

    public static final String PATH = "/internal/v1/ai-gateway";

    /** {@code PUT}'s body: the key always, and the address when it changes (absent keeps it). */
    public record Change(String url, String apiKey) {
    }

    private final AiGatewayConfig config;
    private final AiGateway gateway;
    private final AiModels models;

    public InternalAiGatewayController(AiGatewayConfig config, AiGateway gateway, AiModels models) {
        this.config = config;
        this.gateway = gateway;
        this.models = models;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> show() {
        return answer(describe(config.effective()));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> set(@RequestBody(required = false) Change change,
                                                   @RequestParam(defaultValue = "false") boolean test) {
        Change c = change == null ? new Change(null, null) : change;
        AiGatewayConfig.Effective now;
        try {
            now = config.set(c.url(), c.apiKey(), StudioPrincipals.currentSubject());
        } catch (AiGatewayConfig.Refused e) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("error", message(e.code()));
            refusal.put("code", e.code());
            refusal.put("field", e.field());
            return ResponseEntity.status(422).cacheControl(CacheControl.noStore()).body(refusal);
        }
        Map<String, Object> body = describe(now);
        if (test) {
            body.put("test", ping());
        }
        return answer(body);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        config.clear();
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> ping() {
        Map<String, Object> result = new LinkedHashMap<>();
        long started = System.nanoTime();
        String model = models.defaultModel();
        try {
            AiGateway.Answer answer = gateway.ping(model);
            result.put("ok", true);
            result.put("millis", (System.nanoTime() - started) / 1_000_000);
            result.put("model", model);
            result.put("requestId", answer.requestId());
        } catch (AiGateway.AiException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    static Map<String, Object> describe(AiGatewayConfig.Effective e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", e.url().isEmpty() ? null : e.url());
        body.put("source", e.source().name().toLowerCase(java.util.Locale.ROOT));
        body.put("keySet", !e.key().isEmpty());
        body.put("keyHint", e.key().isEmpty() ? null : e.keyHint());
        body.put("configured", e.configured());
        body.put("updatedOn", e.updatedOn() == null ? null : e.updatedOn().toString());
        body.put("updatedBy", e.updatedBy());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> answer(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String message(String code) {
        return switch (code) {
            case "url_invalid" -> "url must be an http or https address with a host, without a query";
            case "key_invalid" -> "apiKey may hold no whitespace or control characters, and at most 512 characters";
            case "key_required_with_url" -> "a new url needs its apiKey in the same request";
            case "url_required" -> "url is required";
            case "key_required" -> "apiKey is required";
            case "unchanged" -> "apiKey is required";
            default -> code;
        };
    }
}
