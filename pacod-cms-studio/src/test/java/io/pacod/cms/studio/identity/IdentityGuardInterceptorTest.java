package io.pacod.cms.studio.identity;

import io.pacod.cms.studio.access.IdentityGuardInterceptor;
import io.pacod.cms.studio.access.StudioUnavailableException;
import io.pacod.cms.studio.controller.MgmtSitesController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.method.HandlerMethod;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityGuardInterceptorTest {

    private AuthzFeed feed;
    private IdentityGuardInterceptor guard;
    private HandlerMethod handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        feed = mock(AuthzFeed.class);
        ObjectProvider<AuthzFeed> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(feed);
        IdProperties id = new IdProperties(true, "http://id.test", "http://id-internal.test", "pacod-cms-studio", "s",
                "pacod-cms-service", "s", "http://studio.test", Duration.ofSeconds(60));
        guard = new IdentityGuardInterceptor(provider, id);
        handler = new HandlerMethod(mock(MgmtSitesController.class), MgmtSitesController.class.getMethod("list",
                org.springframework.ui.Model.class));

        OidcIdToken token = new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "usr_1", "sid", "sid_1", "email", "a@example.com"));
        DefaultOidcUser user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), token);
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "pacod-id"));

        when(feed.isActive("usr_1")).thenReturn(true);
        when(feed.isReady()).thenReturn(true);
        when(feed.staleness()).thenReturn(Duration.ofSeconds(5));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a live session of an active account passes")
    void livePasses() throws Exception {
        assertThat(guard.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler)).isTrue();
    }

    @Test
    @DisplayName("a session pacod-id ended signs the person out here")
    void revokedSessionSignsOut() throws Exception {
        when(feed.isRevoked("sid_1")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(guard.preHandle(request, response, handler)).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("/mgmt/login?ended");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a disabled account is signed out too")
    void disabledAccountSignsOut() throws Exception {
        when(feed.isActive("usr_1")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(guard.preHandle(new MockHttpServletRequest(), response, handler)).isFalse();
        assertThat(response.getRedirectedUrl()).endsWith("/mgmt/login?ended");
    }

    @Test
    @DisplayName("a copy older than the limit decides nothing: 503")
    void staleIsUnavailable() {
        when(feed.staleness()).thenReturn(Duration.ofSeconds(61));
        assertThatThrownBy(() -> guard.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                .isInstanceOf(StudioUnavailableException.class);
    }

    @Test
    @DisplayName("before the first snapshot nothing is decided either")
    void notReadyIsUnavailable() {
        when(feed.isReady()).thenReturn(false);
        assertThatThrownBy(() -> guard.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                .isInstanceOf(StudioUnavailableException.class);
    }
}
