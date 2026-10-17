package io.pacod.cms.studio.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** What the chrome calls a signed-in person: their address, else their name, else their id. */
public final class StudioPrincipals {

    private StudioPrincipals() {
    }

    /** The pacod-id account ({@code usr_…}): the ID token's {@code sub}. */
    public static String subject(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getPrincipal() instanceof OidcUser user ? user.getSubject() : authentication.getName();
    }

    /** The signed-in person's account, from the current security context. */
    public static String currentSubject() {
        return subject(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }

    public static String displayName(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OidcUser user) {
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                return user.getEmail();
            }
            if (user.getFullName() != null && !user.getFullName().isBlank()) {
                return user.getFullName();
            }
            return user.getSubject();
        }
        return authentication.getName();
    }
}
