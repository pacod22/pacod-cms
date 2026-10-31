package io.pacod.cms.site.config;

import io.pacod.cms.core.util.CrawlerClassifier;
import io.pacod.cms.site.services.CrawlerRecorder;
import io.pacod.cms.site.services.CrawlerTraffic;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;

/**
 * Classifies every request to the public site as a reader or an automated client, marks the
 * automated ones, and records them once they have been answered.
 *
 * <p>pacod does this in its outermost SvelteKit hook; a servlet filter is the same position here.
 * It is ahead of Spring Security (see {@code CrawlerTrafficConfig}), so it sees what the controllers
 * never do: {@code robots.txt}, {@code sitemap.xml} and the llms files, every 404, {@code /b/{id}} and
 * {@code /social/go} redirects, and the firewall's 400s.
 *
 * <h2>The status is read after the chain, and a throw is a 500</h2>
 * {@code response.getStatus()} in {@code finally} already holds a {@code sendError} 404 -- the error
 * page is rendered later, in an ERROR dispatch this filter skips ({@code OncePerRequestFilter}'s
 * default), so nothing is counted twice. An exception escaping the chain has not set a status yet;
 * it is recorded as 500 and rethrown. pacod lost every redirect thrown from a hook this way.
 *
 * <h2>The address is {@code X-Real-IP}, never {@code X-Forwarded-For}</h2>
 * Caddy sets {@code X-Real-IP} to the TCP peer it saw. {@code X-Forwarded-For} is a chain, and its
 * first entry -- the one {@code ForwardedHeaderFilter} hands back as {@code getRemoteAddr()} -- is
 * the client's to choose wherever a proxy appends to it rather than replacing it, and on the site's
 * directly published port. A forger would put a real Googlebot address there. And {@code X-Real-IP} itself is believed only from a proxy hop; see
 * {@link #clientAddress}.
 *
 * <h2>What is skipped</h2>
 * {@code /site-assets/**}, the site's own CSS and JS out of the jar, and {@code /web/v1/health}, which the compose healthcheck {@code wget}s
 * every ten seconds and which would otherwise be most of {@code other}. {@code /assets/**} and
 * {@code /resources/**} are the site's own ({@code MediaController}), so a crawler's fetches of
 * images, banner frames and PDFs are recorded like any other; the studio says so.
 */
public class CrawlerTrafficFilter extends OncePerRequestFilter {

    private static final String REAL_IP = "X-Real-IP";

    private final CrawlerRecorder recorder;

    public CrawlerTrafficFilter(CrawlerRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UnboundPaths.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // The studio's own requests -- a draft preview, a PDF render -- marked by the filters
        // before this one: never a crawler's visit, though a PDF render's Chromium says
        // HeadlessChrome and would fill the log with every page and image it fetches.
        if (request.getAttribute(CrawlerTraffic.UNCOUNTED_ATTRIBUTE) != null) {
            chain.doFilter(request, response);
            return;
        }
        CrawlerClassifier.Identity identity = CrawlerTraffic.classify(request.getHeader("User-Agent"));
        if (identity == null) {
            chain.doFilter(request, response);
            return;
        }

        request.setAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE, identity);
        Instant arrived = Instant.now();
        int status = 0;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } catch (IOException | ServletException | RuntimeException | Error e) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            throw e;
        } finally {
            recorder.record(identity, clientAddress(request), request.getHeader("User-Agent"),
                    request.getMethod(), request.getRequestURI(), status, arrived);
        }
    }

    /**
     * The client address, as an IP literal or {@code null}.
     *
     * <p>{@code X-Real-IP} is believed only when the TCP peer is a proxy hop -- loopback or a
     * private address, which is what Caddy is on the compose network. The site's port is also
     * published directly ({@code WEB_PORT}, "for bypassing the proxy"), and a client reaching it
     * from outside could otherwise write any address it liked into the header and be shown as
     * {@code VERIFIED}. The peer is read from the <em>innermost</em> request:
     * {@code ForwardedHeaderFilter} wraps the request before this filter runs and answers
     * {@code getRemoteAddr()} with the first {@code X-Forwarded-For} entry, which is the client's
     * to choose. With
     * {@code SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK} Tomcat installs no {@code RemoteIpValve}, so
     * the container's own request still holds the real peer.
     *
     * <p>Only a literal is returned, so the stored value is always an address and never whatever
     * text a header carried.
     */
    static String clientAddress(HttpServletRequest request) {
        String peer = literal(innermost(request).getRemoteAddr());
        String real = literal(request.getHeader(REAL_IP));
        if (real != null && isProxyHop(peer)) {
            return real;
        }
        return peer;
    }

    private static ServletRequest innermost(ServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current;
    }

    private static boolean isProxyHop(String peer) {
        if (peer == null) {
            return false;
        }
        InetAddress address = InetAddress.ofLiteral(peer);
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        // fc00::/7, IPv6 unique-local: a Docker network with IPv6 enabled.
        return !(address instanceof java.net.Inet4Address) && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** The value as a normalised IP literal, or {@code null}. Never resolves a name. */
    private static String literal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value.strip()).getHostAddress();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
