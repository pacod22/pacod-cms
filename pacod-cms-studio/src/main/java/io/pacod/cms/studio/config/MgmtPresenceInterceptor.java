package io.pacod.cms.studio.config;

import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.MgmtPresence;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feeds {@link MgmtPresence} from every console request an operator makes.
 *
 * <p>The three background polls in {@code mgmt.js} are skipped, and that is the point of the
 * class rather than a detail: the two live counts fire every minute from any open dashboard or
 * monitoring tab, and a running job's status every couple of seconds for as long as the job
 * runs -- possibly hours. Counting them would keep an operator "active" for as long as they left a
 * tab open, and the tile would answer "who has the console open", which nobody asked. A new
 * timer-driven {@code fetch} in {@code mgmt.js} belongs on this list. Scripts on
 * {@code /internal/**} (a service account's token) are skipped too.
 *
 * <p>Reads the {@code SecurityContext} and keys each operator by
 * {@code StudioPrincipals.displayName}, the name the chrome shows them under.
 */
public class MgmtPresenceInterceptor implements HandlerInterceptor {

    static final Set<String> POLLS = Set.of("/mgmt/monitoring/live", "/mgmt/dashboard/live");

    /** {@code /mgmt/jobs/&#123;id&#125;/status}, the running job's log poll. */
    static final Pattern JOB_STATUS_POLL = Pattern.compile("^/mgmt/jobs/[^/]+/status$");

    private final MgmtPresence presence;

    public MgmtPresenceInterceptor(MgmtPresence presence) {
        this.presence = presence;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The path within the website: under /sites/<id>/ that is the context path, and the polls
        // are listed without it.
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        // Scripts on the internal API are not operators at the console.
        if (POLLS.contains(uri) || JOB_STATUS_POLL.matcher(uri).matches() || uri.startsWith("/internal/")) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            presence.touch(StudioPrincipals.displayName(authentication));
        }
        return true;
    }
}
