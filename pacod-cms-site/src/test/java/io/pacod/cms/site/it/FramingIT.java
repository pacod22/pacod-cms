package io.pacod.cms.site.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may frame the site, as the browser is told over real HTTP -- a MockMvc without the security
 * chain never shows these headers. Spring Security's default {@code X-Frame-Options: DENY} blanks
 * every HTML5 booking, the website's own iframe of its own folder, and nothing on the server
 * notices.
 */
class FramingIT extends TwoWebsitesIT {

    @BeforeAll
    void bannerFolder() throws IOException {
        Path entry = STORAGE.resolve("sites/alpha/resources/banners/framing-probe/index.html");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "<!doctype html><p>creative</p>");
    }

    @Test
    @DisplayName("a banner folder may be framed by its website and the studio; no X-Frame-Options")
    void bannerFolderIsFramable() {
        HttpResponse<String> response = get(ALPHA_HOST, "/resources/banners/framing-probe/index.html");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValue("frame-ancestors 'self' " + STUDIO_ORIGIN);
        assertThat(response.headers().firstValue("X-Frame-Options")).isEmpty();
    }

    @Test
    @DisplayName("a page may be framed by its own website only")
    void pagesAreSameOrigin() {
        HttpResponse<String> response = get(ALPHA_HOST, "/p/about");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue("frame-ancestors 'self'");
        assertThat(response.headers().allValues("X-Frame-Options")).containsExactly("SAMEORIGIN");
    }

    @Test
    @DisplayName("the error page carries the same policy")
    void errorPage() {
        HttpResponse<String> response = get(ALPHA_HOST, "/p/no-such-page");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue("frame-ancestors 'self'");
        assertThat(response.headers().allValues("X-Frame-Options")).containsExactly("SAMEORIGIN");
    }
}
