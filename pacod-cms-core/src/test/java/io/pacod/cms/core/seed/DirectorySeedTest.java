package io.pacod.cms.core.seed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A seed folder reads its own files and nothing else: no path out of it, no link through which
 * one could be reached, and a listing a creative's import would take.
 */
class DirectorySeedTest {

    @TempDir
    Path tmp;

    private Path root;
    private DirectorySeed seed;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("seed"));
        Files.writeString(root.resolve("seed.json"), "{}");
        Files.createDirectories(root.resolve("banners/gw/css"));
        Files.writeString(root.resolve("banners/gw/index.html"), "<a>gw</a>");
        Files.writeString(root.resolve("banners/gw/css/creative.css"), "a{}");
        Files.writeString(root.resolve("banners/gw/.DS_Store"), "x");
        Files.writeString(tmp.resolve("secret.txt"), "outside");
        seed = new DirectorySeed(root);
    }

    @Test
    @DisplayName("a file of the folder is read by its relative path")
    void readsItsFiles() {
        assertThat(seed.read("seed.json")).asString().isEqualTo("{}");
        assertThat(seed.read("banners/gw/css/creative.css")).asString().isEqualTo("a{}");
        assertThat(seed.external()).isTrue();
        assertThat(seed.seedFile()).isEqualTo("seed.json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "banners/../../secret.txt", "/etc/hosts", "banners//gw/index.html",
            "banners/./gw/index.html", "banners\\gw\\index.html", "", " ", "seed.json\0"})
    @DisplayName("a path that is absolute, climbs out, or has an odd segment is refused")
    void refusesPathsOut(String path) {
        assertThatThrownBy(() -> seed.read(path)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("may not");
    }

    @Test
    @DisplayName("a link is never followed: not to a file outside, not through a linked folder")
    void refusesLinks() throws IOException {
        Files.createSymbolicLink(root.resolve("photo.jpg"), tmp.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("elsewhere"), tmp);
        assertThatThrownBy(() -> seed.read("photo.jpg")).hasMessageContaining("link");
        assertThatThrownBy(() -> seed.read("elsewhere/secret.txt")).hasMessageContaining("link");
        assertThatThrownBy(() -> seed.files("elsewhere")).hasMessageContaining("link");
    }

    @Test
    @DisplayName("a missing file, or a folder in its place, is said to be so")
    void missingOrNotAFile() {
        assertThatThrownBy(() -> seed.read("photos/none.jpg")).hasMessageContaining("missing");
        assertThatThrownBy(() -> seed.read("banners")).hasMessageContaining("not a file");
    }

    @Test
    @DisplayName("a folder lists its files in name order, below it, without hidden ones or links")
    void listsAFolder() throws IOException {
        Files.createSymbolicLink(root.resolve("banners/gw/linked.html"), tmp.resolve("secret.txt"));
        assertThat(seed.files("banners/gw")).containsExactly("css/creative.css", "index.html");
        assertThat(seed.files("banners/gw/")).containsExactly("css/creative.css", "index.html");
        assertThat(seed.files("banners/none")).isEmpty();
    }

    @Test
    @DisplayName("a folder that is not there is no seed")
    void refusesAMissingFolder() {
        assertThatThrownBy(() -> new DirectorySeed(tmp.resolve("none"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DirectorySeed(tmp.resolve("secret.txt"))).isInstanceOf(IllegalArgumentException.class);
    }
}
