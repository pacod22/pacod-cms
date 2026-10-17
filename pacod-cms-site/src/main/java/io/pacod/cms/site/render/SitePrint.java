package io.pacod.cms.site.render;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Whether this request is the studio's PDF renderer reading the print view -- the
 * {@code X-Pacod-Print} header {@code PrintFilter} checked. Such a request counts for nothing and
 * tells no third party ({@code SiteChrome.withoutTracking}); unlike a preview it shows what is
 * published.
 */
public final class SitePrint {

    public static final String ATTRIBUTE = SitePrint.class.getName();

    private SitePrint() {
    }

    public static boolean is(HttpServletRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
    }
}
