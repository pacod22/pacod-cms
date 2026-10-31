package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A website's public origin as the studio writes it: the scheme's default port left out, as the
 * site and browsers write it, any other port kept.
 */
class StudioSitesOriginTest {

    private static String origin(String scheme, String port) {
        return new StudioSites(mock(WebsiteRepository.class), mock(DomainRepository.class), scheme, port)
                .originOf("demo.localhost");
    }

    @Test
    @DisplayName("the scheme's default port is left out, any other port is kept")
    void defaultPortIsLeftOut() {
        assertThat(origin("http", "80")).isEqualTo("http://demo.localhost");
        assertThat(origin("https", "443")).isEqualTo("https://demo.localhost");
        assertThat(origin("https", "")).isEqualTo("https://demo.localhost");
        assertThat(origin("http", "8000")).isEqualTo("http://demo.localhost:8000");
        assertThat(origin("https", "80")).isEqualTo("https://demo.localhost:80");
    }
}
