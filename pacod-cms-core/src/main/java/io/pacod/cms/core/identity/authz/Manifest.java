package io.pacod.cms.core.identity.authz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A product's roles, permissions and scope types, checked, with qualified names.
 *
 * <p>A Java port of {@code pacod_id.authz.Manifest} (pacod-id's Python port; the Rust and
 * TypeScript ports are the others). The vectors under {@code policy/engine-vectors/}, vendored into
 * this module's tests, are the specification and keep the ports in step -- including the
 * <em>order</em> in which a manifest is checked, which decides which error a doubly broken
 * manifest reports: product, scope type names, parents, cycles, roles (sorted), custom roles,
 * grant.
 */
public final class Manifest {

    private static final Set<String> RESERVED_PRODUCTS = Set.of(Names.PLATFORM, Names.ORGANISATION, "custom");

    private final String product;
    private final Map<String, String> scopeParents;
    private final Map<String, Role> roles;
    private final List<String> customAt;
    private final Map<String, String> grant;
    private final String grantDefault;
    private final Map<String, Object> source;

    private Manifest(String product, Map<String, String> scopeParents, Map<String, Role> roles,
                     List<String> customAt, Map<String, String> grant, String grantDefault,
                     Map<String, Object> source) {
        this.product = product;
        this.scopeParents = Collections.unmodifiableMap(scopeParents);
        this.roles = Collections.unmodifiableMap(roles);
        this.customAt = customAt == null ? null : List.copyOf(customAt);
        this.grant = Collections.unmodifiableMap(grant);
        this.grantDefault = grantDefault;
        this.source = Collections.unmodifiableMap(source);
    }

    /** Reads and checks a manifest written in YAML 1.2. */
    public static Manifest fromYaml(String text) {
        Object raw;
        try {
            raw = Yaml12.load(text);
        } catch (RuntimeException e) {
            throw new ManifestException("bad_manifest", "not YAML: " + e.getMessage());
        }
        return fromSource(raw);
    }

    /** Checks a manifest already parsed into maps and lists, and qualifies its names. */
    @SuppressWarnings("unchecked")
    public static Manifest fromSource(Object raw) {
        Map<String, Object> src = readSource(raw);
        String product = (String) src.get("product");
        if (!Names.isIdent(product) || RESERVED_PRODUCTS.contains(product)) {
            throw new ManifestException("bad_product", "'" + product + "' is not a valid product name");
        }

        Map<String, Map<String, String>> scopeTypes =
                (Map<String, Map<String, String>>) src.getOrDefault("scope_types", Map.of());
        for (String name : new TreeSet<>(scopeTypes.keySet())) {
            if (!Names.isIdent(name) || name.equals(Names.PLATFORM) || name.equals(Names.ORGANISATION)) {
                throw new ManifestException("bad_name", "'" + name + "' is not a valid name");
            }
        }

        Map<String, String> scopeParents = new TreeMap<>();
        for (String name : new TreeSet<>(scopeTypes.keySet())) {
            String parent = resolveType(product, scopeTypes, scopeTypes.get(name).get("parent"));
            if (parent == null) {
                throw new ManifestException("unknown_parent", "scope type " + name + " has an unknown parent");
            }
            scopeParents.put(product + "." + name, parent);
        }
        for (String start : scopeParents.keySet()) {
            Set<String> seen = new HashSet<>();
            String at = start;
            while (at != null && scopeParents.containsKey(at)) {
                if (!seen.add(at)) {
                    throw new ManifestException("parent_cycle", "scope type " + start + " is its own ancestor");
                }
                at = scopeParents.get(at);
            }
        }

        Map<String, Role> roles = new TreeMap<>();
        Map<String, Map<String, Object>> roleSources =
                (Map<String, Map<String, Object>>) src.getOrDefault("roles", Map.of());
        for (String name : new TreeSet<>(roleSources.keySet())) {
            Map<String, Object> r = roleSources.get(name);
            if (!Names.isIdent(name) || name.equals("custom")) {
                throw new ManifestException("bad_name", "'" + name + "' is not a valid name");
            }
            List<String> at = new ArrayList<>();
            for (String kind : (List<String>) r.get("at")) {
                String resolved = resolveType(product, scopeTypes, kind);
                if (resolved == null) {
                    throw new ManifestException("unknown_scope_type",
                            "role " + name + " names an unknown scope type " + kind);
                }
                at.add(resolved);
            }
            List<PermissionPattern> patterns = new ArrayList<>();
            for (String p : (List<String>) r.getOrDefault("permissions", List.of())) {
                patterns.add(PermissionPattern.of(p));
            }
            Role.Protection protection = null;
            Object prot = r.getOrDefault("protected", Boolean.FALSE);
            if (Boolean.TRUE.equals(prot)) {
                protection = new Role.Protection(null);
            } else if (prot instanceof Map<?, ?> p) {
                String bypass = (String) p.get("bypass");
                if (!Names.isPermissionName(bypass)) {
                    throw new ManifestException("bad_permission", "'" + bypass + "' is not a valid permission");
                }
                protection = new Role.Protection(product + "." + bypass);
            }
            boolean implicit = Boolean.TRUE.equals(r.get("implicit"));
            if (implicit && !at.equals(List.of(Names.PLATFORM))) {
                throw new ManifestException("implicit_not_platform",
                        "implicit role " + name + " must be bound at the platform");
            }
            String qualified = product + "." + name;
            roles.put(qualified, new Role(qualified, at, patterns, protection, implicit));
        }

        List<String> customAt = null;
        if (src.containsKey("custom_roles")) {
            customAt = new ArrayList<>();
            for (String kind : ((Map<String, List<String>>) src.get("custom_roles")).get("at")) {
                String resolved = resolveType(product, scopeTypes, kind);
                if (resolved == null) {
                    throw new ManifestException("unknown_scope_type", "custom roles name an unknown scope type " + kind);
                }
                customAt.add(resolved);
            }
        }

        Map<String, String> grant = new TreeMap<>();
        String grantDefault = null;
        Map<String, String> grantSource = (Map<String, String>) src.getOrDefault("grant", Map.of());
        for (String role : new TreeSet<>(grantSource.keySet())) {
            String permission = grantSource.get(role);
            if (!Names.isPermissionName(permission)) {
                throw new ManifestException("bad_permission", "'" + permission + "' is not a valid permission");
            }
            if (role.equals("default")) {
                grantDefault = product + "." + permission;
            } else if (roleSources.containsKey(role)) {
                grant.put(product + "." + role, product + "." + permission);
            } else {
                throw new ManifestException("unknown_grant_role", "grant names an unknown role " + role);
            }
        }
        if (grantDefault == null) {
            throw new ManifestException("missing_grant_default", "grant has no default");
        }

        return new Manifest(product, scopeParents, roles, customAt, grant, grantDefault, src);
    }

    /** {@code platform}, {@code organisation}, or a type of this manifest, qualified; null when unknown. */
    private static String resolveType(String product, Map<String, ?> scopeTypes, String name) {
        if (Names.PLATFORM.equals(name) || Names.ORGANISATION.equals(name)) {
            return name;
        }
        if (name == null) {
            return null;
        }
        String local = name.startsWith(product + ".") ? name.substring(product.length() + 1) : name;
        return scopeTypes.containsKey(local) ? product + "." + local : null;
    }

    // ------------------------------------------------------------------ shape of untrusted input

    private static void shape(boolean ok, String what) {
        if (!ok) {
            throw new ManifestException("bad_manifest", "the manifest does not have the expected shape: " + what);
        }
    }

    private static void onlyKeys(Map<?, ?> o, Set<String> allowed, String where) {
        for (Object key : o.keySet()) {
            shape(allowed.contains(key), "unknown field " + key + " in " + where);
        }
    }

    private static List<String> strings(Object v, String what) {
        shape(v instanceof List<?> list && list.stream().allMatch(x -> x instanceof String),
                what + " must be a list of strings");
        List<String> out = new ArrayList<>();
        for (Object x : (List<?>) v) {
            out.add((String) x);
        }
        return out;
    }

    /** Checks the shape of untrusted input, as serde does for the Rust port. */
    private static Map<String, Object> readSource(Object raw) {
        shape(raw instanceof Map<?, ?>, "not a map");
        Map<?, ?> in = (Map<?, ?>) raw;
        onlyKeys(in, Set.of("product", "scope_types", "roles", "custom_roles", "grant"), "the manifest");
        shape(in.get("product") instanceof String, "product must be a string");
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("product", in.get("product"));

        if (in.containsKey("scope_types")) {
            shape(in.get("scope_types") instanceof Map<?, ?>, "scope_types must be a map");
            Map<String, Map<String, String>> types = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) in.get("scope_types")).entrySet()) {
                shape(e.getKey() instanceof String && e.getValue() instanceof Map<?, ?>, "scope type " + e.getKey());
                Map<?, ?> st = (Map<?, ?>) e.getValue();
                onlyKeys(st, Set.of("parent"), "scope type " + e.getKey());
                shape(st.get("parent") instanceof String, "scope type " + e.getKey() + " needs a parent");
                types.put((String) e.getKey(), Map.of("parent", (String) st.get("parent")));
            }
            src.put("scope_types", types);
        }

        if (in.containsKey("roles")) {
            shape(in.get("roles") instanceof Map<?, ?>, "roles must be a map");
            Map<String, Map<String, Object>> roles = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) in.get("roles")).entrySet()) {
                Object name = e.getKey();
                shape(name instanceof String && e.getValue() instanceof Map<?, ?>, "role " + name);
                Map<?, ?> r = (Map<?, ?>) e.getValue();
                onlyKeys(r, Set.of("at", "permissions", "protected", "implicit"), "role " + name);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("at", strings(r.get("at"), "role " + name + ": at"));
                if (r.containsKey("permissions")) {
                    out.put("permissions", strings(r.get("permissions"), "role " + name + ": permissions"));
                }
                if (r.containsKey("protected")) {
                    Object p = r.get("protected");
                    if (p instanceof Boolean) {
                        out.put("protected", p);
                    } else {
                        shape(p instanceof Map<?, ?>, "role " + name + ": protected");
                        Map<?, ?> pm = (Map<?, ?>) p;
                        onlyKeys(pm, Set.of("bypass"), "role " + name + ": protected");
                        shape(pm.get("bypass") instanceof String, "role " + name + ": protected.bypass");
                        out.put("protected", Map.of("bypass", pm.get("bypass")));
                    }
                }
                if (r.containsKey("implicit")) {
                    shape(r.get("implicit") instanceof Boolean, "role " + name + ": implicit");
                    out.put("implicit", r.get("implicit"));
                }
                roles.put((String) name, out);
            }
            src.put("roles", roles);
        }

        if (in.containsKey("custom_roles")) {
            Object c = in.get("custom_roles");
            shape(c instanceof Map<?, ?>, "custom_roles must be a map");
            onlyKeys((Map<?, ?>) c, Set.of("at"), "custom_roles");
            src.put("custom_roles", Map.of("at", strings(((Map<?, ?>) c).get("at"), "custom_roles: at")));
        }

        if (in.containsKey("grant")) {
            Object g = in.get("grant");
            shape(g instanceof Map<?, ?>, "grant must be a map");
            Map<String, String> grant = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) g).entrySet()) {
                shape(e.getKey() instanceof String && e.getValue() instanceof String, "grant." + e.getKey() + " must be a string");
                grant.put((String) e.getKey(), (String) e.getValue());
            }
            src.put("grant", grant);
        }
        return src;
    }

    // --------------------------------------------------------------------------------- reading

    public String product() {
        return product;
    }

    /** The roles, sorted by qualified name. */
    public List<Role> roles() {
        return List.copyOf(roles.values());
    }

    public Role role(String qualified) {
        return roles.get(qualified);
    }

    /** The qualified scope types the product registers, with their parents, sorted. */
    public Map<String, String> scopeTypes() {
        return scopeParents;
    }

    public String scopeParent(String kind) {
        return scopeParents.get(kind);
    }

    /** The qualified scope types custom roles may be bound at; null when the manifest has none. */
    public List<String> customAt() {
        return customAt;
    }

    /** The qualified permission needed to give or take {@code role}. */
    public String grantPermission(String role) {
        return grant.getOrDefault(role, grantDefault);
    }

    /** The manifest as written (shape-checked), to send it on in the catalogue push. */
    public Map<String, Object> source() {
        return source;
    }
}
