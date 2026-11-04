package io.pacod.cms.studio.broker;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * pacod-broker's public API, as far as the studio uses it: announcing an organisation's websites
 * under a lease, keeping the lease alive, saying goodbye, and reading resources for the AI card.
 * pacod-broker ships clients in Rust, Python and TypeScript, not Java, so this is the few calls the
 * studio makes, on {@code RestClient} over HTTP/1.1 like {@code AiGateway}.
 *
 * <p>Every call names its token: an organisation announces with its own (a provider in that
 * organisation), the studio reads with its consumer token. A blank token is a broker in open
 * mode: the studio then names itself ({@code x-broker-client}) and, on lease calls, the provider
 * ({@code ?provider=}), as pacod-broker's own clients do.
 */
public class BrokerClient {

    static final Duration CONNECT = Duration.ofSeconds(5);
    static final Duration READ = Duration.ofSeconds(10);
    static final String CLIENT_NAME = "pacod-cms";

    /** The broker refused or could not be reached; {@code status} 0 for no answer at all. */
    public static final class BrokerException extends RuntimeException {

        private final int status;

        BrokerException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }

        /** The lease or resource no longer exists: announce afresh. */
        public boolean notFound() {
            return status == 404;
        }
    }

    private final RestClient rest;
    private final String base;
    private final ObjectMapper json;

    public BrokerClient(String baseUrl, ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(READ);
        this.base = baseUrl.replaceAll("/+$", "");
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "pacod-cms-studio")
                .build();
        this.json = json;
    }

    /** {@code PUT /v1/announce}: the lease's answer ({@code lease_id}, {@code heartbeat_every_s}, {@code resources}). */
    public JsonNode announce(String token, Map<String, Object> announcement) {
        return call(token, HttpMethod.PUT, "/v1/announce", json.writeValueAsString(announcement));
    }

    public void heartbeat(String token, String leaseId, String providerName) {
        call(token, HttpMethod.POST, leasePath(leaseId, "/heartbeat", token, providerName), "{}");
    }

    public void deregister(String token, String leaseId, String providerName) {
        call(token, HttpMethod.DELETE, leasePath(leaseId, "", token, providerName), null);
    }

    /** {@code POST /v1/discover}. */
    public JsonNode discover(String token, Map<String, Object> query) {
        return call(token, HttpMethod.POST, "/v1/discover", json.writeValueAsString(query));
    }

    /** {@code GET /v1/resources/{id}}: the record with {@code price_now}, {@code live} and reputation. */
    public JsonNode resource(String token, String id) {
        return call(token, HttpMethod.GET, "/v1/resources/" + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8), null);
    }

    static String leasePath(String leaseId, String suffix, String token, String providerName) {
        String path = "/v1/leases/" + UriUtils.encodePathSegment(leaseId, StandardCharsets.UTF_8) + suffix;
        boolean open = token == null || token.isBlank();
        return open && providerName != null
                ? path + "?provider=" + UriUtils.encodeQueryParam(providerName, StandardCharsets.UTF_8)
                : path;
    }

    private JsonNode call(String token, HttpMethod method, String path, String body) {
        try {
            // Built from a URI so that the path's own encoding is kept, not encoded twice.
            RestClient.RequestBodySpec spec = rest.method(method).uri(java.net.URI.create(base + path))
                    .accept(MediaType.APPLICATION_JSON);
            if (token != null && !token.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + token.strip());
            } else {
                spec = spec.header("x-broker-client", CLIENT_NAME);
            }
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            String answer = spec.retrieve().body(String.class);
            return answer == null || answer.isBlank() ? json.createObjectNode() : json.readTree(answer);
        } catch (RestClientResponseException e) {
            throw new BrokerException(e.getStatusCode().value(), problem(e.getResponseBodyAsString()));
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new BrokerException(0, "the broker could not be reached");
        }
    }

    /** The broker's problem title and detail, short, never the whole body. */
    private String problem(String body) {
        try {
            JsonNode p = json.readTree(body);
            String text = (p.path("title").asString("") + ": " + p.path("detail").asString("")).strip();
            return text.length() > 300 ? text.substring(0, 300) : text;
        } catch (RuntimeException e) {
            return "the broker refused";
        }
    }
}
