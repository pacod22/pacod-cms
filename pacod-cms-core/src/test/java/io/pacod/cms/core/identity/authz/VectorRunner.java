package io.pacod.cms.core.identity.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs a file of rules-engine vectors (format: {@code authz/engine-vectors/README.md}) -- the
 * engine's own, or the product's ({@code authz/vectors/cms.yaml}). A Java port of
 * {@code pacod_id.vectors}.
 */
final class VectorRunner {

    record Result(int checks, List<String> failures) {
    }

    private VectorRunner() {
    }

    @SuppressWarnings("unchecked")
    static Result run(String text, Function<String, Manifest> load) {
        Map<String, Object> file = (Map<String, Object>) Yaml12.load(text);
        List<Manifest> manifests = new ArrayList<>();
        for (String name : (List<String>) file.get("manifests")) {
            manifests.add(load.apply(name));
        }
        Catalog catalog = new Catalog(manifests);
        int checks = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) file.get("cases")) {
            Map<String, Object> s = (Map<String, Object>) (c.get("subject") != null ? c.get("subject") : c.get("actor"));
            Subject subject = subject(s);
            for (Map<String, Object> check : (List<Map<String, Object>>) c.get("checks")) {
                checks++;
                try {
                    String got = one(catalog, subject, check);
                    if (got != null) {
                        failures.add(c.get("name") + ": " + check + " gave " + got);
                    }
                } catch (RuntimeException e) {
                    failures.add(c.get("name") + ": " + e);
                }
            }
        }
        return new Result(checks, failures);
    }

    @SuppressWarnings("unchecked")
    private static String one(Catalog c, Subject subject, Map<String, Object> check) {
        Object expect = check.get("expect");
        if (check.containsKey("allowed")) {
            return bool(c.allowed(subject, (String) check.get("allowed"), (List<String>) check.get("at")), expect);
        }
        if (check.containsKey("has_role")) {
            return bool(c.hasRole(subject, (String) check.get("has_role"), (String) check.get("scope")), expect);
        }
        if (check.containsKey("holds_any_at")) {
            return bool(c.holdsAnyAt(subject, (String) check.get("holds_any_at")), expect);
        }
        if (check.containsKey("allowed_somewhere")) {
            return bool(c.allowedSomewhere(subject, (String) check.get("allowed_somewhere")), expect);
        }
        if (check.containsKey("touches")) {
            return bool(c.touches(subject, (List<String>) check.get("touches")), expect);
        }
        if (check.containsKey("can_grant")) {
            String got = c.canGrant(subject, (String) check.get("can_grant"), (List<String>) check.get("at"))
                    .map(Catalog.GrantError::code).orElse("ok");
            return got.equals(expect) ? null : got;
        }
        if (check.containsKey("check_protected")) {
            Map<String, Object> p = (Map<String, Object>) check.get("check_protected");
            Optional<Violation> v = c.checkProtected(subject, held(p.get("before")), held(p.get("after")));
            if ("ok".equals(expect)) {
                return v.isEmpty() ? null : v.get().toString();
            }
            Map<String, String> want = (Map<String, String>) ((Map<String, Object>) expect).get("violation");
            boolean holds = v.isPresent() && v.get().role().equals(want.get("role"))
                    && v.get().scope().equals(want.get("scope"));
            return holds ? null : String.valueOf(v.orElse(null));
        }
        throw new IllegalArgumentException("unknown check " + check);
    }

    private static String bool(boolean got, Object expect) {
        return Boolean.valueOf(got).equals(expect) ? null : String.valueOf(got);
    }

    @SuppressWarnings("unchecked")
    private static Subject subject(Map<String, Object> s) {
        List<Binding> bindings = new ArrayList<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) s.getOrDefault("bindings", List.of())) {
            bindings.add(new Binding((String) b.get("role"), (List<String>) b.get("path")));
        }
        Subject.Kind kind = "service".equals(s.get("kind")) ? Subject.Kind.SERVICE : Subject.Kind.USER;
        return new Subject(kind, bindings);
    }

    @SuppressWarnings("unchecked")
    private static List<HeldBinding> held(Object list) {
        List<HeldBinding> out = new ArrayList<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) list) {
            out.add(new HeldBinding((String) b.get("principal"), (String) b.get("role"), (List<String>) b.get("path")));
        }
        return out;
    }
}
