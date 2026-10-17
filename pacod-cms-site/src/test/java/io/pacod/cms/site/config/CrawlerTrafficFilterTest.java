package io.pacod.cms.site.config;

import io.pacod.cms.core.util.CrawlerClassifier;
import io.pacod.cms.site.services.CrawlerRecorder;
import io.pacod.cms.site.services.CrawlerTraffic;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the filter records, and what it reads the status and the address from. The status cases
 * are the ones a hook that read the status too early would lose: a redirect, an error page, a
 * throw.
 */
class CrawlerTrafficFilterTest {

    private static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

    private CrawlerRecorder recorder;
    private CrawlerTrafficFilter filter;

    @BeforeEach
    void setUp() {
        recorder = mock(CrawlerRecorder.class);
        filter = new CrawlerTrafficFilter(recorder);
    }

    @Test
    @DisplayName("a crawler is marked before the chain runs and recorded with the status it got")
    void crawlerIsMarkedAndRecorded() throws Exception {
        MockHttpServletRequest request = request("/article/5", GOOGLEBOT);
        AtomicReference<Object> seenInside = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInside.set(req.getAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE)));

        assertThat(seenInside.get()).isInstanceOf(CrawlerClassifier.Identity.class);
        verify(recorder).record(eq(CrawlerClassifier.classify(GOOGLEBOT)), eq("66.249.66.1"), eq(GOOGLEBOT),
                eq("GET"), eq("/article/5"), eq(200), any(Instant.class));
    }

    @Test
    @DisplayName("a sendError 404 is recorded as 404 -- the error page renders later, uncounted")
    void sendErrorIsRecorded() throws Exception {
        filter.doFilter(request("/article/999999", GOOGLEBOT), new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).sendError(404));

        verify(recorder).record(any(), any(), any(), any(), eq("/article/999999"), eq(404), any());
    }

    @Test
    @DisplayName("a redirect is recorded as the redirect it was")
    void redirectIsRecorded() throws Exception {
        filter.doFilter(request("/b/12", GOOGLEBOT), new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).sendRedirect("https://advertiser.example/"));

        verify(recorder).record(any(), any(), any(), any(), eq("/b/12"), eq(302), any());
    }

    @Test
    @DisplayName("an exception escaping the chain is recorded as 500 and still thrown")
    void exceptionIsA500() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request("/list/3", GOOGLEBOT), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        verify(recorder).record(any(), any(), any(), any(), eq("/list/3"), eq(500), any());
    }

    @Test
    @DisplayName("the studio's own requests -- a preview, a PDF render -- are never a crawler's visit")
    void uncountedRequestsAreSkipped() throws Exception {
        // Gotenberg's Chromium says HeadlessChrome; every page and image of a render would land
        // in the crawler log.
        MockHttpServletRequest request = request("/article-print/5", "Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/140.0 Safari/537.36");
        CrawlerTraffic.markUncounted(request);
        AtomicReference<Object> seenInside = new AtomicReference<>("unset");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInside.set(req.getAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE)));

        assertThat(seenInside.get()).isNull();
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("a reader is neither marked nor recorded")
    void readersAreUntouched() throws Exception {
        MockHttpServletRequest request = request("/article/5", FIREFOX);
        AtomicReference<Object> seenInside = new AtomicReference<>("unset");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInside.set(req.getAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE)));

        assertThat(seenInside.get()).isNull();
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("an empty User-Agent is automated -- a browser always sends one")
    void missingUserAgentIsOther() throws Exception {
        MockHttpServletRequest request = request("/", null);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(recorder).record(eq(CrawlerClassifier.other()), any(), any(), any(), eq("/"), anyInt(), any());
    }

    @Test
    @DisplayName("the site's own assets, the container healthcheck and the TLS proxy's question are skipped")
    void skippedPaths() throws Exception {
        for (String path : new String[]{"/site-assets/site.css", "/web/v1/health", "/_internal/tls-ask"}) {
            MockHttpServletRequest request = request(path, "Wget");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
            assertThat(request.getAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE)).as(path).isNull();
        }
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("robots.txt is recorded like any other fetch")
    void robotsTxtIsRecorded() throws Exception {
        filter.doFilter(request("/robots.txt", GOOGLEBOT), new MockHttpServletResponse(), (req, res) -> { });

        verify(recorder).record(any(), any(), any(), any(), eq("/robots.txt"), eq(200), any());
    }

    /**
     * A proxy that appends to X-Forwarded-For leaves its first entry the client's to write, and so
     * does the site's directly published port -- and ForwardedHeaderFilter turns exactly that
     * entry into getRemoteAddr(). The wrapper below is that filter's shape: it answers getRemoteAddr() with the forged entry while the container's
     * own request still holds the peer.
     */
    @Test
    @DisplayName("behind the proxy, X-Real-IP beats a client-written X-Forwarded-For, even through the forwarding wrapper")
    void realIpBeatsForwardedFor() {
        MockHttpServletRequest container = new MockHttpServletRequest("GET", "/");
        container.setRemoteAddr("172.18.0.5");                       // Caddy, on the compose network
        container.addHeader("X-Forwarded-For", "66.249.66.1, 203.0.113.7");
        container.addHeader("X-Real-IP", "203.0.113.7");

        assertThat(CrawlerTrafficFilter.clientAddress(container)).isEqualTo("203.0.113.7");
        assertThat(CrawlerTrafficFilter.clientAddress(forwarded(container, "66.249.66.1"))).isEqualTo("203.0.113.7");
    }

    /**
     * The site's port is published directly as well. A client reaching it from outside is its own
     * peer, and an X-Real-IP it wrote is just text -- believed, it would be a real Googlebot address
     * and a {@code VERIFIED} on the screen.
     */
    @Test
    @DisplayName("from a public peer -- the directly published port -- X-Real-IP is not believed")
    void realIpIsIgnoredFromAPublicPeer() {
        MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/");
        direct.setRemoteAddr("198.51.100.4");
        direct.addHeader("X-Real-IP", "66.249.66.1");

        assertThat(CrawlerTrafficFilter.clientAddress(direct)).isEqualTo("198.51.100.4");
        // And not the forwarding wrapper's answer either: that is X-Forwarded-For again.
        assertThat(CrawlerTrafficFilter.clientAddress(forwarded(direct, "66.249.66.1"))).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("only an IP literal is ever stored; a header carrying anything else falls back to the peer")
    void onlyLiteralsAreStored() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Real-IP", "crawl.googlebot.com<script>");

        assertThat(CrawlerTrafficFilter.clientAddress(request)).isEqualTo("172.18.0.5");

        MockHttpServletRequest local = new MockHttpServletRequest("GET", "/");
        local.setRemoteAddr("127.0.0.1");
        assertThat(CrawlerTrafficFilter.clientAddress(local)).isEqualTo("127.0.0.1");
    }

    private static jakarta.servlet.http.HttpServletRequest forwarded(MockHttpServletRequest request, String forgedClient) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return forgedClient;
            }
        };
    }

    @Test
    @DisplayName("registered ahead of Spring Security, for every path")
    void registeredAheadOfSecurity() {
        var registration = new CrawlerTrafficConfig().crawlerTrafficFilter(recorder);

        assertThat(registration.getOrder()).isLessThan(-100);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }

    private static MockHttpServletRequest request(String path, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("172.18.0.9");
        request.addHeader("X-Real-IP", "66.249.66.1");
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }
}
