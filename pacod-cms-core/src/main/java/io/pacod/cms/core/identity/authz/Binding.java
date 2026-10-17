package io.pacod.cms.core.identity.authz;

import java.util.List;

/** A role held at a scope, with the scope's path, root first: the scope is the last element. */
public record Binding(String role, List<String> path) {

    public Binding {
        path = List.copyOf(path);
    }

    /** The scope the role is held at. */
    public String scope() {
        return path.isEmpty() ? null : path.getLast();
    }
}
