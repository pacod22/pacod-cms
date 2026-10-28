package io.pacod.cms.site.data;

import io.pacod.cms.core.data.SiteSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The website's public origin -- scheme, host and port, no trailing slash -- that every absolute
 * address the site writes begins with: {@code og:url} and the canonical links, the sitemap, the
 * feed.
 *
 * <p>{@link SiteSettings#getBaseUrl()} when it is filled in, else the origin the request arrived
 * at, so a website that has not set one still names a host. One rule in one place: the head, the
 * sitemap and the feed must agree about it, or a crawler is told two addresses for one page.
 */
public final class SiteOrigin {

    private SiteOrigin() {
    }

    /** The base URL, else {@code requestOrigin}; {@code ""} when both are blank. */
    public static String of(SiteSettings settings, String requestOrigin) {
        String base = settings != null ? settings.baseUrlOrEmpty() : "";
        return !base.isEmpty() ? base : stripTrailingSlash(requestOrigin);
    }

    public static String of(SiteSettings settings, HttpServletRequest request) {
        return of(settings, ofRequest(request));
    }

    /** Scheme, host and port as the reader sees them: forwarded headers are already applied. */
    public static String ofRequest(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request)
                .replacePath(null).replaceQuery(null).build().toUriString();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
