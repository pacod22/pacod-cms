package io.pacod.cms.site.services;

import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.util.CrawlerClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reverse-then-forward DNS, with the lookups answered by the test. See {@link CrawlerVerifier} for
 * the three ways this deliberately differs from pacod.
 */
class CrawlerVerifierTest {

    private static final CrawlerClassifier.Identity GOOGLEBOT =
            CrawlerClassifier.classify("Mozilla/5.0 (compatible; Googlebot/2.1)");
    private static final CrawlerClassifier.Identity BINGBOT =
            CrawlerClassifier.classify("Mozilla/5.0 (compatible; bingbot/2.0)");
    private static final CrawlerClassifier.Identity GPTBOT =
            CrawlerClassifier.classify("Mozilla/5.0 (compatible; GPTBot/1.1)");

    private static final String GOOGLE_IP = "66.249.66.1";
    private static final String GOOGLE_HOST = "crawl-66-249-66-1.googlebot.com";

    private CrawlerVerifier verifier;

    @AfterEach
    void stop() {
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    @Test
    @DisplayName("a PTR under the operator's suffix that resolves back to the address is verified")
    void genuineGooglebot() {
        FakeDns dns = new FakeDns(Map.of(GOOGLE_IP, List.of(GOOGLE_HOST)), Map.of(GOOGLE_HOST, List.of(GOOGLE_IP)));
        verifier = new CrawlerVerifier(dns, true, 1, 10);

        assertThat(await(GOOGLE_IP, GOOGLEBOT)).isEqualTo(CrawlerVerification.VERIFIED);
    }

    @Test
    @DisplayName("a lookalike domain is not the operator's: evil-googlebot.com is not googlebot.com")
    void lookalikeSuffixIsRefused() {
        assertThat(CrawlerVerifier.matchesSuffix("crawl.evil-googlebot.com", List.of("googlebot.com"))).isFalse();
        assertThat(CrawlerVerifier.matchesSuffix("evil-googlebot.com", List.of("googlebot.com"))).isFalse();
        assertThat(CrawlerVerifier.matchesSuffix("googlebot.com.", List.of("googlebot.com"))).isTrue();
        assertThat(CrawlerVerifier.matchesSuffix("CRAWL-1.GoogleBot.com", List.of("googlebot.com"))).isTrue();

        FakeDns dns = new FakeDns(Map.of("203.0.113.9", List.of("crawl.evil-googlebot.com")),
                Map.of("crawl.evil-googlebot.com", List.of("203.0.113.9")));
        verifier = new CrawlerVerifier(dns, true, 1, 10);
        assertThat(await("203.0.113.9", GOOGLEBOT)).isEqualTo(CrawlerVerification.UNVERIFIED);
    }

    @Test
    @DisplayName("a PTR anyone could set is not enough: the forward lookup must agree")
    void forwardMismatchIsForged() {
        FakeDns dns = new FakeDns(Map.of("203.0.113.9", List.of(GOOGLE_HOST)), Map.of(GOOGLE_HOST, List.of(GOOGLE_IP)));
        verifier = new CrawlerVerifier(dns, true, 1, 10);

        assertThat(await("203.0.113.9", GOOGLEBOT)).isEqualTo(CrawlerVerification.UNVERIFIED);
    }

    @Test
    @DisplayName("no PTR at all (NXDOMAIN) is a finding: forged")
    void noPtrIsForged() {
        verifier = new CrawlerVerifier(new FakeDns(Map.of(), Map.of()), true, 1, 10);

        assertThat(await("203.0.113.9", GOOGLEBOT)).isEqualTo(CrawlerVerification.UNVERIFIED);
    }

    @Test
    @DisplayName("a timeout is no finding at all: unknown, and cached only briefly")
    void timeoutIsUnknown() {
        FakeDns dns = new FakeDns(Map.of(), Map.of());
        dns.unavailable = true;
        verifier = new CrawlerVerifier(dns, true, 1, 10);

        assertThat(await("203.0.113.9", GOOGLEBOT)).isEqualTo(CrawlerVerification.UNKNOWN);
    }

    @Test
    @DisplayName("a private or loopback address is never looked up, so never labelled forged")
    void privateAddressesAreUnknown() {
        FakeDns dns = new FakeDns(Map.of(), Map.of());
        verifier = new CrawlerVerifier(dns, true, 1, 10);

        for (String ip : List.of("127.0.0.1", "10.0.0.5", "172.18.0.4", "192.168.1.10", "100.64.1.1",
                "169.254.1.1", "::1", "fd12:3456::1", "fe80::1")) {
            assertThat(await(ip, GOOGLEBOT)).as(ip).isEqualTo(CrawlerVerification.UNKNOWN);
        }
        assertThat(dns.reverseCalls).hasValue(0);
    }

    @Test
    @DisplayName("a hostname or garbage in the header is never resolved")
    void nonLiteralsAreUnknown() {
        FakeDns dns = new FakeDns(Map.of(), Map.of());
        verifier = new CrawlerVerifier(dns, true, 1, 10);

        assertThat(await("googlebot.com", GOOGLEBOT)).isEqualTo(CrawlerVerification.UNKNOWN);
        assertThat(await("", GOOGLEBOT)).isEqualTo(CrawlerVerification.UNKNOWN);
        assertThat(await(null, GOOGLEBOT)).isEqualTo(CrawlerVerification.UNKNOWN);
        assertThat(dns.reverseCalls).hasValue(0);
    }

    @Test
    @DisplayName("a crawler with no DNS scheme, or verification switched off, is unknown without a lookup")
    void unverifiableOrDisabledIsUnknown() {
        FakeDns dns = new FakeDns(Map.of(), Map.of());
        verifier = new CrawlerVerifier(dns, true, 1, 10);
        assertThat(await("203.0.113.9", GPTBOT)).isEqualTo(CrawlerVerification.UNKNOWN);

        CrawlerVerifier off = new CrawlerVerifier(dns, false, 1, 10);
        try {
            assertThat(await(off, GOOGLE_IP, GOOGLEBOT)).isEqualTo(CrawlerVerification.UNKNOWN);
        } finally {
            off.shutdown();
        }
        assertThat(dns.reverseCalls).hasValue(0);
    }

    @Test
    @DisplayName("a definite outcome is cached per address and operator, not per address alone")
    void cacheKeyIncludesTheOperator() {
        FakeDns dns = new FakeDns(Map.of(GOOGLE_IP, List.of(GOOGLE_HOST)), Map.of(GOOGLE_HOST, List.of(GOOGLE_IP)));
        verifier = new CrawlerVerifier(dns, true, 1, 10);

        assertThat(await(GOOGLE_IP, GOOGLEBOT)).isEqualTo(CrawlerVerification.VERIFIED);
        assertThat(await(GOOGLE_IP, GOOGLEBOT)).isEqualTo(CrawlerVerification.VERIFIED);
        assertThat(dns.reverseCalls).hasValue(1);

        // The same address now claiming to be Bingbot is a different question -- and the answer is no.
        assertThat(await(GOOGLE_IP, BINGBOT)).isEqualTo(CrawlerVerification.UNVERIFIED);
        assertThat(dns.reverseCalls).hasValue(2);
    }

    @Test
    @DisplayName("the reverse name is in-addr.arpa for IPv4 and nibble-reversed ip6.arpa for IPv6")
    void reverseNames() {
        assertThat(CrawlerVerifier.reverseName(InetAddress.ofLiteral("66.249.66.1")))
                .isEqualTo("1.66.249.66.in-addr.arpa");
        assertThat(CrawlerVerifier.reverseName(InetAddress.ofLiteral("2001:4860:4801:10::1")))
                .isEqualTo("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.1.0.0.1.0.8.4.0.6.8.4.1.0.0.2.ip6.arpa");
    }

    private CrawlerVerification await(String ip, CrawlerClassifier.Identity identity) {
        return await(verifier, ip, identity);
    }

    private static CrawlerVerification await(CrawlerVerifier verifier, String ip, CrawlerClassifier.Identity identity) {
        CompletableFuture<CrawlerVerification> result = new CompletableFuture<>();
        verifier.verify(ip, identity, result::complete);
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("verification never completed", e);
        }
    }

    /** Answers from two maps; a name missing from them does not exist. */
    private static final class FakeDns implements CrawlerVerifier.DnsLookup {

        private final Map<String, List<String>> ptr;
        private final Map<String, List<String>> addresses;
        private final AtomicInteger reverseCalls = new AtomicInteger();
        private volatile boolean unavailable;

        FakeDns(Map<String, List<String>> ptr, Map<String, List<String>> addresses) {
            this.ptr = ptr;
            this.addresses = addresses;
        }

        @Override
        public List<String> reverse(InetAddress address) throws CrawlerVerifier.DnsUnavailableException {
            reverseCalls.incrementAndGet();
            if (unavailable) {
                throw new CrawlerVerifier.DnsUnavailableException("timed out", null);
            }
            return ptr.getOrDefault(address.getHostAddress(), List.of());
        }

        @Override
        public List<InetAddress> forward(String host) throws CrawlerVerifier.DnsUnavailableException {
            if (unavailable) {
                throw new CrawlerVerifier.DnsUnavailableException("timed out", null);
            }
            return addresses.getOrDefault(host, List.of()).stream().map(InetAddress::ofLiteral).toList();
        }
    }
}
