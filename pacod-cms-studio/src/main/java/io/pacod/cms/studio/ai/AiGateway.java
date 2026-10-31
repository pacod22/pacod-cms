package io.pacod.cms.studio.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pacod-gw's chat completions, OpenAI's shape: {@code POST <address>/v1/chat/completions} with a
 * gateway key and a JSON-schema answer, the address and key being {@link AiGatewayConfig}'s -- the
 * stored pair, else {@code pacod.ai.gateway-url}/{@code pacod.ai.api-key} -- read per call, so a
 * change applies to the next one. Every AI feature calls through here and nowhere else.
 *
 * <ul>
 *   <li><b>Attribution, opaque ids only</b>: {@code x-gw-end-user} the website (per-website limits
 *       at the gateway), {@code x-gw-task} the feature, {@code x-gw-trace} the job,
 *       {@code x-gw-user-id} the person's {@code usr_} id; {@code x-gw-tags} only when
 *       {@code pacod.ai.tags} is on, since the gateway counts tag keys a workspace declares.</li>
 *   <li><b>One retry, for a 429</b>, after its {@code Retry-After} (at most 30 s); never for a 402
 *       (the budget is spent) or a 504 (the model may still be generating, and billing). The
 *       gateway already fails over between models; a second retry layer here would multiply.</li>
 *   <li><b>An answer cut short is a failure</b> ({@code finish_reason: length}): a half translation
 *       is never written. A refusal is a failure too.</li>
 *   <li>{@code usage.cost.amount}, when the gateway prices the call, is kept in millionths of a
 *       dollar for the job's record.</li>
 * </ul>
 *
 * <p>What comes back is untrusted: every caller validates it before anything is stored.
 */
@Slf4j
@Component
public class AiGateway {

    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final AiGatewayConfig config;
    private final boolean tags;
    private final RestClient client;
    /** For the model list, which a page render may wait on: seconds, not minutes. */
    private final RestClient listing;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public AiGateway(AiGatewayConfig config, @Value("${pacod.ai.timeout:180s}") Duration timeout,
                     @Value("${pacod.ai.tags:false}") boolean tags) {
        this.config = config;
        this.tags = tags;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        JdkClientHttpRequestFactory quick = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        quick.setReadTimeout(Duration.ofSeconds(4));
        this.listing = RestClient.builder().requestFactory(quick).build();
    }

    /** A gateway with a fixed address and key, nothing stored: for tests. */
    public AiGateway(String url, String key, Duration timeout, boolean tags) {
        this(AiGatewayConfig.fixed(url, key), timeout, tags);
    }

    /** Whether there is a gateway to call: an address and a key, stored or from the environment. */
    public boolean configured() {
        return config.effective().configured();
    }

    /**
     * One completion.
     *
     * @param feature    what it is for: the task the gateway records ({@code translate}, …)
     * @param system     the instructions
     * @param user       the material -- data, never instructions; the prompt says so
     * @param imageUrl   an image to look at ({@code data:} URL), or null
     * @param schemaName the answer schema's name
     * @param schema     the answer's JSON schema (strict)
     */
    public record Call(String feature, String model, String system, String user, String imageUrl,
                       String schemaName, Map<String, Object> schema, int maxTokens,
                       String siteId, String userId, String trace) {
    }

    /** The parsed answer, what it cost, and the gateway's id for the call. */
    public record Answer(JsonNode json, long costMicros, String requestId) {
    }

    /**
     * The gateway's list of what this key may call, {@code GET <address>/gw/v1/models} (the native
     * interface: models with their kind, state and capabilities, and templates with their pools),
     * parsed by {@link AiCatalog}. Nobody is attributed. The address and key in effect, the answer
     * as the gateway wrote it.
     *
     * @throws AiException not set up, unreachable, refused, or not JSON
     */
    public JsonNode listModels() {
        AiGatewayConfig.Effective target = config.effective();
        if (!target.configured()) {
            throw new AiException("AI is not set up: no gateway address and key");
        }
        try {
            String body = listing.get().uri(target.url() + "/gw/v1/models")
                    .headers(h -> h.setBearerAuth(target.key()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            JsonNode parsed = json.readTree(body == null ? "{}" : body);
            if (parsed == null || !parsed.isObject()) {
                throw new AiException("The gateway's model list was not JSON");
            }
            return parsed;
        } catch (RestClientResponseException e) {
            throw refusal(e);
        } catch (ResourceAccessException e) {
            throw new AiException("The AI gateway cannot be reached: " + e.getMessage());
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiException("The gateway's model list was not JSON");
        }
    }

    /** The address and key calls go to now, for a cache keyed by them. */
    AiGatewayConfig.Effective target() {
        return config.effective();
    }

    /** The JSON schema of {@link #ping}'s answer. */
    private static final Map<String, Object> PING_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("reply", Map.of("type", "string")),
            "required", List.of("reply"),
            "additionalProperties", false);

    /**
     * The smallest real call there is, for the installation screen's "test the connection": the
     * address, the key and {@code model}, end to end, with nobody's website or person attributed
     * -- nothing is bound there, and no header names one.
     */
    public Answer ping(String model) {
        return complete(new Call("ping", model, "Answer with a JSON object whose \"reply\" is the word pong.",
                "ping", null, "ping", PING_SCHEMA, 64, null, null, null));
    }

    public Answer complete(Call call) {
        AiGatewayConfig.Effective target = config.effective();
        if (!target.configured()) {
            throw new AiException("AI is not set up: no gateway address and key");
        }
        Map<String, Object> body = body(call);
        try {
            return send(target, call, body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                sleep(retryAfter(e));
                try {
                    return send(target, call, body);
                } catch (RestClientResponseException again) {
                    throw refusal(again);
                }
            }
            throw refusal(e);
        } catch (ResourceAccessException e) {
            throw new AiException("The AI gateway cannot be reached: " + e.getMessage());
        }
    }

    private Map<String, Object> body(Call call) {
        Object content = call.imageUrl() == null ? call.user() : List.of(
                Map.of("type", "text", "text", call.user()),
                Map.of("type", "image_url", "image_url", Map.of("url", call.imageUrl())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.model());
        body.put("messages", List.of(Map.of("role", "system", "content", call.system()),
                Map.of("role", "user", "content", content)));
        body.put("response_format", Map.of("type", "json_schema",
                "json_schema", Map.of("name", call.schemaName(), "strict", true, "schema", call.schema())));
        body.put("max_tokens", call.maxTokens());
        return body;
    }

    private Answer send(AiGatewayConfig.Effective target, Call call, Map<String, Object> body) {
        var response = client.post().uri(target.url() + "/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers(h, target.key(), call))
                .body(json.writeValueAsString(body))
                .retrieve().toEntity(String.class);
        JsonNode answer = json.readTree(response.getBody() == null ? "{}" : response.getBody());
        JsonNode choice = answer.path("choices").path(0);
        String finish = choice.path("finish_reason").asString("");
        if ("length".equals(finish)) {
            throw new AiException("The answer was cut off (too long for the output budget)");
        }
        JsonNode message = choice.path("message");
        if (!message.path("refusal").isNull() && !message.path("refusal").isMissingNode()) {
            throw new AiException("The model declined: " + message.path("refusal").asString());
        }
        String text = message.path("content").asString("");
        JsonNode parsed;
        try {
            parsed = json.readTree(text);
        } catch (RuntimeException e) {
            throw new AiException("The answer was not the JSON asked for");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new AiException("The answer was not the JSON asked for");
        }
        return new Answer(parsed, costMicros(answer.path("usage").path("cost")),
                response.getHeaders().getFirst("x-gw-request-id"));
    }

    private void headers(HttpHeaders headers, String key, Call call) {
        headers.setBearerAuth(key);
        put(headers, "x-gw-end-user", call.siteId());
        put(headers, "x-gw-task", call.feature());
        put(headers, "x-gw-trace", call.trace());
        put(headers, "x-gw-user-id", call.userId());
        if (tags) {
            List<String> pairs = new ArrayList<>();
            if (call.siteId() != null) {
                pairs.add("site=" + call.siteId());
            }
            pairs.add("feature=" + call.feature());
            headers.set("x-gw-tags", String.join(",", pairs));
        }
    }

    private static void put(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank() && value.matches("[A-Za-z0-9_.:-]{1,200}")) {
            headers.set(name, value);
        }
    }

    /** {@code {"amount": 0.0042, "currency": "USD"}}, or a bare number; in millionths. */
    static long costMicros(JsonNode cost) {
        JsonNode amount = cost.isObject() ? cost.path("amount") : cost;
        if (!amount.isNumber()) {
            return 0;
        }
        return amount.decimalValue().multiply(BigDecimal.valueOf(1_000_000)).longValue();
    }

    private static Duration retryAfter(RestClientResponseException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        try {
            Duration wait = Duration.ofSeconds(Long.parseLong(header == null ? "2" : header.strip()));
            return wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait;
        } catch (NumberFormatException ex) {
            return Duration.ofSeconds(2);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Interrupted while waiting to retry");
        }
    }

    /**
     * A refusal in words, with what the gateway said that helps find it in its log -- its error
     * code and the request id -- and nothing else of its answer: never a body echoed whole, and
     * the key is never in one.
     */
    private AiException refusal(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String reason = switch (status) {
            case 401, 403 -> "The AI gateway refused the key";
            case 402 -> "The AI budget is used up";
            case 429 -> "The AI gateway is busy; try again later";
            case 504 -> "The model did not answer in time";
            default -> "The AI gateway answered " + status;
        };
        List<String> details = new ArrayList<>();
        try {
            JsonNode error = json.readTree(e.getResponseBodyAsString()).path("error");
            String code = error.path("code").asString(error.path("type").asString(""));
            if (TOKEN.matcher(code).matches()) {
                details.add(code);
            }
        } catch (RuntimeException ignored) {
            // Not JSON: the status says enough.
        }
        String request = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("x-gw-request-id");
        if (request != null && TOKEN.matcher(request).matches()) {
            details.add("request " + request);
        }
        return new AiException(details.isEmpty() ? reason : reason + " (" + String.join(", ", details) + ")");
    }

    /** What may be quoted from a gateway's answer: an identifier, never prose. */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    /** A call that produced nothing usable, in words for a log or a flash. */
    public static class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }
    }
}
