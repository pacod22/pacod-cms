package io.pacod.cms.studio.site;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link StudioSiteFilter} on its own: what the application sees, and what it is bound to. */
class StudioSiteFilterTest {

    private static final Tenant DEMO = new Tenant("demo", "org_demo", "site_demo", "en", ZoneId.of("UTC"));

    private StudioSiteFilter filter;

    /** What the chain was handed, and which website was bound while it ran. */
    private final AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    private final AtomicReference<HttpServletResponse> seenResponse = new AtomicReference<>();
    private final AtomicReference<String> boundSite = new AtomicReference<>();

    private final FilterChain chain = (request, response) -> {
        seen.set((HttpServletRequest) request);
        seenResponse.set((HttpServletResponse) response);
        boundSite.set(TenantContext.find().map(Tenant::siteId).orElse(null));
    };

    @BeforeEach
    void setUp() {
        TenantDirectory directory = mock(TenantDirectory.class);
        when(directory.bySite(anyString())).thenReturn(Optional.empty());
        when(directory.bySite("demo")).thenReturn(Optional.of(DEMO));
        filter = new StudioSiteFilter(directory);
    }

    @Test
    @DisplayName("a website's screen reaches the application under /sites/<id> as its context path")
    void prefixBecomesTheContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sites/demo/mgmt/articles");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(boundSite.get()).isEqualTo("demo");
        assertThat(seen.get().getContextPath()).isEqualTo("/sites/demo");
        assertThat(seen.get().getServletPath()).isEqualTo("/mgmt/articles");
        assertThat(seen.get().getRequestURI()).isEqualTo("/sites/demo/mgmt/articles");
        assertThat(seen.get().getRequestURL().toString()).isEqualTo("http://localhost/sites/demo/mgmt/articles");
    }

    @Test
    @DisplayName("a website that does not exist is a 404, never a default website")
    void unknownWebsiteIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/sites/nobody/mgmt/articles"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("an id that cannot be a website id is not looked up and binds nothing")
    void invalidIdBindsNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sites/Not_An_Id/mgmt/articles");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isSameAs(request);
        assertThat(boundSite.get()).isNotEqualTo("demo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/sites/demo", "/sites/demo/"})
    @DisplayName("the bare website address goes to its dashboard")
    void bareWebsiteGoesToItsDashboard(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/sites/demo/mgmt/dashboard");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mgmt", "/mgmt/", "/mgmt/articles", "/mgmt/dashboard/live", "/mgmt/users"})
    @DisplayName("a website's screen asked for with no website goes to the website list")
    void unprefixedScreenGoesToTheList(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/mgmt/sites");
        assertThat(seen.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mgmt/login", "/mgmt/logout", "/mgmt/sites", "/mgmt/oauth2/authorization/pacod-id",
            "/mgmt/oauth2/code/pacod-id",
            "/mgmt/ui-language/en", "/mgmt-assets/mgmt.css", "/mgmt-health", "/error"})
    @DisplayName("the platform's own addresses pass through untouched")
    void platformAddressesPassThrough(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isSameAs(request);
    }

    @Test
    @DisplayName("a cookie filed under the website prefix is put back under the studio's root")
    void cookiePathsAreRewritten() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/sites/demo/mgmt/logout"),
                new MockHttpServletResponse(), chain);
        HttpServletResponse wrapped = seenResponse.get();

        Cookie rememberMe = new Cookie("remember-me", "x");
        rememberMe.setPath("/sites/demo");
        wrapped.addCookie(rememberMe);
        assertThat(rememberMe.getPath()).isEqualTo("/");

        Cookie elsewhere = new Cookie("other", "y");
        elsewhere.setPath("/mgmt-assets");
        wrapped.addCookie(elsewhere);
        assertThat(elsewhere.getPath()).isEqualTo("/mgmt-assets");

        MockHttpServletResponse header = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/sites/demo/mgmt/x"), header, chain);
        seenResponse.get().addHeader("Set-Cookie", "a=1; Path=/sites/demo/mgmt; HttpOnly");
        seenResponse.get().addHeader("Set-Cookie", "b=2; Path=/; HttpOnly");
        assertThat(header.getHeaders("Set-Cookie")).containsExactly("a=1; Path=/; HttpOnly", "b=2; Path=/; HttpOnly");
    }

    @Test
    @DisplayName("the error dispatch is re-wrapped from the address that failed")
    void errorDispatchKeepsTheWebsite() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/sites/demo/mgmt/articles/99");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(boundSite.get()).isEqualTo("demo");
        assertThat(seen.get().getContextPath()).isEqualTo("/sites/demo");
        assertThat(seen.get().getRequestURI()).isEqualTo("/sites/demo/error");
        assertThat(seen.get().getServletPath()).isEqualTo("/error");
    }

    @Test
    @DisplayName("an image an article body names without a prefix is read for the page that asked")
    void imagePreviewFollowsTheStudioReferer() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/img/articles/a.jpg");
        request.addHeader("Referer", "http://localhost/sites/demo/mgmt/articles/5");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(boundSite.get()).isEqualTo("demo");
        assertThat(seen.get().getContextPath()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example/sites/demo/mgmt/articles/5",
            "http://localhost:8081/sites/demo/mgmt/articles/5", "not a url", "http://localhost/mgmt/articles"})
    @DisplayName("a Referer from another origin, or naming no website, picks nothing")
    void foreignRefererPicksNothing(String referer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/img/articles/a.jpg");
        request.addHeader("Referer", referer);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isSameAs(request);
        assertThat(boundSite.get()).isNotEqualTo("demo");
    }
}
