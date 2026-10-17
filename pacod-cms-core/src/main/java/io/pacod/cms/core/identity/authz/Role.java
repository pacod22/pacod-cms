package io.pacod.cms.core.identity.authz;

import java.util.List;

/**
 * A role of a manifest, with qualified names ({@code cms.editor}, scope types {@code cms.website}).
 *
 * @param at         the scope types it may be bound at: {@code platform}, {@code organisation}, or
 *                   a qualified product type
 * @param protection null when unprotected; see {@link Protection}
 * @param implicit   held at the platform by every person's account without being bound
 */
public record Role(String name, List<String> at, List<PermissionPattern> patterns, Protection protection,
                   boolean implicit) {

    public Role {
        at = List.copyOf(at);
        patterns = List.copyOf(patterns);
    }

    /** Whether one of the role's patterns covers {@code local}, a permission without the product. */
    public boolean grantsLocal(String local) {
        for (PermissionPattern pattern : patterns) {
            if (pattern.matches(local)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A protected role may not be left without a holder at a scope where it had one -- unless the
     * actor is allowed the {@code bypass} permission there. {@code bypass} null: always protected.
     */
    public record Protection(String bypass) {
    }
}
