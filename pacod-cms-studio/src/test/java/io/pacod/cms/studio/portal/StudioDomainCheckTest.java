package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.studio.identity.IdProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StudioDomainCheckTest {

    private static StudioDomainCheck check(String studio, String issuer, String siteDomain) {
        IdProperties id = new IdProperties(true, issuer, "http://id:8071", "studio", "s", "service", "s", studio,
                Duration.ofSeconds(60));
        return new StudioDomainCheck(id, new PortalProperties(null, 3, 5, false, siteDomain), mock(DomainRepository.class), false);
    }

    @Test
    @DisplayName("websites on the studio's registrable domain stop the studio from starting, saying why")
    void sharedDomainIsRefused() {
        assertThatThrownBy(() -> check("https://studio.example.com", "https://id.example.org", "sites.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("example.com").hasMessageContaining("the studio (studio.example.com)");
        // pacod-id's sign-in cookies are as much at stake.
        assertThatThrownBy(() -> check("https://studio.example.org", "https://id.example-sites.net", "example-sites.net"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sign-in");
    }

    @Test
    @DisplayName("a domain of their own, a public suffix, development's localhost, or none at all passes")
    void separateDomainsPass() {
        assertThatCode(() -> check("https://studio.example.com", "https://id.example.com", "example-sites.net"))
                .doesNotThrowAnyException();
        // A private-section suffix: every website below it is a registrable domain of its own.
        assertThatCode(() -> check("https://studio.github.io", "https://id.example.com", "github.io"))
                .doesNotThrowAnyException();
        assertThatCode(() -> check("http://studio.localhost:8000", "http://id.localhost:8000", "localhost"))
                .doesNotThrowAnyException();
        assertThatCode(() -> check("http://studio.localhost:8000", "http://id.localhost:8000", ""))
                .doesNotThrowAnyException();
        // An address is not a domain; nothing to compare, and the start goes on.
        assertThatCode(() -> check("http://10.0.0.5:7070", "http://10.0.0.5:8070", "example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stored addresses on the studio's or sign-in's registrable domain are reported, others not")
    void storedAddressesAreReported() {
        StudioDomainCheck check = check("https://studio.example.com", "https://id.example.org", "example-sites.net");

        List<String> findings = check.sharing(List.of(new Domain("www.example.com", "acme", true),
                new Domain("acme.example-sites.net", "acme", false), new Domain("login.example.org", "beta", true)));

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0)).contains("acme", "www.example.com", "the studio");
        assertThat(findings.get(1)).contains("beta", "login.example.org", "sign-in");
    }
}
