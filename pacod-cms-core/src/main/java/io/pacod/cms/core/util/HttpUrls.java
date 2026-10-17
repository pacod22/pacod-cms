package io.pacod.cms.core.util;

import java.util.Locale;

/**
 * The one place a stored URL is judged safe to redirect a reader to.
 *
 * <p>In core because the console ({@code pacod-cms-studio}) and the public site
 * ({@code pacod-cms-site}) run in different JVMs and both need it, and it must not be duplicated:
 * a booking's {@code targetUrl} is validated twice, once by {@code MgmtBannerService} on save and
 * once by {@code BannerClickController} before the {@code Location} header is written, and the
 * guarantee is that <b>what is refused on save is exactly what is refused on redirect</b>. Two
 * copies of this method would be two opportunities for that to stop being true.
 *
 * <p>The second check is not redundant. A document written by hand in mongosh, or imported from
 * elsewhere, would otherwise turn {@code /b/&#123;id&#125;} into an open redirect -- and a
 * {@code javascript:} URL into stored XSS.
 */
public final class HttpUrls {

    private HttpUrls() {
    }

    /** Whether a stored target is safe to put in a {@code Location} header. */
    public static boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.strip().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }
}
