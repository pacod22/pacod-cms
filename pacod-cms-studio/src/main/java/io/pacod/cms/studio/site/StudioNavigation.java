package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.config.MgmtLocaleConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the sidebar shows, which is the same on every screen: the platform's block, then the website
 * switcher and the website's screens. A platform screen has no website of its own, so it shows the
 * one <em>kept</em> from the screens before -- the {@value #SITE_COOKIE} cookie, written whenever a
 * website's screen renders -- and the menu does not change under the operator as they go between
 * the website list and a website. {@code currentSite} stays what the page itself is about; the topbar
 * says that.
 *
 * <p>Request attributes, set for view-rendering responses by {@link MgmtLocaleConfig}:
 * <ul>
 *   <li>{@value #SITE} -- the bound website, else the kept one, else the person's only one; null
 *       when there is none to show. A kept id is believed only when it is a website id, the website
 *       exists and is not being deleted, and the person still holds a role at, above or below it.</li>
 *   <li>{@value #SITES} -- the websites the person {@link StudioAccess#touches touches}, as the
 *       website list shows them.</li>
 *   <li>{@value #SWITCH_PATH} -- where the switcher leads in another website: the same sidebar
 *       screen, never a record (article 42 belongs to one website), else the dashboard.</li>
 *   <li>{@value #PLATFORM_OPEN} -- whether the platform block is unfolded; the
 *       {@value #PLATFORM_COOKIE} cookie, which mgmt.js writes, says it is not.</li>
 * </ul>
 */
@Slf4j
@Component
public class StudioNavigation {

    public static final String SITE = "navSite";
    public static final String SITES = "navSites";
    public static final String SWITCH_PATH = "navSwitchPath";
    public static final String PLATFORM_OPEN = "platformNavOpen";

    public static final String SITE_COOKIE = "mgmt-site";
    public static final String PLATFORM_COOKIE = "mgmt-platform-nav";
    static final Duration MAX_AGE = Duration.ofDays(365);

    static final String DASHBOARD = "/mgmt/dashboard";

    /**
     * The sidebar's website screens, by the path a screen below them starts with, and where each
     * leads; the longest match wins, so {@code /mgmt/social/stats} is not {@code /mgmt/social}.
     */
    static final Map<String, String> SCREENS = Map.ofEntries(
            Map.entry(DASHBOARD, DASHBOARD),
            Map.entry("/mgmt/articles", "/mgmt/articles"),
            Map.entry("/mgmt/pages", "/mgmt/pages"),
            Map.entry("/mgmt/images", "/mgmt/images"),
            Map.entry("/mgmt/authors", "/mgmt/authors"),
            Map.entry("/mgmt/categories", "/mgmt/categories"),
            Map.entry("/mgmt/issues", "/mgmt/issues"),
            Map.entry("/mgmt/social", "/mgmt/social"),
            Map.entry("/mgmt/banners", "/mgmt/banners"),
            Map.entry("/mgmt/banners/layouts", "/mgmt/banners/layouts"),
            Map.entry("/mgmt/design", "/mgmt/design"),
            Map.entry("/mgmt/design/theme", "/mgmt/design/theme"),
            Map.entry("/mgmt/design/menus", "/mgmt/design/menus/main"),
            Map.entry("/mgmt/monitoring", "/mgmt/monitoring"),
            Map.entry("/mgmt/social/stats", "/mgmt/social/stats"),
            Map.entry("/mgmt/crawlers", "/mgmt/crawlers"),
            Map.entry("/mgmt/settings", "/mgmt/settings"),
            Map.entry("/mgmt/members", "/mgmt/members"),
            Map.entry("/mgmt/site-files", "/mgmt/site-files"),
            Map.entry("/mgmt/pdf", "/mgmt/pdf"),
            Map.entry("/mgmt/jobs", "/mgmt/jobs"));

    private static final List<String> PREFIXES = SCREENS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();

    private final StudioSites sites;
    private final StudioAccess access;

    public StudioNavigation(StudioSites sites, StudioAccess access) {
        this.sites = sites;
        this.access = access;
    }

    /**
     * Sets the sidebar's attributes and keeps the bound website for the screens that have none.
     *
     * <p>Not for an error dispatch or an anonymous request, neither of which draws a sidebar: the
     * error page of a website someone was refused would otherwise keep that website, and the menu
     * would jump on the next platform screen. A failure costs the sidebar its website, never the
     * screen.
     */
    public void expose(HttpServletRequest request, HttpServletResponse response,
                       StudioSites.SiteView bound) {
        if (request.getDispatcherType() == DispatcherType.ERROR || request.getUserPrincipal() == null) {
            return;
        }
        try {
            show(request, response, bound);
        } catch (RuntimeException e) {
            log.error("Could not work out the sidebar's website: {}", e.getMessage());
        }
    }

    private void show(HttpServletRequest request, HttpServletResponse response, StudioSites.SiteView bound) {
        List<StudioSites.SiteChoice> choices = sites.choices().stream()
                .filter(choice -> access.touches(StudioAccess.pathOfWebsite(choice.organisationId(), choice.id())))
                .toList();
        String kept = cookie(request, SITE_COOKIE);
        StudioSites.SiteView site = bound != null ? bound : kept(kept).or(() -> only(choices)).orElse(null);

        request.setAttribute(SITE, site);
        request.setAttribute(SITES, choices);
        request.setAttribute(SWITCH_PATH, bound == null ? DASHBOARD : switchPath(StudioPaths.withinSite(
                request.getRequestURI().substring(request.getContextPath().length()))));
        request.setAttribute(PLATFORM_OPEN, !"closed".equals(cookie(request, PLATFORM_COOKIE)));

        if (bound != null && !bound.id().equals(kept) && touches(bound)) {
            keep(request, response, bound.id());
        }
    }

    /** The sidebar screen {@code path} is at or below, as the address it leads to; the dashboard otherwise. */
    static String switchPath(String path) {
        if (path != null) {
            for (String prefix : PREFIXES) {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return SCREENS.get(prefix);
                }
            }
        }
        return DASHBOARD;
    }

    private Optional<StudioSites.SiteView> kept(String id) {
        if (id == null || !Website.ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return sites.byId(id).filter(this::touches);
    }

    private boolean touches(StudioSites.SiteView site) {
        return access.touches(StudioAccess.pathOfWebsite(site.organisationId(), site.id()));
    }

    private Optional<StudioSites.SiteView> only(List<StudioSites.SiteChoice> choices) {
        return choices.size() == 1 ? sites.byId(choices.getFirst().id()) : Optional.empty();
    }

    /**
     * Path {@code /}, as {@code mgmt-lang}'s: one choice reaches {@code /mgmt/} and every
     * {@code /sites/<id>/mgmt/}; {@code Secure} follows {@code request.isSecure()} for the same
     * reason (see {@link io.pacod.cms.studio.i18n.MgmtLocaleResolver}).
     */
    private static void keep(HttpServletRequest request, HttpServletResponse response, String id) {
        ResponseCookie cookie = ResponseCookie.from(SITE_COOKIE, id)
                .path("/")
                .maxAge(MAX_AGE)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
