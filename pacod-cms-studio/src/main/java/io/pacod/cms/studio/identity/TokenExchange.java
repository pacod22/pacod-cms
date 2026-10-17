package io.pacod.cms.studio.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Exchanges a token for one pacod-id's admin API accepts (RFC 8693), as the service client.
 *
 * <p>The admin API wants the person (or service account) it acts for and the product it acts in:
 * an exchanged token carries the subject's own {@code sub} and session, plus {@code act.sub} -- this
 * client -- whose product is {@code cms}. The subject is either a signed-in person's access token
 * (issued for audience {@code pacod-cms}, which is this client's own: pacod-id exchanges nothing
 * else) or the provisioner's {@code pidt_} token.
 *
 * <p>The answer has no {@code expires_in}; the token lives 60 seconds. It is kept for
 * {@link #KEEP}, keyed by a hash of the subject token, so a screen's three admin calls cost one
 * exchange.
 */
public class TokenExchange {

    static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    static final Duration KEEP = Duration.ofSeconds(50);

    private final RestClient rest;
    private final ObjectMapper json;
    private final Cache<String, String> exchanged = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(KEEP).build();

    public TokenExchange(IdProperties id, ObjectMapper json) {
        this(RestClient.builder()
                .baseUrl(id.internalUrl())
                .requestFactory(IdClient.shortRequests())
                .defaultHeader("Authorization", IdClient.basic(id.serviceClientId(), id.serviceSecret()))
                .build(), json);
    }

    TokenExchange(RestClient rest, ObjectMapper json) {
        this.rest = rest;
        this.json = json;
    }

    /** A token for {@code /admin/v1}, acting as whoever {@code subjectToken} names. */
    public String forAdmin(String subjectToken) {
        return exchanged.get(hash(subjectToken), key -> exchange(subjectToken));
    }

    /** Forgets the exchanged token of {@code subjectToken}, after pacod-id refused it. */
    public void forget(String subjectToken) {
        exchanged.invalidate(hash(subjectToken));
    }

    private String exchange(String subjectToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT);
        form.add("subject_token", subjectToken);
        form.add("subject_token_type", ACCESS_TOKEN);
        form.add("audience", "pacod-id");
        JsonNode answer = IdClient.call(() -> rest.post()
                .uri("/oidc/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(String.class), json);
        String token = answer.path("access_token").asString(null);
        if (token == null || token.isBlank()) {
            throw new IdClient.IdException(502, "no_token", "pacod-id answered the token exchange without a token");
        }
        return token;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
