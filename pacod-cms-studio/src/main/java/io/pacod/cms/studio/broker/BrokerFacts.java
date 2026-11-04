package io.pacod.cms.studio.broker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What pacod-broker says of models, for the installation screen's AI card: the reputation, price
 * now and live state of the gateway's models that are broker resources (their
 * {@code broker_resource_id} in the gateway's list), and the chat models the broker lists that
 * the gateway does not offer yet.
 *
 * <p>Read with the studio's consumer token ({@code pacod.broker.token}), each resource at most once
 * a minute; a failure is kept ten seconds, so a broker that is down costs one short wait per
 * screen, never the screen. Everything here is other providers' words: shown as text, never
 * followed as a link.
 */
@Slf4j
public class BrokerFacts {

    static final Pattern RESOURCE_ID = Pattern.compile("res_[0-9a-f]{8,64}");
    static final Duration KEEP = Duration.ofSeconds(60);
    static final Duration KEEP_FAILURE = Duration.ofSeconds(10);
    static final int FOUND_LIMIT = 20;

    /** One resource as the AI card shows it. */
    public record Facts(String id, String name, String provider, String price, Double reputation,
                        String reputationLabel, String status, boolean vision, boolean structuredOutput) {
    }

    private final BrokerClient broker;
    private final String token;
    private final Cache<String, Optional<Facts>> resources = Caffeine.newBuilder().maximumSize(2_000)
            .expireAfterWrite(KEEP).build();
    private volatile List<Facts> found;
    private volatile long foundAt;
    private volatile String error;
    private volatile long errorAt;

    public BrokerFacts(BrokerClient broker, String token) {
        this.broker = broker;
        this.token = token == null ? "" : token.strip();
    }

    /** The facts of one resource; empty when unknown, unreadable, or the id is not one. */
    public Optional<Facts> of(String resourceId) {
        if (resourceId == null || !RESOURCE_ID.matcher(resourceId).matches() || recentlyFailed()) {
            return Optional.empty();
        }
        Optional<Facts> cached = resources.getIfPresent(resourceId);
        if (cached != null) {
            return cached;
        }
        try {
            Optional<Facts> facts = Optional.of(facts(broker.resource(token, resourceId)));
            resources.put(resourceId, facts);
            return facts;
        } catch (BrokerClient.BrokerException e) {
            if (e.notFound()) {
                resources.put(resourceId, Optional.empty());
            } else {
                failed(e.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Chat models on the broker that answer a JSON schema (and see images, for {@code vision}), minus
     * the resources in {@code known} -- what the gateway already offers. At most {@value #FOUND_LIMIT}.
     */
    public List<Facts> notInGateway(Set<String> known, boolean vision) {
        List<Facts> all = found;
        if (all == null || System.currentTimeMillis() - foundAt > KEEP.toMillis()) {
            if (recentlyFailed()) {
                return List.of();
            }
            try {
                JsonNode answer = broker.discover(token, Map.of("kind", "model", "limit", 100));
                List<Facts> list = new ArrayList<>();
                for (JsonNode hit : answer.path("hits")) {
                    list.add(facts(hit));
                }
                found = all = List.copyOf(list);
                foundAt = System.currentTimeMillis();
            } catch (BrokerClient.BrokerException e) {
                failed(e.getMessage());
                return List.of();
            }
        }
        return all.stream()
                .filter(f -> f.structuredOutput() && (!vision || f.vision()) && !known.contains(f.id()))
                .limit(FOUND_LIMIT)
                .toList();
    }

    /** The last failure, while it is recent; null otherwise. */
    public String error() {
        return recentlyFailed() ? error : null;
    }

    static Facts facts(JsonNode hit) {
        JsonNode r = hit.has("resource") ? hit.path("resource") : hit;
        JsonNode provider = hit.path("provider").isObject() ? hit.path("provider") : r.path("provider");
        JsonNode price = hit.has("price") ? hit.path("price") : hit.path("price_now");
        JsonNode rep = hit.path("reputation").isObject() ? hit.path("reputation") : hit.path("provider_reputation");
        JsonNode caps = r.path("capabilities");
        String id = hit.path("id").asString(r.path("id").asString(""));
        return new Facts(id,
                text(r.path("title").asString(r.path("name").asString("")), 120),
                text(provider.path("title").asString(provider.path("name").asString("")), 120),
                price(price),
                rep.path("score").isNumber() ? Math.round(rep.path("score").asDouble() * 100) / 100.0 : null,
                text(rep.path("label").asString(""), 24),
                text(hit.path("live").path("status").asString(""), 24),
                caps.path("vision").asBoolean(false),
                caps.path("structured_output").asBoolean(false));
    }

    /** {@code EUR input 0.25, output 0.5}: the price in force now, as one line. */
    static String price(JsonNode price) {
        if (price == null || !price.isObject()) {
            return "";
        }
        if (price.path("free").asBoolean(false)) {
            return "free";
        }
        List<String> parts = new ArrayList<>();
        price.path("rates").properties().stream().limit(4).forEach(e -> {
            if (e.getValue().isNumber()) {
                parts.add(text(e.getKey(), 32) + " " + e.getValue().asDouble());
            }
        });
        return parts.isEmpty() ? "" : text(price.path("currency").asString(""), 8) + " " + String.join(", ", parts);
    }

    private static String text(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private boolean recentlyFailed() {
        return error != null && System.currentTimeMillis() - errorAt < KEEP_FAILURE.toMillis();
    }

    private void failed(String message) {
        error = message;
        errorAt = System.currentTimeMillis();
        log.info("pacod-broker could not be read: {}", message);
    }
}
