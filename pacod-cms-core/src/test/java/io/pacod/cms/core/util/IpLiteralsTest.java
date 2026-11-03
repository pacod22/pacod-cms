package io.pacod.cms.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One spelling per address, so the site's rows and the studio's filters meet. */
class IpLiteralsTest {

    @Test
    @DisplayName("every spelling of an IPv6 address comes out as one")
    void ipv6SpellingsAreOne() {
        String expected = "2001:db8:0:0:0:0:0:1";

        assertThat(IpLiterals.normalise("2001:db8::1")).isEqualTo(expected);
        assertThat(IpLiterals.normalise("2001:DB8:0:0:0:0:0:1")).isEqualTo(expected);
        assertThat(IpLiterals.normalise("[2001:db8::1]")).isEqualTo(expected);
        assertThat(IpLiterals.normalise(" 2001:db8::1 ")).isEqualTo(expected);
    }

    @Test
    @DisplayName("an IPv4 address is itself")
    void ipv4() {
        assertThat(IpLiterals.normalise("198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("anything that is not a literal is null -- never a name looked up")
    void notALiteral() {
        assertThat(IpLiterals.normalise("example.com")).isNull();
        assertThat(IpLiterals.normalise("1.2.3.4; DROP")).isNull();
        assertThat(IpLiterals.normalise("")).isNull();
        assertThat(IpLiterals.normalise(null)).isNull();
    }
}
