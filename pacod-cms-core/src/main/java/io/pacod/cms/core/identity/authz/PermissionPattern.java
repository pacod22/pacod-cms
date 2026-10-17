package io.pacod.cms.core.identity.authz;

/** {@code *}, {@code x.*} (every permission below {@code x}), or an exact permission name. */
public record PermissionPattern(Kind kind, String value) {

    public enum Kind { ALL, PREFIX, EXACT }

    /** Matches a local permission name -- without its product. */
    public boolean matches(String local) {
        return switch (kind) {
            case ALL -> true;
            // value keeps its trailing dot: "reports." covers "reports.view", not "reporting".
            case PREFIX -> local.length() > value.length() && local.startsWith(value);
            case EXACT -> local.equals(value);
        };
    }

    static PermissionPattern of(String s) {
        if ("*".equals(s)) {
            return new PermissionPattern(Kind.ALL, "");
        }
        if (s.endsWith(".*") && Names.isPermissionName(s.substring(0, s.length() - 2))) {
            return new PermissionPattern(Kind.PREFIX, s.substring(0, s.length() - 1));
        }
        if (Names.isPermissionName(s)) {
            return new PermissionPattern(Kind.EXACT, s);
        }
        throw new ManifestException("bad_permission", "'" + s + "' is not a valid permission");
    }
}
