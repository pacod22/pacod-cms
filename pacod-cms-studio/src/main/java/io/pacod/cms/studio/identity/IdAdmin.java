package io.pacod.cms.studio.identity;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * pacod-id's admin API ({@code /admin/v1}, on the internal listener), with a token from
 * {@link TokenExchange}. pacod-id checks every call against the acting principal's own roles -- the
 * studio decides nothing here that pacod-id would not refuse on its own.
 */
public class IdAdmin {

    private final RestClient rest;
    private final ObjectMapper json;

    public IdAdmin(IdProperties id, ObjectMapper json) {
        this(RestClient.builder()
                .baseUrl(id.internalUrl() + "/admin/v1")
                .requestFactory(IdClient.shortRequests())
                .build(), json);
    }

    IdAdmin(RestClient rest, ObjectMapper json) {
        this.rest = rest;
        this.json = json;
    }

    public JsonNode get(String token, String path, Map<String, ?> query) {
        return IdClient.call(() -> rest.get()
                .uri(path, builder -> {
                    query.forEach(builder::queryParam);
                    return builder.build();
                })
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class), json);
    }

    public JsonNode post(String token, String path, Object body) {
        return IdClient.call(() -> rest.post()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                .body(String.class), json);
    }

    public JsonNode put(String token, String path, Object body) {
        return IdClient.call(() -> rest.put()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                .body(String.class), json);
    }

    public JsonNode delete(String token, String path, Map<String, ?> query) {
        return IdClient.call(() -> rest.delete()
                .uri(path, builder -> {
                    query.forEach(builder::queryParam);
                    return builder.build();
                })
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class), json);
    }

    /** The {@code seq} of a write's answer; -1 when it carries none (nothing changed). */
    public static long seqOf(JsonNode answer) {
        JsonNode seq = answer.path("seq");
        return seq.isNumber() ? seq.asLong() : -1;
    }
}
