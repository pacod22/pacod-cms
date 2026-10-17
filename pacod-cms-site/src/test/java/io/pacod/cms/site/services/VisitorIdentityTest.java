package io.pacod.cms.site.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader key, which is the whole privacy surface of the monitoring feature.
 *
 * <p>Every assertion here is a property that fails silently if it breaks: a key that changes per
 * request makes every reader unique and the count meaningless; a key that ignores the forwarded
 * address makes the entire site one reader; an unsalted key is reversible from a list of
 * candidate addresses.
 */
class VisitorIdentityTest {

    private static final String SALT = "test-salt";

    @Test
    @DisplayName("the same reader on the same browser gets the same key")
    void theSameReaderIsStable() {
        VisitorIdentity identity = new VisitorIdentity(SALT);

        assertThat(identity.hashOf(request("1.2.3.4", "Firefox")))
                .isEqualTo(identity.hashOf(request("1.2.3.4", "Firefox")));
    }

    @Test
    @DisplayName("a different address or a different browser is a different reader")
    void differentReadersDiffer() {
        VisitorIdentity identity = new VisitorIdentity(SALT);
        String base = identity.hashOf(request("1.2.3.4", "Firefox"));

        assertThat(identity.hashOf(request("1.2.3.5", "Firefox"))).isNotEqualTo(base);
        assertThat(identity.hashOf(request("1.2.3.4", "Chrome"))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("the salt is what stops the key being reversible from a guessed address")
    void theSaltChangesTheKey() {
        // Without one, a digest of an address is enumerable: there are only so many candidates
        // and hashing does not help against an input you can simply try.
        assertThat(new VisitorIdentity("salt-a").hashOf(request("1.2.3.4", "Firefox")))
                .isNotEqualTo(new VisitorIdentity("salt-b").hashOf(request("1.2.3.4", "Firefox")));
    }

    @Test
    @DisplayName("a blank salt is replaced by a random one rather than left empty")
    void aBlankSaltIsGenerated() {
        // Two instances with no configured salt must not agree: that is what proves each one
        // generated its own instead of falling back to a constant.
        assertThat(new VisitorIdentity("").hashOf(request("1.2.3.4", "Firefox")))
                .isNotEqualTo(new VisitorIdentity("").hashOf(request("1.2.3.4", "Firefox")));
    }

    @Test
    @DisplayName("X-Forwarded-For wins over the remote address")
    void forwardedForWins() {
        VisitorIdentity identity = new VisitorIdentity(SALT);

        // getRemoteAddr() behind Caddy is the proxy container on the compose network, which would
        // make every reader on the site one reader. The first entry of the header is the client;
        // everything after it is a proxy.
        MockHttpServletRequest forwarded = request("10.0.0.9", "Firefox");
        forwarded.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.9");

        assertThat(identity.hashOf(forwarded))
                .isEqualTo(identity.hashOf(request("203.0.113.7", "Firefox")))
                .isNotEqualTo(identity.hashOf(request("10.0.0.9", "Firefox")));
    }

    @Test
    @DisplayName("no request and no address mean no reader, not a shared one")
    void anUnidentifiableRequestHasNoKey() {
        VisitorIdentity identity = new VisitorIdentity(SALT);

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        anonymous.setRemoteAddr(null);

        // A constant fallback would read as a single, very busy reader -- worse than a gap.
        assertThat(identity.hashOf(anonymous)).isNull();
        assertThat(identity.hashOf(null)).isNull();
    }

    @Test
    @DisplayName("the stored key is a truncated digest, not a full fingerprint")
    void theKeyIsTruncated() {
        assertThat(new VisitorIdentity(SALT).hashOf(request("1.2.3.4", "Firefox")))
                .hasSize(16)
                .matches("[0-9a-f]+");
    }

    private static MockHttpServletRequest request(String address, String agent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        request.addHeader("User-Agent", agent);
        return request;
    }
}
