package io.pacod.cms.studio.identity;

import io.pacod.cms.studio.access.ServiceTokenCheck;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A script's {@code pidt_…} token, checked as pacod-id says products check them
 * ({@code POST /internal/v1/introspect} as the service client): {@code active} and
 * {@code pid_kind: service}, the account then being the {@code sub}.
 *
 * <ul>
 *   <li><b>Cached by the token's SHA-256</b> -- never the token -- an answer for {@link #TTL}, a
 *       refusal for {@link #NEGATIVE_TTL}; everything is dropped when the feed's
 *       {@link AuthzFeed#credentialEpoch()} moves ({@code credential.revoked}, or a snapshot reload
 *       that may have skipped one).</li>
 *   <li><b>The feed must be fresh</b>, as for a person ({@code IdentityGuardInterceptor}): what the
 *       account may do comes from it, and an old copy may still hold a role since taken away. A
 *       disabled or deleted account is refused.</li>
 * </ul>
 */
@Slf4j
public class ServiceTokens implements ServiceTokenCheck {

    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration NEGATIVE_TTL = Duration.ofSeconds(10);
    static final int MAX_CACHED = 1_000;
    static final int MAX_TOKEN = 4_096;

    private record Entry(String sub, Instant until) {
    }

    private final IdClient client;
    private final AuthzFeed feed;
    private final Duration maxStaleness;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private volatile long epoch = Long.MIN_VALUE;

    public ServiceTokens(IdClient client, AuthzFeed feed, Duration maxStaleness) {
        this(client, feed, maxStaleness, Clock.systemUTC());
    }

    ServiceTokens(IdClient client, AuthzFeed feed, Duration maxStaleness, Clock clock) {
        this.client = client;
        this.feed = feed;
        this.maxStaleness = maxStaleness;
        this.clock = clock;
    }

    @Override
    public Optional<String> subjectOf(String token) {
        if (!feed.isReady() || feed.staleness().compareTo(maxStaleness) > 0) {
            throw new Unavailable("pacod-id has not been reachable for " + feed.staleness().toSeconds() + "s");
        }
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN) {
            return Optional.empty();
        }
        long now = feed.credentialEpoch();
        if (now != epoch) {
            cache.clear();
            epoch = now;
        }
        String key = sha256(token);
        Instant at = clock.instant();
        Entry hit = cache.get(key);
        String sub;
        if (hit != null && at.isBefore(hit.until())) {
            sub = hit.sub();
        } else {
            sub = introspect(token);
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(key, new Entry(sub, at.plus(sub == null ? NEGATIVE_TTL : TTL)));
        }
        return sub != null && feed.isActive(sub) ? Optional.of(sub) : Optional.empty();
    }

    private String introspect(String token) {
        JsonNode answer;
        try {
            answer = client.postForm("/internal/v1/introspect", Map.of("token", token));
        } catch (IdClient.IdException e) {
            if (e.status() >= 500) {
                throw new Unavailable("pacod-id did not answer the token check: " + e.getMessage());
            }
            log.error("pacod-id refused the studio's token check ({} {})", e.status(), e.code());
            throw new Unavailable("pacod-id refused the token check");
        }
        if (!answer.path("active").asBoolean(false) || !"service".equals(answer.path("pid_kind").asString(""))) {
            return null;
        }
        String sub = answer.path("sub").asString("");
        return sub.isBlank() ? null : sub;
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
