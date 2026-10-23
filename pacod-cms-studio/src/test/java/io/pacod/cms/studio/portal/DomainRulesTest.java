package io.pacod.cms.studio.portal;

import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.identity.IdProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRulesTest {

    private static final IdProperties ID = new IdProperties(true, "https://id.example.com", "http://pacod-id:8071",
            "studio", "s", "service", "s", "https://studio.example.com:8443", Duration.ofSeconds(60));

    private final DomainRules rules = new DomainRules(ID, portal(false));
    private final DomainRules shared = new DomainRules(ID, portal(true));

    private static PortalProperties portal(boolean shared) {
        return new PortalProperties(null, 3, 5, false, "", shared);
    }

    @Test
    @DisplayName("a host is stored lowercase, without a trailing dot")
    void normalises() {
        assertThat(rules.check(" News.Example-Sites.NET. ")).isEqualTo("news.example-sites.net");
        assertThat(rules.check("demo.localhost")).isEqualTo("demo.localhost");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "-bad.example.com", "bad-.example.com", "a..example.com",
            "10.0.0.1", "exa mple.com", "example.com/path", "ex_ample.com", "http://example.com"})
    @DisplayName("what is not a host name is refused")
    void refusesNonNames(String host) {
        assertThatThrownBy(() -> rules.check(host)).isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.domain.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"studio.example.com", "x.studio.example.com", "id.example.com", "deep.x.id.example.com"})
    @DisplayName("the studio's and sign-in's hosts, and every name below them, belong to no website")
    void refusesReserved(String host) {
        assertThatThrownBy(() -> rules.check(host)).isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.domain.reserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"news.example.com", "mystudio.example.com", "example.com", "www.example.com"})
    @DisplayName("a sibling of the studio's host is refused: on its registrable domain a website could set the studio's cookies")
    void refusesTheStudiosRegistrableDomain(String host) {
        assertThatThrownBy(() -> rules.check(host)).isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.domain.sameSite");
    }

    @ParameterizedTest
    @ValueSource(strings = {"news.example.com", "example.com", "www.example.com"})
    @DisplayName("with the domain shared on purpose, a sibling of the studio's host is a website's to claim")
    void sharedAllowsSiblings(String host) {
        assertThat(shared.check(host)).isEqualTo(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"studio.example.com", "x.studio.example.com", "id.example.com"})
    @DisplayName("with the domain shared on purpose, the studio's and sign-in's hosts still belong to no website")
    void sharedKeepsReserved(String host) {
        assertThatThrownBy(() -> shared.check(host)).isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.domain.reserved");
    }

    @Test
    @DisplayName("another registrable domain is a website's to claim, and so is a sibling under a public suffix")
    void otherDomainsAreFine() {
        assertThat(rules.check("news.example-sites.net")).isEqualTo("news.example-sites.net");
        DomainRules local = new DomainRules(new IdProperties(true, "http://id.localhost:8000", "http://pacod-id:8071",
                "studio", "s", "service", "s", "http://studio.localhost:8000", Duration.ofSeconds(60)), portal(false));
        assertThat(local.check("demo.localhost")).isEqualTo("demo.localhost");
        DomainRules suffix = new DomainRules(new IdProperties(true, "https://id.example.com", "http://pacod-id:8071",
                "studio", "s", "service", "s", "https://studio.github.io", Duration.ofSeconds(60)), portal(false));
        assertThat(suffix.check("news.github.io")).isEqualTo("news.github.io");
    }
}
