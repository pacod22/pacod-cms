package io.pacod.cms.core.identity.authz;

import java.util.regex.Pattern;

/** The name rules of the engine's specification. */
final class Names {

    static final String PLATFORM = "platform";
    static final String ORGANISATION = "organisation";

    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    private Names() {
    }

    /** A lowercase identifier: {@code [a-z][a-z0-9_]*}. */
    static boolean isIdent(String s) {
        return s != null && IDENT.matcher(s).matches();
    }

    /** Identifiers joined by dots. */
    static boolean isPermissionName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (String part : s.split("\\.", -1)) {
            if (!isIdent(part)) {
                return false;
            }
        }
        return true;
    }

    /** {@code product.rest}, or null when there is no dot. */
    static String[] split(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.indexOf('.');
        return dot < 0 ? null : new String[]{name.substring(0, dot), name.substring(dot + 1)};
    }

    /** The type of a scope written as text: {@code platform}, or the part before {@code :}. */
    static String scopeKind(String scope) {
        if (PLATFORM.equals(scope)) {
            return PLATFORM;
        }
        if (scope == null) {
            return null;
        }
        int colon = scope.indexOf(':');
        if (colon <= 0 || colon == scope.length() - 1) {
            return null;
        }
        String kind = scope.substring(0, colon);
        return PLATFORM.equals(kind) ? null : kind;
    }
}
