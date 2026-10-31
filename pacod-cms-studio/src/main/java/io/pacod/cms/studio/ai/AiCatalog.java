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
 *       suits it; one it cannot see is the gateway's to route.</li>
 *   <li><b>Untrusted</b>: an id outside {@link #ID} is dropped, the lists are capped.</li>
 *   <li><b>Cached a minute</b>, keyed by the address and a hash of the key in effect, so a new
 *       gateway is asked at once; a failure is remembered for {@link #FAILURE_TTL} with its reason.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiCatalog {

    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration FAILURE_TTL = Duration.ofSeconds(10);
    static final Pattern ID = Pattern.compile("[A-Za-z0-9@_./:-]{1,200}");
    static final int MAX_ENTRIES = 500;

    /** A chat model the key may call. */
    public record ModelOption(String id, String displayName, String provider, boolean vision,
                              boolean structuredOutput, boolean deprecated) {
    }

    /** A template, {@code @name}, with the models of its pool. */
    public record TemplateOption(String id, String primaryModel, List<String> poolModels) {
    }

    /** An entry a feature may choose: a template or a model. */
    public record Choice(String id, String label, boolean template, boolean deprecated) {
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
                    .forEach(m -> out.add(new Choice(m.id(), m.displayName().equals(m.id()) ? m.id()
                            : m.displayName() + " (" + m.id() + ")", false, m.deprecated())));
            return out;
        }

        /**
         * The choices for the default: what suits the features that need no images (they all ask
         * the same). Whether it also suits VISION, when that has no override, {@link AiModels#set}
         * checks.
         */
        public List<Choice> offeredForDefault() {
            return offeredFor(AiFeature.TRANSLATE);
        }
    }

    /** The catalogue, or why there is none. */
    public record Lookup(Catalog catalog, String error) {

        public Optional<Catalog> found() {
            return Optional.ofNullable(catalog);
        }
    }

    private record Cached(String key, Lookup lookup, Instant until) {
    }

    private final AiGateway gateway;
    private final Clock clock;
    private volatile Cached cached;

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
        AiGatewayConfig.Effective target = gateway.target();
        String key = target.url() + "|" + sha256(target.key());
        Instant now = clock.instant();
        Cached c = cached;
        if (c != null && c.key().equals(key) && now.isBefore(c.until())) {
            return c.lookup();
        }
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
            models.add(new ModelOption(id, display, ID.matcher(provider).matches() ? provider : "",
                    caps.path("vision").asBoolean(false), caps.path("structured_output").asBoolean(false),
                    m.path("deprecated").asBoolean(false) || "deprecated".equals(state)));
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

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
