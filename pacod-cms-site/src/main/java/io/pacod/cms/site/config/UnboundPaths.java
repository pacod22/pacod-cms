package io.pacod.cms.site.config;

/**
 * The requests that are about no website, so no filter that binds, previews, prints or counts may
 * act on them: the container's health check, the site's own CSS and JavaScript (the same for every
 * website, out of the jar), and the TLS proxy's question whether a host is a website's
 * ({@code TlsAskController}), which arrives with the proxy's Host, not a reader's.
 *
 * <p>One rule for the four filters: a path exempted from the binding but not from a filter after it
 * reaches that filter with no website bound, where {@code TenantContext.current()} throws.
 */
public final class UnboundPaths {

    public static final String HEALTH = "/web/v1/health";
    public static final String TLS_ASK = "/_internal/tls-ask";

    private UnboundPaths() {
    }

    /** @param path the request's path within the application */
    public static boolean matches(String path) {
        return path != null && (path.equals(HEALTH) || path.equals(TLS_ASK) || path.startsWith("/site-assets/"));
    }
}
