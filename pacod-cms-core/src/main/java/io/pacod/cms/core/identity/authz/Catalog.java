package io.pacod.cms.core.identity.authz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every product's manifest, and the checks pacod-id's rules engine answers over them.
 *
 * <p>A Java port of {@code pacod_id.authz.Catalog}. Scopes are strings, {@code platform} or
 * {@code <type>:<id>}; a path lists a scope's ancestors root first, the scope last:
 * {@code [platform, "organisation:o1", "cms.website:demo"]}. Permissions and roles are qualified
 * with their product. The semantics, and every edge of them, are fixed by the vectors in
 * {@code policy/engine-vectors/} -- this class follows them, not the other way round.
 */
public final class Catalog {

    /** Why {@link #canGrant} refused. */
    public enum GrantError {
        UNKNOWN_ROLE, IMPLICIT_ROLE, BAD_PATH, SCOPE_TYPE_NOT_ALLOWED, NOT_PERMITTED;

        /** The code the vectors and pacod-id's API use. */
        public String code() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Map<String, Manifest> manifests = new LinkedHashMap<>();

    public Catalog(Manifest... manifests) {
        for (Manifest m : manifests) {
            insert(m);
        }
    }

    public Catalog(List<Manifest> manifests) {
        manifests.forEach(this::insert);
    }

    /** Adds a manifest, replacing the product's earlier one. */
    public void insert(Manifest manifest) {
        manifests.put(manifest.product(), manifest);
    }

    public Manifest manifest(String product) {
        return manifests.get(product);
    }

    // -------------------------------------------------------------------------- resolution

    /** A defined role, or the scope types of a custom role; null when the role does not exist. */
    private Object resolve(String role) {
        String[] parts = Names.split(role);
        if (parts == null) {
            return null;
        }
        Manifest manifest = manifests.get(parts[0]);
        if (manifest == null) {
            return null;
        }
        if (parts[1].startsWith("custom.")) {
            String name = parts[1].substring("custom.".length());
            return Names.isIdent(name) && manifest.customAt() != null ? manifest.customAt() : null;
        }
        return manifest.role(role);
    }

    private static List<String> atOf(Object resolved) {
        if (resolved instanceof Role r) {
            return r.at();
        }
        @SuppressWarnings("unchecked")
        List<String> at = (List<String>) resolved;
        return at;
    }

    private String parentType(String kind) {
        if (Names.ORGANISATION.equals(kind)) {
            return Names.PLATFORM;
        }
        String[] parts = Names.split(kind);
        if (parts == null) {
            return null;
        }
        Manifest manifest = manifests.get(parts[0]);
        return manifest == null ? null : manifest.scopeParent(kind);
    }

    /** Whether a path leads from the platform through the scope types, one level at a time. */
    public boolean isValidPath(List<String> path) {
        if (path == null || path.isEmpty() || !Names.PLATFORM.equals(path.getFirst())) {
            return false;
        }
        String previous = Names.PLATFORM;
        for (String scope : path.subList(1, path.size())) {
            String kind = Names.scopeKind(scope);
            if (kind == null || kind.equals(Names.PLATFORM) || !previous.equals(parentType(kind))) {
                return false;
            }
            previous = kind;
        }
        return true;
    }

    /** The role of a binding when the binding counts; every check ignores the others. */
    private Object effective(String role, List<String> path) {
        if (path == null || path.isEmpty() || !Names.PLATFORM.equals(path.getFirst())) {
            return null;
        }
        String kind = Names.scopeKind(path.getLast());
        if (kind == null) {
            return null;
        }
        Object resolved = resolve(role);
        if (resolved == null) {
            return null;
        }
        return atOf(resolved).contains(kind) ? resolved : null;
    }

    private List<Role> implicitRoles(Subject subject, String product) {
        if (subject.kind() != Subject.Kind.USER) {
            return List.of();
        }
        Manifest manifest = manifests.get(product);
        if (manifest == null) {
            return List.of();
        }
        List<Role> out = new ArrayList<>();
        for (Role r : manifest.roles()) {
            if (r.implicit()) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean grants(String product, String local, Role role) {
        return role.name().substring(0, role.name().indexOf('.')).equals(product) && role.grantsLocal(local);
    }

    private static boolean isPrefix(List<String> prefix, List<String> path) {
        if (prefix.isEmpty() || prefix.size() > path.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------ checks

    /** Whether the subject may do {@code permission} (qualified) at the scope with path {@code at}. */
    public boolean allowed(Subject subject, String permission, List<String> at) {
        String[] parts = Names.split(permission);
        if (parts == null) {
            return false;
        }
        for (Binding b : subject.bindings()) {
            if (effective(b.role(), b.path()) instanceof Role r && isPrefix(b.path(), at)
                    && grants(parts[0], parts[1], r)) {
                return true;
            }
        }
        if (at.isEmpty() || !Names.PLATFORM.equals(at.getFirst())) {
            return false;
        }
        for (Role r : implicitRoles(subject, parts[0])) {
            if (grants(parts[0], parts[1], r)) {
                return true;
            }
        }
        return false;
    }

    /** Whether some binding of the subject grants {@code permission}, wherever it is held. */
    public boolean allowedSomewhere(Subject subject, String permission) {
        String[] parts = Names.split(permission);
        if (parts == null) {
            return false;
        }
        for (Binding b : subject.bindings()) {
            if (effective(b.role(), b.path()) instanceof Role r && grants(parts[0], parts[1], r)) {
                return true;
            }
        }
        for (Role r : implicitRoles(subject, parts[0])) {
            if (grants(parts[0], parts[1], r)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the subject holds exactly {@code role} at exactly {@code scope}. */
    public boolean hasRole(Subject subject, String role, String scope) {
        for (Binding b : subject.bindings()) {
            if (b.role().equals(role) && !b.path().isEmpty() && b.path().getLast().equals(scope)
                    && effective(b.role(), b.path()) != null) {
                return true;
            }
        }
        String[] parts = Names.split(role);
        if (!Names.PLATFORM.equals(scope) || parts == null) {
            return false;
        }
        for (Role r : implicitRoles(subject, parts[0])) {
            if (r.name().equals(role)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the subject holds some role at exactly {@code scope}. */
    public boolean holdsAnyAt(Subject subject, String scope) {
        for (Binding b : subject.bindings()) {
            if (!b.path().isEmpty() && b.path().getLast().equals(scope) && effective(b.role(), b.path()) != null) {
                return true;
            }
        }
        return false;
    }

    /** Whether some binding of the subject sits on {@code at}, above it or below it. */
    public boolean touches(Subject subject, List<String> at) {
        for (Binding b : subject.bindings()) {
            if (effective(b.role(), b.path()) != null && (isPrefix(b.path(), at) || isPrefix(at, b.path()))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code actor} may give (or take) {@code role} at the scope with path {@code at}. */
    public Optional<GrantError> canGrant(Subject actor, String role, List<String> at) {
        Object resolved = resolve(role);
        if (resolved == null) {
            return Optional.of(GrantError.UNKNOWN_ROLE);
        }
        if (resolved instanceof Role r && r.implicit()) {
            return Optional.of(GrantError.IMPLICIT_ROLE);
        }
        if (!isValidPath(at)) {
            return Optional.of(GrantError.BAD_PATH);
        }
        if (!atOf(resolved).contains(Names.scopeKind(at.getLast()))) {
            return Optional.of(GrantError.SCOPE_TYPE_NOT_ALLOWED);
        }
        Manifest manifest = manifests.get(role.substring(0, role.indexOf('.')));
        return allowed(actor, manifest.grantPermission(role), at) ? Optional.empty() : Optional.of(GrantError.NOT_PERMITTED);
    }

    /**
     * Refuses a change of bindings, from {@code before} to {@code after}, that leaves a scope
     * without a holder of a protected role, unless the role's bypass permission lets {@code actor}
     * do it. The first violation, ordered by role, then scope; empty when the change may go.
     */
    public Optional<Violation> checkProtected(Subject actor, List<HeldBinding> before, List<HeldBinding> after) {
        // Keyed role, then scope, so the iteration order is the report order.
        TreeMap<String, TreeMap<String, Map.Entry<Role, List<String>>>> checked = new TreeMap<>();
        for (HeldBinding b : before) {
            if (effective(b.role(), b.path()) instanceof Role r && r.protection() != null) {
                checked.computeIfAbsent(b.role(), k -> new TreeMap<>())
                        .putIfAbsent(b.path().getLast(), Map.entry(r, b.path()));
            }
        }
        for (var byRole : checked.entrySet()) {
            String name = byRole.getKey();
            for (var byScope : byRole.getValue().entrySet()) {
                String scope = byScope.getKey();
                Role role = byScope.getValue().getKey();
                List<String> path = byScope.getValue().getValue();
                boolean left = false;
                for (HeldBinding b : after) {
                    if (b.role().equals(name) && !b.path().isEmpty() && b.path().getLast().equals(scope)
                            && effective(b.role(), b.path()) != null) {
                        left = true;
                        break;
                    }
                }
                if (left) {
                    continue;
                }
                boolean bypassed = role.protection().bypass() != null
                        && allowed(actor, role.protection().bypass(), path);
                if (!bypassed) {
                    return Optional.of(new Violation(name, scope));
                }
            }
        }
        return Optional.empty();
    }
}
