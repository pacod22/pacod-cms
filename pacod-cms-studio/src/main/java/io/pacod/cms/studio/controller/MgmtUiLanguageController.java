package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.PlatformScreen;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.site.StudioPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.LocaleResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The language switch in the topbar and on the sign-in page: sets the {@code mgmt-lang} cookie and
 * goes back to the screen the operator was on.
 *
 * <p>An endpoint rather than a {@code LocaleChangeInterceptor}, because the interceptor works off a
 * query parameter on every request, and {@code lang} -- the obvious name -- already means content
 * language on three of this console's screens. Four things about it are load-bearing:
 * <ul>
 *   <li><b>Under {@code /mgmt/}</b>, because that is the console's security matcher and
 *       {@code StudioPaths}' list of platform screens; and a literal segment, so it beats
 *       {@link MgmtHomeController}'s {@code /mgmt/**} catch-all.</li>
 *   <li><b>{@code permitAll}</b> in {@code MgmtSecurityConfig}: the sign-in page offers it too, and
 *       an authenticated-only URL would be saved by the request cache, so the operator would land
 *       on it after signing in.</li>
 *   <li><b>The way back is the {@code Referer}, reduced to a path under {@code /mgmt/}.</b> Only the
 *       path and query are ever written into {@code Location}, so a foreign Referer cannot turn this
 *       into an open redirect; {@code //} and {@code /\} (another host to a browser), {@code ..},
 *       control characters and the switch itself are refused, and anything refused goes to
 *       {@code /mgmt/}.</li>
 *   <li><b>{@code Location} is written by hand</b>, not through {@code redirect:}, which expands
 *       {@code {...}} in the URL as template variables -- and a search term can contain a brace.</li>
 * </ul>
 */
@PlatformScreen
@Controller
public class MgmtUiLanguageController {

    static final String HOME = "/mgmt/";
    static final String PREFIX = "/mgmt/";
    static final String SELF = "/mgmt/ui-language";

    private final LocaleResolver localeResolver;

    public MgmtUiLanguageController(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @GetMapping("/mgmt/ui-language/{code}")
    public ResponseEntity<Void> switchLanguage(@PathVariable String code,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        if (!MgmtText.isSupported(code)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        localeResolver.setLocale(request, response, Locale.of(code));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, backTo(request.getHeader(HttpHeaders.REFERER)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /** The path and query of {@code referer} when it is a console screen, else {@link #HOME}. */
    static String backTo(String referer) {
        if (referer == null || referer.isBlank()) {
            return HOME;
        }
        for (int i = 0; i < referer.length(); i++) {
            char c = referer.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return HOME;
            }
        }
        URI uri;
        try {
            uri = new URI(referer);
        } catch (URISyntaxException e) {
            return HOME;
        }
        String path = uri.getRawPath();
        // A website's screen comes back with its prefix; the checks below are about the screen.
        String screen = path == null ? null : StudioPaths.withinSite(path);
        if (path == null || screen == null
                || !screen.startsWith(PREFIX)
                || path.startsWith("//")
                || path.contains("/..")
                || path.contains("%2e") || path.contains("%2E")
                || screen.startsWith(SELF)) {
            return HOME;
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }
}
