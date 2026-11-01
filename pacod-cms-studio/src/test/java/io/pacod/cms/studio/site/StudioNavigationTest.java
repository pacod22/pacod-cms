package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.studio.access.StudioAccess;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the sidebar shows: the bound website, else the one kept from the screens before -- believed
 * only for a website that exists and that the person still works on -- else their only one; and
 * where the switcher leads in another website.
 */
class StudioNavigationTest {

    private static final StudioSites.SiteView ALPHA = new StudioSites.SiteView("alpha", "org_a", "Alpha",
            WebsiteStatus.ACTIVE, "en", List.of("alpha.test"), "http://alpha.test");
    private static final StudioSites.SiteView BETA = new StudioSites.SiteView("beta", "org_b", "Beta",
            WebsiteStatus.ACTIVE, "en", List.of(), null);

    private final StudioSites sites = mock(StudioSites.class);
    private final StudioAccess access = mock(StudioAccess.class);
    private final StudioNavigation navigation = new StudioNavigation(sites, access);

    @BeforeEach
    void websites() {
        when(sites.choices()).thenReturn(List.of(new StudioSites.SiteChoice("alpha", "org_a", "Alpha"),
                new StudioSites.SiteChoice("beta", "org_b", "Beta")));
        when(sites.byId(anyString())).thenReturn(Optional.empty());
        when(sites.byId("alpha")).thenReturn(Optional.of(ALPHA));
        when(sites.byId("beta")).thenReturn(Optional.of(BETA));
        when(access.touches(any())).thenReturn(true);
    }

    /** A signed-in request: the sidebar is drawn for nobody else. */
    private static MockHttpServletRequest signedIn(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setUserPrincipal(() -> "usr_a");
        return request;
    }

    private MockHttpServletRequest platformScreen(Cookie... cookies) {
        MockHttpServletRequest request = signedIn("/mgmt/organisations");
        if (cookies.length > 0) {
            request.setCookies(cookies);
        }
        return request;
    }

    @Test
    @DisplayName("a website's screen shows that website and keeps it, once, for the screens without one")
    void boundWebsiteIsKept() {
        MockHttpServletRequest request = signedIn("/sites/beta/mgmt/articles/42");
        request.setContextPath("/sites/beta");
        MockHttpServletResponse response = new MockHttpServletResponse();
        navigation.expose(request, response, BETA);

        assertThat(request.getAttribute(StudioNavigation.SITE)).isEqualTo(BETA);
        assertThat(request.getAttribute(StudioNavigation.SWITCH_PATH)).isEqualTo("/mgmt/articles");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("mgmt-site=beta;").contains("Path=/", "HttpOnly", "SameSite=Lax");

        // Already kept: nothing written again.
        MockHttpServletRequest again = signedIn("/mgmt/articles");
        again.setCookies(new Cookie(StudioNavigation.SITE_COOKIE, "beta"));
        MockHttpServletResponse quiet = new MockHttpServletResponse();
        navigation.expose(again, quiet, BETA);
        assertThat(quiet.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("a platform screen shows the kept website and writes nothing; the switcher leads to dashboards")
    void platformScreenShowsTheKeptWebsite() {
        MockHttpServletRequest request = platformScreen(new Cookie(StudioNavigation.SITE_COOKIE, "beta"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        navigation.expose(request, response, null);

        assertThat(request.getAttribute(StudioNavigation.SITE)).isEqualTo(BETA);
        assertThat(request.getAttribute(StudioNavigation.SWITCH_PATH)).isEqualTo("/mgmt/dashboard");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("a kept id is ignored when it is no website id, names no website, or one the person no longer works on")
    void keptIdIsChecked() {
        for (String id : List.of("../x", "Alpha", "a", "gone")) {
            MockHttpServletRequest request = platformScreen(new Cookie(StudioNavigation.SITE_COOKIE, id));
            navigation.expose(request, new MockHttpServletResponse(), null);
            assertThat(request.getAttribute(StudioNavigation.SITE)).as(id).isNull();
        }
        verify(sites, never()).byId("../x");

        when(access.touches(StudioAccess.pathOfWebsite("org_b", "beta"))).thenReturn(false);
        MockHttpServletRequest request = platformScreen(new Cookie(StudioNavigation.SITE_COOKIE, "beta"));
        navigation.expose(request, new MockHttpServletResponse(), null);
        // Not beta: the person's only website now, which is shown as with nothing kept.
        assertThat(request.getAttribute(StudioNavigation.SITE)).isEqualTo(ALPHA);
        // Nor is beta offered.
        assertThat(request.getAttribute(StudioNavigation.SITES))
                .isEqualTo(List.of(new StudioSites.SiteChoice("alpha", "org_a", "Alpha")));
    }

    @Test
    @DisplayName("with nothing kept, the person's only website is shown; with several, none")
    void onlyWebsiteIsShown() {
        MockHttpServletRequest several = platformScreen();
        navigation.expose(several, new MockHttpServletResponse(), null);
        assertThat(several.getAttribute(StudioNavigation.SITE)).isNull();

        when(access.touches(StudioAccess.pathOfWebsite("org_b", "beta"))).thenReturn(false);
        MockHttpServletRequest one = platformScreen();
        navigation.expose(one, new MockHttpServletResponse(), null);
        assertThat(one.getAttribute(StudioNavigation.SITE)).isEqualTo(ALPHA);
    }

    @Test
    @DisplayName("an error page, an anonymous request and a website the person does not work on keep nothing")
    void keepsOnlyWhatThePersonOpened() {
        // The error page of a website someone was refused would otherwise replace the one they use.
        MockHttpServletRequest error = signedIn("/error");
        error.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        navigation.expose(error, refused, BETA);
        assertThat(refused.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(error.getAttribute(StudioNavigation.SITE)).isNull();

        // The sign-in page draws no sidebar.
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/mgmt/login");
        navigation.expose(anonymous, new MockHttpServletResponse(), null);
        assertThat(anonymous.getAttribute(StudioNavigation.SITES)).isNull();
        verify(sites, never()).choices();

        when(access.touches(StudioAccess.pathOfWebsite("org_b", "beta"))).thenReturn(false);
        MockHttpServletResponse untouched = new MockHttpServletResponse();
        navigation.expose(signedIn("/mgmt/articles"), untouched, BETA);
        assertThat(untouched.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("a control plane that cannot be read costs the sidebar its website, never the screen")
    void failureCostsTheWebsiteOnly() {
        when(sites.choices()).thenThrow(new IllegalStateException("down"));
        MockHttpServletRequest request = platformScreen();
        navigation.expose(request, new MockHttpServletResponse(), null);
        assertThat(request.getAttribute(StudioNavigation.SITE)).isNull();
    }

    @Test
    @DisplayName("the Platform block is open unless its cookie says it was folded")
    void platformFold() {
        MockHttpServletRequest open = platformScreen();
        navigation.expose(open, new MockHttpServletResponse(), null);
        assertThat(open.getAttribute(StudioNavigation.PLATFORM_OPEN)).isEqualTo(true);

        MockHttpServletRequest folded = platformScreen(new Cookie(StudioNavigation.PLATFORM_COOKIE, "closed"));
        navigation.expose(folded, new MockHttpServletResponse(), null);
        assertThat(folded.getAttribute(StudioNavigation.PLATFORM_OPEN)).isEqualTo(false);
    }

    @Test
    @DisplayName("the switcher leads to the same sidebar screen, never to a record of this website")
    void switchPath() {
        assertThat(StudioNavigation.switchPath("/mgmt/articles")).isEqualTo("/mgmt/articles");
        assertThat(StudioNavigation.switchPath("/mgmt/articles/42/translations/de")).isEqualTo("/mgmt/articles");
        assertThat(StudioNavigation.switchPath("/mgmt/banners/7")).isEqualTo("/mgmt/banners");
        assertThat(StudioNavigation.switchPath("/mgmt/banners/layouts/acme2")).isEqualTo("/mgmt/banners/layouts");
        assertThat(StudioNavigation.switchPath("/mgmt/social")).isEqualTo("/mgmt/social");
        assertThat(StudioNavigation.switchPath("/mgmt/social/stats")).isEqualTo("/mgmt/social/stats");
        assertThat(StudioNavigation.switchPath("/mgmt/design/menus/footer")).isEqualTo("/mgmt/design/menus/main");
        assertThat(StudioNavigation.switchPath("/mgmt/design/layouts/home")).isEqualTo("/mgmt/design");
        // Not a sidebar screen, a near miss, a platform screen: the dashboard.
        assertThat(StudioNavigation.switchPath("/mgmt/pages-old")).isEqualTo("/mgmt/dashboard");
        assertThat(StudioNavigation.switchPath("/mgmt/organisations")).isEqualTo("/mgmt/dashboard");
        assertThat(StudioNavigation.switchPath(null)).isEqualTo("/mgmt/dashboard");
    }
}
