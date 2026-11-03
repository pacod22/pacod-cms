package io.pacod.cms.site.services;

import io.pacod.cms.core.util.IpLiterals;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

/**
 * The address a request came from, as the site stores it: the crawler log's and the reader log's
 * one rule.
 *
 * <h2>The address is {@code X-Real-IP}, never {@code X-Forwarded-For}</h2>
 * Caddy sets {@code X-Real-IP} to the TCP peer it saw. {@code X-Forwarded-For} is a chain, and its
 * first entry -- the one {@code ForwardedHeaderFilter} hands back as {@code getRemoteAddr()} -- is
 * the client's to choose wherever a proxy appends to it rather than replacing it, and on the site's
 * directly published port. An address shown on a screen as a reader's or a crawler's must be one
 * the client could not write.
 *
 * <p>{@code VisitorIdentity} keeps its own derivation on purpose: its hash is the key the reader
 * counts are made of, and changing what goes into it would count every reader twice on the day it
 * changed.
 */
public final class ClientAddress {

    private static final String REAL_IP = "X-Real-IP";

    private ClientAddress() {
    }

    /**
     * The client address, as an IP literal or {@code null}.
     *
     * <p>{@code X-Real-IP} is believed only when the TCP peer is a proxy hop -- loopback or a
     * private address, which is what Caddy is on the compose network. The site's port is also
     * published directly ({@code WEB_PORT}, "for bypassing the proxy"), and a client reaching it
     * from outside could otherwise write any address it liked into the header. The peer is read from
     * the <em>innermost</em> request: {@code ForwardedHeaderFilter} wraps the request before any of
     * this runs and answers {@code getRemoteAddr()} with the first {@code X-Forwarded-For} entry,
     * which is the client's to choose. With {@code SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK} Tomcat
     * installs no {@code RemoteIpValve}, so the container's own request still holds the real peer.
     *
     * <p>Only a literal is returned ({@link IpLiterals#normalise}), so the stored value is always an
     * address, spelled as the studio's filters spell it, and never whatever text a header carried.
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String peer = IpLiterals.normalise(innermost(request).getRemoteAddr());
        String real = IpLiterals.normalise(request.getHeader(REAL_IP));
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
}
