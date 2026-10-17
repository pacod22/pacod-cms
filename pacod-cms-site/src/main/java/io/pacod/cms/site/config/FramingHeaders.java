package io.pacod.cms.site.config;

import io.pacod.cms.site.render.SitePreview;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.header.HeaderWriter;

import java.util.regex.Pattern;

/**
 * Who may frame what the public site answers, as {@code Content-Security-Policy: frame-ancestors}.
 *
 * <p><b>Not Spring Security's {@code X-Frame-Options: DENY}</b>, which forbids same-origin framing
 * too: every HTML5 booking is an iframe of {@code /resources/banners/…} on its own website, and
 * {@code MediaController} serves those paths through the security chain, whose default
 * {@code DENY} renders each of them blank. Nothing on the server notices -- the file is served,
 * 200 -- only a reader's browser refuses it.
 *
 * <ul>
 *   <li>Everything: {@code frame-ancestors 'self'}, and {@code X-Frame-Options: SAMEORIGIN} for a
 *       client that predates CSP.</li>
 *   <li>{@code /resources/banners/**}: {@code 'self'} and the studio's origin, which previews a
 *       banner layout by framing the published folder from the website's own origin. No
 *       {@code X-Frame-Options}: {@code SAMEORIGIN} would refuse the studio wherever the CSP is
 *       not read, and where it is read it overrides the header anyway.</li>
 *   <li>The studio's draft preview ({@code PreviewFilter}): the same, since the studio shows it
 *       in a frame.</li>
 * </ul>
 *
 * <p>A response that already carries a policy keeps it. The studio's origin is
 * {@code pacod.studio.origin} ({@code STUDIO_ORIGIN}); blank, only the website frames itself.
 */
@Slf4j
public final class FramingHeaders implements HeaderWriter {

    static final String CSP = "Content-Security-Policy";
    static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:\\d{1,5})?");

    private final String studioOrigin;

    /**
     * @param studioOrigin scheme, host and port, without a path; blank for none
     * @throws IllegalStateException for anything else, which would otherwise be written into a header
     */
    public FramingHeaders(String studioOrigin) {
        String origin = studioOrigin == null ? "" : studioOrigin.strip();
        if (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        if (!origin.isEmpty() && !ORIGIN.matcher(origin).matches()) {
            throw new IllegalStateException("pacod.studio.origin must be scheme://host[:port], not '" + studioOrigin + "'");
        }
        if (origin.isEmpty()) {
            log.warn("pacod.studio.origin (STUDIO_ORIGIN) is blank: the studio cannot preview banner layouts");
        }
        this.studioOrigin = origin;
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader(CSP)) {
            return;
        }
        if ((isBannerFolder(request) || SitePreview.of(request).isPresent()) && !studioOrigin.isEmpty()) {
            response.setHeader(CSP, "frame-ancestors 'self' " + studioOrigin);
            return;
        }
        response.setHeader(CSP, "frame-ancestors 'self'");
        response.setHeader(FRAME_OPTIONS, "SAMEORIGIN");
    }

    private static boolean isBannerFolder(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/resources/banners/");
    }
}
