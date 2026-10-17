package io.pacod.cms.site.render;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * Whether this request is the studio's draft preview -- {@code ?_preview=<token>}, checked by
 * {@code PreviewFilter} and stored as a request attribute that everything else reads.
 *
 * <p>A preview renders the draft layouts and theme, read past every cache; settings and menus
 * read fresh; no tracking ids ({@code SiteChrome.withoutTracking}); and counts for nothing. The
 * page carries the token in a meta tag, from which {@code preview.js} adds it to every same-origin
 * link and GET form -- third-party cookies are blocked in the studio's frame, so the address is
 * the only carrier -- and {@code site.js} sends no beacon.
 *
 * @see io.pacod.cms.core.design.PreviewTokens
 */
public final class SitePreview {

    /** The query parameter that carries the token. */
    public static final String PARAMETER = "_preview";
    public static final String ATTRIBUTE = SitePreview.class.getName();

    /** @param templateSet the set to render with instead of the website's, or null */
    public record Preview(String token, String templateSet) {
    }

    private SitePreview() {
    }

    public static Optional<Preview> of(HttpServletRequest request) {
        return request != null && request.getAttribute(ATTRIBUTE) instanceof Preview preview
                ? Optional.of(preview) : Optional.empty();
    }

    /** The same for code that is not handed the request; off a request thread, none. */
    public static Optional<Preview> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
                && attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Preview preview
                ? Optional.of(preview) : Optional.empty();
    }

    /**
     * The token in a raw query string, or null. Read from the query string alone, never the
     * body: {@code getParameter} would parse a POST's form body to find it.
     */
    public static String tokenIn(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith(PARAMETER + "=")) {
                String value = pair.substring(PARAMETER.length() + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
