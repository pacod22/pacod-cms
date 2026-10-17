package io.pacod.cms.site.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A website's files are served on its own hosts only, and nothing outside its folder at all. */
class StorageIsolationIT extends TwoWebsitesIT {

    @BeforeAll
    void files() throws IOException {
        Path image = STORAGE.resolve("sites/alpha/assets/img/articles/only-alpha.jpg");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        Files.writeString(STORAGE.resolve("secret.txt"), "outside every website");
        Path backup = STORAGE.resolve("sites/alpha/backups/banners/x/index.html");
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "outside every website");
    }

    @Test
    @DisplayName("a website's image answers on its host and 404s on another's")
    void imageIsTheWebsitesOwn() {
        assertThat(get(ALPHA_HOST, "/assets/img/articles/only-alpha.jpg").statusCode()).isEqualTo(200);
        assertThat(get(BETA_HOST, "/assets/img/articles/only-alpha.jpg").statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/assets/../../alpha/assets/img/articles/only-alpha.jpg",
            "/assets/%2e%2e/%2e%2e/alpha/assets/img/articles/only-alpha.jpg",
            "/assets/..%2F..%2Falpha%2Fassets%2Fimg%2Farticles%2Fonly-alpha.jpg",
            "/assets/../../../secret.txt",
            "/resources/%2e%2e/%2e%2e/%2e%2e/secret.txt",
            // A website's own files outside assets/ and resources/ -- its backups -- are not published.
            "/resources/%2e%2e/backups/banners/x/index.html",
            "/resources/../backups/banners/x/index.html",
    })
    @DisplayName("no path climbs out of the website's folder")
    void traversalIsRefused(String path) {
        for (String host : new String[]{ALPHA_HOST, BETA_HOST}) {
            assertThat(get(host, path).statusCode()).isNotEqualTo(200);
            assertThat(get(host, path).body()).doesNotContain("outside every website");
        }
    }
}
