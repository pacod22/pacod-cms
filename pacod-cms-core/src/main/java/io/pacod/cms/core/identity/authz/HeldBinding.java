package io.pacod.cms.core.identity.authz;

import java.util.List;

/** A binding with its holder, for {@link Catalog#checkProtected}. */
public record HeldBinding(String principal, String role, List<String> path) {

    public HeldBinding {
        path = List.copyOf(path);
    }
}
