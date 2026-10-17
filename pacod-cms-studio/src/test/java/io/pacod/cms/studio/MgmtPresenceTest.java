package io.pacod.cms.studio;

import io.pacod.cms.studio.config.MgmtPresenceInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MgmtPresenceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T10:00:00Z"));

    private final MgmtPresence presence = new MgmtPresence(Duration.ofMinutes(5), new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an operator is active for the window after their last request, then forgotten")
    void windowExpires() {
        presence.touch("maria");
        now.set(now.get().plusSeconds(60));
        presence.touch("admin");

        assertThat(presence.active()).containsExactly("admin", "maria");

        now.set(now.get().plus(Duration.ofMinutes(4)).plusSeconds(1));
        assertThat(presence.active()).containsExactly("admin");

        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertThat(presence.active()).isEmpty();
    }

    @Test
    @DisplayName("a blank name is not an operator")
    void blankIsIgnored() {
        presence.touch(null);
        presence.touch(" ");

        assertThat(presence.active()).isEmpty();
    }

    @Test
    @DisplayName("the interceptor counts a signed-in request, not an anonymous one, and never a poll")
    void interceptorCountsOnlyRealActivity() {
        MgmtPresenceInterceptor interceptor = new MgmtPresenceInterceptor(presence);

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        interceptor.preHandle(request("/mgmt/login"), new MockHttpServletResponse(), null);
        assertThat(presence.active()).isEmpty();

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "maria", null, AuthorityUtils.createAuthorityList("ROLE_MGMT")));

        // An open tab polls both of these every minute; counting them would make "active" mean
        // "has the console open".
        interceptor.preHandle(request("/mgmt/dashboard/live"), new MockHttpServletResponse(), null);
        interceptor.preHandle(request("/mgmt/monitoring/live"), new MockHttpServletResponse(), null);
        // A running job's log poll, every couple of seconds for hours.
        interceptor.preHandle(request("/mgmt/jobs/42/status"), new MockHttpServletResponse(), null);
        assertThat(presence.active()).isEmpty();

        // The job's own screen is not a poll.
        interceptor.preHandle(request("/mgmt/jobs/42"), new MockHttpServletResponse(), null);
        assertThat(presence.active()).containsExactly("maria");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
