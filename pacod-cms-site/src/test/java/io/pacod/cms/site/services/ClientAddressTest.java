package io.pacod.cms.site.services;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one rule the crawler log and the reader log store an address by. {@code CrawlerTrafficFilterTest}
 * pins the same rule through the filter.
 */
class ClientAddressTest {

    @Test
    @DisplayName("behind the proxy, X-Real-IP beats a client-written X-Forwarded-For, even through the forwarding wrapper")
    void realIpFromAProxyHop() {
        MockHttpServletRequest container = new MockHttpServletRequest("GET", "/");
        container.setRemoteAddr("172.18.0.5");
        container.addHeader("X-Forwarded-For", "66.249.66.1, 203.0.113.7");
        container.addHeader("X-Real-IP", "203.0.113.7");

        assertThat(ClientAddress.of(container)).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.of(forwarded(container, "66.249.66.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("from a public peer -- the directly published port -- X-Real-IP is just text")
    void realIpIsIgnoredFromAPublicPeer() {
        MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/");
        direct.setRemoteAddr("198.51.100.4");
        direct.addHeader("X-Real-IP", "66.249.66.1");

        assertThat(ClientAddress.of(direct)).isEqualTo("198.51.100.4");
        assertThat(ClientAddress.of(forwarded(direct, "66.249.66.1"))).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("an IPv6 address is spelled one way, the way the studio's filter spells it")
    void ipv6IsNormalised() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("fd00::5");                       // unique-local: a Docker network
        request.addHeader("X-Real-IP", "2001:DB8::1");

        assertThat(ClientAddress.of(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    @DisplayName("only an IP literal is returned; a header carrying anything else falls back to the peer")
    void onlyLiterals() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Real-IP", "crawl.googlebot.com<script>");

        assertThat(ClientAddress.of(request)).isEqualTo("172.18.0.5");
        assertThat(ClientAddress.of(null)).isNull();
    }

    private static HttpServletRequest forwarded(MockHttpServletRequest request, String forgedClient) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return forgedClient;
            }
        };
    }
}
