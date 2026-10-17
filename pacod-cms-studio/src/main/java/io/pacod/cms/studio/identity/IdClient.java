package io.pacod.cms.studio.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * pacod-id's internal API, as the studio's service client ({@code pacod-cms-service}).
 *
 * <p>HTTP Basic with the service client's id and secret, which pacod-id accepts on every
 * {@code /internal/v1} call; the feed, the catalogue and scope registration additionally require
 * a service client with a product, and only for that product ({@code cms}). Errors come back in
 * pacod-id's shape, {@code {"error": {"type", "message", "details": {"code", ...}}}}; callers branch
 * on {@link IdException#code()}.
 */
@Slf4j
public class IdClient {

    /** Longer than the feed's long poll (25 s), so a poll that waits its full time is not cut off. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(40);

    private final RestClient rest;
    private final ObjectMapper json;

    public IdClient(IdProperties id, ObjectMapper json) {
        this(RestClient.builder()
                .baseUrl(id.internalUrl())
                .requestFactory(requestFactory())
                .defaultHeader("Authorization", basic(id.serviceClientId(), id.serviceSecret()))
                .build(), json);
    }

    IdClient(RestClient rest, ObjectMapper json) {
        this.rest = rest;
        this.json = json;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    static String basic(String id, String secret) {
        String pair = (id == null ? "" : id) + ":" + (secret == null ? "" : secret);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code GET} a JSON document. */
    public JsonNode get(String path, Map<String, ?> query) {
        return call(() -> rest.get()
                .uri(path, builder -> {
                    query.forEach(builder::queryParam);
                    return builder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    /** {@code PUT} a JSON body; the answer's JSON (pacod-id answers {@code {"seq": n}} and the like). */
    public JsonNode put(String path, Object body) {
        return call(() -> rest.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                .body(String.class));
    }

    /** {@code DELETE}; the answer's JSON ({@code {"seq": n}}). */
    public JsonNode delete(String path, Map<String, ?> query) {
        return call(() -> rest.delete()
                .uri(path, builder -> {
                    query.forEach(builder::queryParam);
                    return builder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class));
    }

    /** {@code POST} with no body (pacod-id's confirmations), ignoring the answer. */
    public void post(String path, Map<String, ?> query) {
        call(() -> {
            rest.post()
                    .uri(path, builder -> {
                        query.forEach(builder::queryParam);
                        return builder.build();
                    })
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    private JsonNode call(java.util.function.Supplier<String> request) {
        return call(request, json);
    }

    /** Runs {@code request}, turning pacod-id's refusals into {@link IdException}s. */
    static JsonNode call(java.util.function.Supplier<String> request, ObjectMapper json) {
        String body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            throw IdException.of(e.getStatusCode(), e.getResponseBodyAsString(), json);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new IdException(503, "unreachable", e.getMessage());
        }
        return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    static SimpleClientHttpRequestFactory shortRequests() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    /** A refusal or failure from pacod-id: its status and {@code details.code}. */
    public static final class IdException extends RuntimeException {

        private final int status;
        private final String code;

        IdException(int status, String code, String message) {
            super("pacod-id answered " + status + (code == null ? "" : " " + code) + ": " + message);
            this.status = status;
            this.code = code;
        }

        static IdException of(HttpStatusCode status, String body, ObjectMapper json) {
            String code = null;
            String message = body;
            try {
                JsonNode error = json.readTree(body).path("error");
                if (error.isString()) {
                    // OAuth's shape, from the token endpoint: {"error": "invalid_grant", ...}.
                    code = error.asString();
                    message = json.readTree(body).path("error_description").asString(code);
                } else {
                    code = error.path("details").path("code").asString(null);
                    message = error.path("message").asString(body);
                }
            } catch (RuntimeException ignored) {
                // Not pacod-id's error shape: keep the body as the message.
            }
            return new IdException(status.value(), code, message);
        }

        public int status() {
            return status;
        }

        /** {@code details.code}, e.g. {@code cursor_expired}, {@code scope_unknown}; may be null. */
        public String code() {
            return code;
        }
    }
}
