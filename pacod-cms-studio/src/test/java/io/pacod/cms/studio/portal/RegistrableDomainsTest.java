package io.pacod.cms.studio.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real entries of the Public Suffix List: an ICANN suffix, private-section ones, and unlisted names. */
class RegistrableDomainsTest {

    @ParameterizedTest
    @CsvSource({
            "studio.example.com, example.com",
            "cms.demo.example.io, example.io",
            "www.example.com, example.com",
            "Example.COM., example.com",
            "news.bbc.co.uk, bbc.co.uk",
            // The private section: each name below github.io is a registrable domain of its own.
            "foo.github.io, foo.github.io",
            "a.b.github.io, b.github.io",
            // No listed suffix: the default rule makes the last label the suffix.
            "studio.localhost, studio.localhost",
            "x.demo.localhost, demo.localhost",
            "studio.sites.internal, sites.internal",
            "localhost, localhost",
    })
    @DisplayName("a host's registrable domain is its public suffix plus one label")
    void registrable(String host, String expected) {
        assertThat(RegistrableDomains.of(host)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.5", "::1", "[::1]", "", "exa mple.com", "-x.example.com"})
    @DisplayName("an address or something that is not a host name has none")
    void noneForAddresses(String host) {
        assertThat(RegistrableDomains.of(host)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"github.io, true", "co.uk, true", "localhost, true", "com, true",
            "example-sites.net, false", "sites.internal, false", "demo.localhost, false"})
    @DisplayName("a public suffix gives every name directly below it a registrable domain of its own")
    void publicSuffix(String host, boolean suffix) {
        assertThat(RegistrableDomains.isPublicSuffix(host)).isEqualTo(suffix);
    }
}
