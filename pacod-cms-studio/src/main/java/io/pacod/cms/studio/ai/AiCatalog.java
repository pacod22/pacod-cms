package io.pacod.cms.studio.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the installation's gateway key may call, from the gateway itself
 * ({@link AiGateway#listModels()}): the models and templates a platform admin chooses among
 * ({@link AiModels}).
 *
 * <ul>
 *   <li><b>Only what the studio can use</b>: chat models that are {@code active} or
 *       {@code deprecated} (the callable states), able to answer with a JSON schema -- every
 *       feature asks for one, and without {@code structured_output} the gateway drops or refuses
 *       it -- and, for {@link AiFeature#VISION}, taking images, which the gateway otherwise
 *       refuses. A template is offered for a feature when every model of its pool the key can see
 *       suits it; one it cannot see is the gateway's to route. What a feature cannot use is still
 *       listed, never offered ({@link Catalog#unusableFor}), so the screen shows the whole list.</li>
 *   <li><b>Untrusted</b>: an id outside {@link #ID} is dropped, the lists are capped.</li>
 *   <li><b>Cached a minute</b>, keyed by the address and a hash of the key in effect, so a new
 *       gateway is asked at once; a failure is remembered for {@link #FAILURE_TTL} with its reason.
 *       The installation screen asks afresh on every visit ({@link #refresh}), which also renews
 *       what the jobs read.</li>
 *   <li><b>What the gateway applies</b>: a model's limits and the parameters it takes come with
 *       the list; a template's settings ({@link AiTemplateDetails}) need a call of their own,
 *       {@link #template}, cached the same way and dropped with the list.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiCatalog {

    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration FAILURE_TTL = Duration.ofSeconds(10);
    static final Pattern ID = Pattern.compile("[A-Za-z0-9@_./:-]{1,200}");
    /**
     * A template id that may go into an address, {@code /gw/v1/models/@name}: a slug, as pacod-gw
     * names templates, never a dot or a slash -- {@code @../admin} would name another path.
     */
    static final Pattern TEMPLATE_PATH = Pattern.compile("@[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
    static final int MAX_ENTRIES = 500;

    /**
     * A chat model the key may call, with what the gateway says of it.
     *
     * @param maxOutputTokens the lower of the model's and the grant's ceiling, or null for none
     * @param temperature     whether it takes one: {@code accepted}, {@code rejected},
     *                        {@code default_only}; null when not said
     * @param reasoning       its reasoning style ({@code none}, {@code effort}, {@code budget},
     *                        {@code adaptive}, {@code always_on}); null when not said
     * @param efforts         the reasoning levels it takes, for a style of {@code effort}
     * @param brokerResourceId the pacod-broker resource the gateway links it to ({@code res_…}); null
     *                         for none, or a gateway that says nothing of the broker
     */
    public record ModelOption(String id, String displayName, String provider, boolean vision,
                              boolean structuredOutput, boolean deprecated, String state, Integer contextWindow,
                              Integer maxOutputTokens, String temperature, String topP, String reasoning,
                              List<String> efforts, String sunsetAt, String successor, String brokerResourceId) {

        /** Whether the gateway would drop (or, under {@code reject}, refuse) a temperature sent to it. */
        public boolean refusesTemperature() {
            return "rejected".equals(temperature) || "default_only".equals(temperature);
        }

        /** Whether a reasoning effort means nothing to it. */
        public boolean takesNoEffort() {
            return "none".equals(reasoning);
        }
    }

    /** A template, {@code @name}, with the models of its pool. */
    public record TemplateOption(String id, String primaryModel, List<String> poolModels) {
    }

    /** An entry a feature may choose: a template or a model. */
    public record Choice(String id, String label, boolean template, boolean deprecated) {
    }

    /**
     * An entry the gateway lists that a feature cannot use, shown so the list is whole but never
     * offered: {@code reason} is {@code no_json} (no JSON-schema answer, which every feature asks
     * for) or {@code no_images} (alt text looks at the picture).
     */
    public record Unusable(String id, String label, boolean template, String reason) {
    }

    public record Catalog(List<ModelOption> models, List<TemplateOption> templates) {

        public Optional<ModelOption> model(String id) {
            return models.stream().filter(m -> m.id().equals(id)).findFirst();
        }

        public Optional<TemplateOption> template(String id) {
            return templates.stream().filter(t -> t.id().equals(id)).findFirst();
        }

        /** Whether the gateway offers {@code id} at all, to anyone of this key. */
        public boolean has(String id) {
            return model(id).isPresent() || template(id).isPresent();
        }

        /** Whether {@code id} is offered and suits {@code feature}. */
        public boolean suits(String id, AiFeature feature) {
            Optional<ModelOption> m = model(id);
            if (m.isPresent()) {
                return suits(m.get(), feature);
            }
            Optional<TemplateOption> t = template(id);
            if (t.isEmpty()) {
                return false;
            }
            Map<String, ModelOption> byId = models.stream().collect(Collectors.toMap(ModelOption::id, Function.identity(), (a, b) -> a));
            // A pool model the key cannot see directly is the gateway's to route: not held against it.
            return t.get().poolModels().stream().map(byId::get).filter(java.util.Objects::nonNull)
                    .allMatch(pm -> suits(pm, feature));
        }

        private static boolean suits(ModelOption m, AiFeature feature) {
            return m.structuredOutput() && (!feature.needsVision() || m.vision());
        }

        /** What {@code feature} may choose: templates first, then models, each by id. */
        public List<Choice> offeredFor(AiFeature feature) {
            List<Choice> out = new ArrayList<>();
            templates.stream().filter(t -> suits(t.id(), feature)).sorted(Comparator.comparing(TemplateOption::id))
                    .forEach(t -> out.add(new Choice(t.id(), t.id(), true, false)));
            models.stream().filter(m -> suits(m, feature)).sorted(Comparator.comparing(ModelOption::id))
                    .forEach(m -> out.add(new Choice(m.id(), label(m), false, m.deprecated())));
            return out;
        }

        /** What the gateway lists that {@code feature} cannot use, with why: templates first, then models, each by id. */
        public List<Unusable> unusableFor(AiFeature feature) {
            List<Unusable> out = new ArrayList<>();
            templates.stream().filter(t -> !suits(t.id(), feature)).sorted(Comparator.comparing(TemplateOption::id))
                    .forEach(t -> out.add(new Unusable(t.id(), t.id(), true, poolOf(t.id()).stream()
                            .anyMatch(pm -> !pm.structuredOutput()) ? NO_JSON : NO_IMAGES)));
            models.stream().filter(m -> !suits(m, feature)).sorted(Comparator.comparing(ModelOption::id))
                    .forEach(m -> out.add(new Unusable(m.id(), label(m), false, m.structuredOutput() ? NO_IMAGES : NO_JSON)));
            return out;
        }

        /** {@link #unusableFor} for the default, which the features without images share. */
        public List<Unusable> unusableForDefault() {
            return unusableFor(AiFeature.TRANSLATE);
        }

        private static String label(ModelOption m) {
            return m.displayName().equals(m.id()) ? m.id() : m.displayName() + " (" + m.id() + ")";
        }

        /**
         * The choices for the default: what suits the features that need no images (they all ask
         * the same). Whether it also suits VISION, when that has no override, {@link AiModels#set}
         * checks.
         */
        public List<Choice> offeredForDefault() {
            return offeredFor(AiFeature.TRANSLATE);
        }

        /** Every reasoning level a listed model takes, in the order first seen. */
        public List<String> efforts() {
            return models.stream().flatMap(m -> m.efforts().stream()).distinct().toList();
        }

        /** The models of {@code id}'s pool the key can see; empty for a model or an unknown id. */
        public List<ModelOption> poolOf(String id) {
            Map<String, ModelOption> byId = models.stream().collect(Collectors.toMap(ModelOption::id, Function.identity(), (a, b) -> a));
            return template(id).map(t -> t.poolModels().stream().map(byId::get).filter(java.util.Objects::nonNull).toList())
                    .orElse(List.of());
        }
    }

    /** The catalogue, or why there is none. */
    public record Lookup(Catalog catalog, String error) {

        public Optional<Catalog> found() {
            return Optional.ofNullable(catalog);
        }
    }

    /** A template's settings, or why they could not be read. */
    public record TemplateLookup(AiTemplateDetails details, String error) {
    }

    private record Cached(String key, Lookup lookup, Instant until) {
    }

    private record CachedTemplate(String key, TemplateLookup lookup, Instant until) {
    }

    static final String NO_JSON = "no_json";
    static final String NO_IMAGES = "no_images";
    static final Set<String> SUPPORT = Set.of("accepted", "rejected", "default_only");
    static final Set<String> REASONING = Set.of("none", "effort", "budget", "adaptive", "always_on");
    static final int MAX_EFFORTS = 8;

    private final AiGateway gateway;
    private final Clock clock;
    private volatile Cached cached;
    private final Map<String, CachedTemplate> templates = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public AiCatalog(AiGateway gateway) {
        this(gateway, Clock.systemUTC());
    }

    AiCatalog(AiGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    public Optional<Catalog> current() {
        return lookup().found();
    }

    /** The catalogue or the reason it could not be read; never throws. */
    public Lookup lookup() {
        String key = cacheKey();
        Cached c = cached;
        if (c != null && c.key().equals(key) && clock.instant().isBefore(c.until())) {
            return c.lookup();
        }
        return read(key);
    }

    /**
     * The catalogue as the gateway answers now, kept for whoever looks next; the templates' settings
     * are asked again too. For a screen that must show what the gateway offers, such as a template
     * added a moment ago.
     */
    public Lookup refresh() {
        templates.clear();
        return read(cacheKey());
    }

    /**
     * What {@code id}, a template the list names, applies to every call; cached like the list.
     * Never throws.
     */
    public TemplateLookup template(String id) {
        String key = cacheKey() + "|" + id;
        Instant now = clock.instant();
        CachedTemplate c = templates.get(id);
        if (c != null && c.key().equals(key) && now.isBefore(c.until())) {
            return c.lookup();
        }
        TemplateLookup fresh;
        Duration ttl;
        try {
            fresh = new TemplateLookup(AiTemplateDetails.parse(id, gateway.templateSpec(id)), null);
            ttl = TTL;
        } catch (AiGateway.AiException e) {
            log.warn("The gateway's template {} could not be read: {}", id, e.getMessage());
            fresh = new TemplateLookup(null, e.getMessage());
            ttl = FAILURE_TTL;
        }
        if (templates.size() >= MAX_ENTRIES) {
            templates.clear();
        }
        templates.put(id, new CachedTemplate(key, fresh, now.plus(ttl)));
        return fresh;
    }

    private String cacheKey() {
        AiGatewayConfig.Effective target = gateway.target();
        return target.url() + "|" + sha256(target.key());
    }

    private Lookup read(String key) {
        Instant now = clock.instant();
        Lookup fresh;
        Duration ttl;
        try {
            fresh = new Lookup(parse(gateway.listModels()), null);
            ttl = TTL;
        } catch (AiGateway.AiException e) {
            log.warn("The gateway's model list could not be read: {}", e.getMessage());
            fresh = new Lookup(null, e.getMessage());
            ttl = FAILURE_TTL;
        }
        cached = new Cached(key, fresh, now.plus(ttl));
        return fresh;
    }

    /** Forgets what was read, so the next look asks the gateway. */
    public void forget() {
        cached = null;
        templates.clear();
    }

    static final Pattern BROKER_RESOURCE = Pattern.compile("res_[0-9a-f]{8,64}");

    /** A pacod-broker resource id, checked: it is only ever passed back to the broker. */
    static String brokerResource(JsonNode v) {
        String id = v.isString() ? v.asString() : "";
        return BROKER_RESOURCE.matcher(id).matches() ? id : null;
    }

    public static Catalog parse(JsonNode answer) {
        List<ModelOption> models = new ArrayList<>();
        for (JsonNode m : answer.path("models")) {
            if (models.size() >= MAX_ENTRIES) {
                break;
            }
            String id = m.path("id").asString("");
            String state = m.path("state").asString("active");
            if (!ID.matcher(id).matches() || id.startsWith("@") || !"chat".equals(m.path("kind").asString("chat"))
                    || !Set.of("active", "deprecated").contains(state)) {
                continue;
            }
            String display = m.path("display_name").asString(id);
            if (display.isBlank() || display.length() > 200 || display.chars().anyMatch(Character::isISOControl)) {
                display = id;
            }
            String provider = m.path("provider").asString("");
            JsonNode caps = m.path("capabilities");
            List<String> efforts = new ArrayList<>();
            for (JsonNode e : caps.path("reasoning_efforts")) {
                String level = e.isString() ? e.asString() : "";
                if (efforts.size() < MAX_EFFORTS && AiModels.EFFORT.matcher(level).matches() && !efforts.contains(level)) {
                    efforts.add(level);
                }
            }
            String successor = m.path("successor").isString() ? m.path("successor").asString() : "";
            String sunset = m.path("sunset_at").isString() ? m.path("sunset_at").asString() : "";
            models.add(new ModelOption(id, display, ID.matcher(provider).matches() ? provider : "",
                    caps.path("vision").asBoolean(false), caps.path("structured_output").asBoolean(false),
                    m.path("deprecated").asBoolean(false) || "deprecated".equals(state), state,
                    tokens(m.path("context_window")), tokens(m.path("max_output_tokens")),
                    oneOf(caps.path("temperature"), SUPPORT), oneOf(caps.path("top_p"), SUPPORT),
                    oneOf(caps.path("reasoning"), REASONING), List.copyOf(efforts),
                    instant(sunset), ID.matcher(successor).matches() ? successor : null,
                    brokerResource(m.path("broker_resource_id"))));
        }
        List<TemplateOption> templates = new ArrayList<>();
        for (JsonNode t : answer.path("templates")) {
            if (templates.size() >= MAX_ENTRIES) {
                break;
            }
            String id = t.path("id").asString("");
            if (!id.startsWith("@") || !ID.matcher(id).matches()) {
                continue;
            }
            List<String> pool = new ArrayList<>();
            for (JsonNode pm : t.path("models")) {
                String name = pm.path("model").asString("");
                if (ID.matcher(name).matches()) {
                    pool.add(name);
                }
            }
            String primary = t.path("model").asString("");
            templates.add(new TemplateOption(id, ID.matcher(primary).matches() ? primary : "", List.copyOf(pool)));
        }
        return new Catalog(List.copyOf(models), List.copyOf(templates));
    }

    private static Integer tokens(JsonNode n) {
        return n.isIntegralNumber() && n.asLong() > 0 && n.asLong() <= 100_000_000 ? (int) n.asLong() : null;
    }

    private static String oneOf(JsonNode n, Set<String> allowed) {
        String s = n.isString() ? n.asString() : "";
        return allowed.contains(s) ? s : null;
    }

    /** An RFC 3339 instant as its date, {@code 2027-01-31}; null for anything else. */
    private static String instant(String s) {
        try {
            return java.time.OffsetDateTime.parse(s).toLocalDate().toString();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
