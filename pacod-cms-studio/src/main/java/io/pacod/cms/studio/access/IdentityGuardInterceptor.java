package io.pacod.cms.studio.access;

import io.pacod.cms.studio.identity.AuthzFeed;
import io.pacod.cms.studio.identity.IdProperties;
import io.pacod.cms.studio.site.StudioSiteFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Checks a signed-in person against pacod-id's feed before any studio screen runs.
 *
 * <ul>
 *   <li><b>An ended session or a disabled or deleted account signs the person out here</b>: the
 *       studio session is invalidated and the browser sent to the sign-in page. A session pacod-id
 *       ended -- a logout elsewhere, a password change, an administrator -- is in the feed as a
 *       revoked {@code sid}, and the ID token carries the session's {@code sid}.</li>
 *   <li><b>A copy older than {@link IdProperties#maxStaleness()} decides nothing</b>: 503. Roles may
 *       have been taken away since; a studio that kept deciding with them would keep granting
 *       what was revoked for as long as pacod-id stayed unreachable.</li>
 * </ul>
 * Without a feed ({@code pacod.id.enabled=false}) there is nothing to check against.
 */
@Slf4j
public class IdentityGuardInterceptor implements HandlerInterceptor {

    private final ObjectProvider<AuthzFeed> feed;
    private final IdProperties id;

    public IdentityGuardInterceptor(ObjectProvider<AuthzFeed> feed, IdProperties id) {
        this.feed = feed;
        this.id = id;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        AuthzFeed f = feed.getIfAvailable();
        if (f == null || !(handler instanceof HandlerMethod method)
                || !method.getBeanType().getName().startsWith("io.pacod.cms.studio.")) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return true;
        }
        String sid = user.getClaimAsString("sid");
        if (!f.isActive(user.getSubject()) || f.isRevoked(sid)) {
            log.info("Signing {} out: pacod-id ended the session or the account", user.getSubject());
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            Object root = request.getAttribute(StudioSiteFilter.STUDIO_ROOT);
            response.sendRedirect((root == null ? "" : root) + "/mgmt/login?ended");
            return false;
        }
        if (!f.isReady() || f.staleness().compareTo(id.maxStaleness()) > 0) {
            throw new StudioUnavailableException("pacod-id has not been reachable for " + f.staleness().toSeconds() + "s");
        }
        return true;
    }
}
