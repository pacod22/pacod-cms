package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The studio's two kinds of address, and how to tell them apart.
 *
 * <ul>
 *   <li><b>A website's screens</b> live under {@code /sites/<id>/}: {@code /sites/demo/mgmt/articles}.
 *       {@link StudioSiteFilter} binds the website and presents {@code /sites/<id>} as the context
 *       path, so inside the application the path is still {@code /mgmt/articles} -- every mapping,
 *       every security matcher, every {@code @{/mgmt/…}} link and every {@code redirect:} works
 *       unchanged and gains the prefix on the way out.</li>
 *   <li><b>The platform's screens</b> belong to no website: signing in and out (and pacod-id's
 *       redirect back), the console's language, the list of websites and the portal -- organisations,
 *       creating a website, the installation's settings. They answer with or without a prefix,
 *       because nothing on them reads website content.</li>
 * </ul>
 * Any other {@code /mgmt/…} address without a prefix is a website screen with no website named, and
 * is sent to the website list rather than guessed at.
 */
public final class StudioPaths {

    /** {@code /sites/<id>} followed by the end of the path or a slash. */
    private static final Pattern SITE_PREFIX = Pattern.compile("^/sites/([^/]+)(/.*)?$");

    public static final String SITES_SCREEN = "/mgmt/sites";
    public static final String ORGANISATIONS_SCREEN = "/mgmt/organisations";
    public static final String PLATFORM_SCREEN = "/mgmt/platform";

    private static final Set<String> PLATFORM_EXACT = Set.of("/mgmt/login", "/mgmt/logout", SITES_SCREEN,
            ORGANISATIONS_SCREEN, PLATFORM_SCREEN);
    /**
     * {@code /mgmt/oauth2/}: the sign-in redirect to pacod-id and its callback. The portal's
     * screens -- organisations, a new website, the installation -- belong to no website either.
     */
    private static final Set<String> PLATFORM_PREFIXES = Set.of("/mgmt/ui-language/", "/mgmt/oauth2/", SITES_SCREEN + "/",
            ORGANISATIONS_SCREEN + "/", PLATFORM_SCREEN + "/");

    private StudioPaths() {
    }

    /** A website id and the path below it, parsed out of a path within the application. */
    public record SitePath(String siteId, String rest) {
    }

    /**
     * The website named by {@code path}, or {@code null} when it names none. An id that is not a
     * valid website id is no website -- it is never looked up.
     */
    public static SitePath parse(String path) {
        if (path == null) {
            return null;
        }
        Matcher m = SITE_PREFIX.matcher(path);
        if (!m.matches() || !Website.ID.matcher(m.group(1)).matches()) {
            return null;
        }
        String rest = m.group(2) == null ? "" : m.group(2);
        return new SitePath(m.group(1), rest);
    }

    /** {@code path} without a leading {@code /sites/<id>}, for checks that only care about the screen. */
    public static String withinSite(String path) {
        SitePath site = parse(path);
        return site == null ? path : site.rest();
    }

    /** Whether {@code path} (within a website, or un-prefixed) is a platform screen. */
    public static boolean isPlatform(String path) {
        if (PLATFORM_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : PLATFORM_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an un-prefixed {@code path} is a website's screen, which cannot be served without one. */
    public static boolean needsSite(String path) {
        return (path.equals("/mgmt") || path.startsWith("/mgmt/")) && !isPlatform(path);
    }

    /** The studio address of a website's screen. */
    public static String siteScreen(String siteId, String screen) {
        return "/sites/" + siteId + screen;
    }
}
