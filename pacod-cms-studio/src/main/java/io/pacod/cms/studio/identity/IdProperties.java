package io.pacod.cms.studio.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How the studio reaches pacod-id ({@code pacod.id.*}).
 *
 * @param enabled        whether the studio follows pacod-id's feed, pushes its catalogue and keeps
 *                       its scopes in step. Off in the tests and in the one-off {@code --create-site}
 *                       run; sign-in itself is always through pacod-id.
 * @param issuer         the public issuer, as browsers reach it: {@code http://id.localhost:8000}
 * @param internalUrl    pacod-id's internal listener, as this container reaches it:
 *                       {@code http://pacod-id:8071} -- token, keys, {@code /internal/v1}, {@code /admin/v1}
 * @param studioOrigin   this studio's own origin as browsers reach it, for the absolute redirect
 *                       URIs pacod-id has registered
 * @param maxStaleness   how old the feed's last contact may be before the studio's screens answer
 *                       503 rather than decide with roles that may have changed
 */
@ConfigurationProperties("pacod.id")
public record IdProperties(
        @DefaultValue("true") boolean enabled,
        String issuer,
        String internalUrl,
        @DefaultValue("pacod-cms-studio") String studioClientId,
        String studioSecret,
        @DefaultValue("pacod-cms-service") String serviceClientId,
        String serviceSecret,
        String studioOrigin,
        @DefaultValue("60s") Duration maxStaleness) {

    public IdProperties {
        issuer = trimSlash(issuer);
        internalUrl = trimSlash(internalUrl);
        studioOrigin = trimSlash(studioOrigin);
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
