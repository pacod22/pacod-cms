package io.pacod.cms.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule two applications depend on.
 *
 * <p>{@code MgmtBannerService} applies it on save in the studio and
 * {@code BannerClickController} applies it again before writing a {@code Location} header in the
 * public site. The method lives here rather than in either of them so those two can never drift;
 * this test is what the shared copy is worth.
 */
class HttpUrlsTest {

    @Test
    @DisplayName("http and https targets are accepted, case and surrounding space included")
    void acceptsHttpAndHttps() {
        assertThat(HttpUrls.isHttpUrl("https://example.com/")).isTrue();
        assertThat(HttpUrls.isHttpUrl("http://example.com/")).isTrue();
        assertThat(HttpUrls.isHttpUrl("HTTPS://example.com/")).isTrue();
        assertThat(HttpUrls.isHttpUrl("  https://example.com/  ")).isTrue();
    }

    @Test
    @DisplayName("everything that would make /b/{id} an open redirect or an XSS sink is refused")
    void refusesEverythingElse() {
        // The one that matters: /b/{id} puts this straight into a Location header.
        assertThat(HttpUrls.isHttpUrl("javascript:alert(1)")).isFalse();
        // Protocol-relative. Reads like a path, resolves to another origin.
        assertThat(HttpUrls.isHttpUrl("//example.com/")).isFalse();
        assertThat(HttpUrls.isHttpUrl("ftp://example.com/")).isFalse();
        assertThat(HttpUrls.isHttpUrl("")).isFalse();
        assertThat(HttpUrls.isHttpUrl("   ")).isFalse();
        assertThat(HttpUrls.isHttpUrl(null)).isFalse();
    }
}
