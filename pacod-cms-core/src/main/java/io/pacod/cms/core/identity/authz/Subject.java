package io.pacod.cms.core.identity.authz;

import java.util.List;

/** Who is checked: a person's account, which also holds the implicit roles, or a service account. */
public record Subject(Kind kind, List<Binding> bindings) {

    public enum Kind { USER, SERVICE }

    public Subject {
        bindings = List.copyOf(bindings);
    }

    public static Subject user(List<Binding> bindings) {
        return new Subject(Kind.USER, bindings);
    }
}
