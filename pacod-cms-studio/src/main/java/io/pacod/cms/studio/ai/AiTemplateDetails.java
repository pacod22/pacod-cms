package io.pacod.cms.studio.ai;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the gateway applies to every call of one template, from
 * {@link AiGateway#templateSpec(String)} ({@code GET /gw/v1/models/@name}: the list's entry plus
 * pacod-gw's {@code TemplateSpec} as {@code spec}), for the installation screen to show beside the
 * template it names.
 *
 * <p><b>Untrusted</b>: every value is typed and bounded, a string matched against
 * {@link AiCatalog#ID} or an allow-list, and anything else read as absent -- never an error, since
 * this only informs a screen.
 *
 * @param maxOutputTokens the template's default and ceiling for {@code max_tokens}
 * @param timeoutSeconds  the longest a call may take, at most 600
 */
public record AiTemplateDetails(String id, Integer version, String model, List<PoolEntry> pool, String routingScheme,
                                Integer maxOutputTokens, Param temperature, Param topP, Param frequencyPenalty,
                                Param presencePenalty, Param reasoningEffort, Param reasoningBudget,
                                Param reasoningSummary, Integer timeoutSeconds, Integer retryMaxAttempts,
                                String promptCache, String unsupportedParams) {

    static final int MAX_POOL = 16;
    private static final Set<String> PROMPT_CACHE = Set.of("off", "passthrough", "auto");
    private static final Set<String> UNSUPPORTED = Set.of("drop", "reject");

    /** One model of the pool, with the weight and priority the routing uses. */
    public record PoolEntry(String model, String tier, int weight, int priority) {
    }

    /**
     * A parameter as the template sets it: {@code omit} removes it from every call, {@code locked}
     * replaces whatever the caller sent, otherwise {@code value} is a default the caller's beats.
     * The value is a number, a level or {@code true}/{@code false}, as text.
     */
    public record Param(String value, boolean locked, boolean omit) {

        /** Whether a value the studio sends for it is ignored. */
        public boolean overridesCaller() {
            return omit || (locked && value != null);
        }
    }

    public static AiTemplateDetails parse(String id, JsonNode answer) {
        JsonNode spec = answer.path("spec");
        List<PoolEntry> pool = new ArrayList<>();
        JsonNode members = spec.path("models").isArray() && !spec.path("models").isEmpty()
                ? spec.path("models") : answer.path("models");
        for (JsonNode m : members) {
            if (pool.size() >= MAX_POOL) {
                break;
            }
            String model = m.path("model").asString("");
            if (AiCatalog.ID.matcher(model).matches()) {
                String tier = m.path("tier").asString("");
                pool.add(new PoolEntry(model, AiCatalog.ID.matcher(tier).matches() ? tier : null,
                        bounded(m.path("weight"), 0, 1_000_000, 1), bounded(m.path("priority"), 0, 255, 0)));
            }
        }
        String model = firstId(spec.path("model"), answer.path("model"));
        JsonNode reasoning = spec.path("reasoning");
        return new AiTemplateDetails(id, integer(answer.path("version"), 1, Integer.MAX_VALUE), model, List.copyOf(pool),
                firstId(answer.path("routing_scheme"), spec.path("routing_scheme")),
                integer(spec.has("max_output_tokens") ? spec.path("max_output_tokens") : answer.path("max_output_tokens"),
                        1, 10_000_000),
                number(spec.path("temperature"), 0, 2), number(spec.path("top_p"), 0, 1),
                number(spec.path("frequency_penalty"), -2, 2), number(spec.path("presence_penalty"), -2, 2),
                level(reasoning.path("effort")), whole(reasoning.path("budget_tokens")), flag(reasoning.path("summary")),
                integer(spec.path("timeout_seconds"), 1, 600), integer(spec.path("retry_max_attempts"), 1, 10),
                oneOf(spec.path("prompt_cache"), PROMPT_CACHE), oneOf(spec.path("unsupported_params"), UNSUPPORTED));
    }

    private static String firstId(JsonNode a, JsonNode b) {
        for (JsonNode n : List.of(a, b)) {
            String s = n.isString() ? n.asString() : "";
            if (AiCatalog.ID.matcher(s).matches()) {
                return s;
            }
        }
        return null;
    }

    private static Integer integer(JsonNode n, long min, long max) {
        return n.isIntegralNumber() && n.asLong() >= min && n.asLong() <= max ? (int) n.asLong() : null;
    }

    private static int bounded(JsonNode n, int min, int max, int otherwise) {
        Integer v = integer(n, min, max);
        return v == null ? otherwise : v;
    }

    private static String oneOf(JsonNode n, Set<String> allowed) {
        String s = n.isString() ? n.asString() : "";
        return allowed.contains(s) ? s : null;
    }

    /** A {@code Param<f64>}; null where unset or unreadable. */
    private static Param number(JsonNode p, double min, double max) {
        if (!p.isObject()) {
            return null;
        }
        JsonNode v = p.path("value");
        String value = v.isNumber() && v.asDouble() >= min && v.asDouble() <= max
                ? java.math.BigDecimal.valueOf(v.asDouble()).stripTrailingZeros().toPlainString() : null;
        return param(p, value);
    }

    private static Param whole(JsonNode p) {
        if (!p.isObject()) {
            return null;
        }
        Integer v = integer(p.path("value"), 0, 10_000_000);
        return param(p, v == null ? null : String.valueOf(v));
    }

    private static Param level(JsonNode p) {
        if (!p.isObject()) {
            return null;
        }
        String v = p.path("value").isString() ? p.path("value").asString() : "";
        return param(p, AiModels.EFFORT.matcher(v).matches() ? v : null);
    }

    private static Param flag(JsonNode p) {
        if (!p.isObject()) {
            return null;
        }
        return param(p, p.path("value").isBoolean() ? String.valueOf(p.path("value").asBoolean()) : null);
    }

    private static Param param(JsonNode p, String value) {
        boolean omit = p.path("omit").asBoolean(false);
        boolean locked = p.path("locked").asBoolean(false);
        if (!omit && value == null) {
            return null;
        }
        return new Param(omit ? null : value, locked && !omit, omit);
    }
}
