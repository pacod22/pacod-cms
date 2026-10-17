package io.pacod.cms.studio;

import io.pacod.cms.core.exceptions.MediaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The banner upload rules, which are deliberately not the article image rules.
 *
 * <p>{@code ImageService} enforces exactly 600x400 and 128 KB as layout constraints for the
 * article carousel. Banner creatives are 728x90, 300x250, 300x600 and whatever else is booked,
 * and an animated GIF leaderboard routinely exceeds 128 KB -- so these tests pin that the two
 * sets of rules stay apart.
 */
class BannerCreativeServiceTest {

    @TempDir
    Path tempDir;

    private BannerCreativeService service;
    private Path bannerFolder;

    @BeforeEach
    void setUp() {
        // Deliberately a path that does not exist yet: ImageService fails with NoSuchFileException
        // on a fresh volume because it never creates its parent, and this service must not.
        bannerFolder = tempDir.resolve("resources").resolve("banners");

        service = new BannerCreativeService();
        ReflectionTestUtils.setField(service, "bannerFolder", bannerFolder.toString());
    }

    private static MockMultipartFile gif(String filename, int bytes) {
        return new MockMultipartFile("file", filename, "image/gif", new byte[bytes]);
    }

    @Test
    @DisplayName("The target directory is created rather than assumed")
    void createsTheDirectory() throws MediaException {
        assertThat(bannerFolder).doesNotExist();

        service.store(7, gif("leaderboard.gif", 1024));

        assertThat(bannerFolder).isDirectory();
    }

    @Test
    @DisplayName("The stored name is prefixed with the banner id, and the public path is returned")
    void prefixesWithTheBannerId() throws MediaException {
        String source = service.store(7, gif("leaderboard.gif", 1024));

        assertThat(source).isEqualTo("/resources/banners/7-leaderboard.gif");
        assertThat(bannerFolder.resolve("7-leaderboard.gif")).exists();
    }

    /**
     * The reason for the prefix: uploads land in the same tree as the hand-placed HTML5 banner
     * folders, so two advertisers both sending {@code banner.gif} must not overwrite one another.
     */
    @Test
    @DisplayName("Two banners can upload the same filename without colliding")
    void doesNotCollideAcrossBanners() throws MediaException {
        String first = service.store(7, gif("banner.gif", 512));
        String second = service.store(8, gif("banner.gif", 512));

        assertThat(first).isNotEqualTo(second);
        assertThat(bannerFolder.resolve("7-banner.gif")).exists();
        assertThat(bannerFolder.resolve("8-banner.gif")).exists();
    }

    @Test
    @DisplayName("Anything bigger than the banner limit is refused, with the limit in the message")
    void refusesOversizeFiles() {
        int tooBig = (int) BannerCreativeService.MAX_BANNER_BYTES + 1;

        assertThatThrownBy(() -> service.store(7, gif("huge.gif", tooBig)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining(String.valueOf(BannerCreativeService.MAX_BANNER_BYTES / 1024));
    }

    /**
     * The headroom over {@code ImageService.MAX_IMAGE_BYTES} is the point of this service
     * existing; a change that quietly aligned the two would break the screen it was built for.
     */
    @Test
    @DisplayName("A creative larger than the article image limit is accepted")
    void acceptsWhatTheArticleRulesWouldRefuse() throws MediaException {
        service.store(7, gif("animated.gif", 300 * 1024));

        assertThat(bannerFolder.resolve("7-animated.gif")).exists();
    }

    @Test
    @DisplayName("A non-image content type is refused")
    void refusesForeignContentTypes() {
        MockMultipartFile pdf = new MockMultipartFile("file", "creative.pdf",
                "application/pdf", new byte[64]);

        assertThatThrownBy(() -> service.store(7, pdf))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("application/pdf");
    }

    @Test
    @DisplayName("WebP is allowed here even though the article rules do not allow it")
    void acceptsWebp() throws MediaException {
        MockMultipartFile webp = new MockMultipartFile("file", "creative.webp",
                "image/webp", new byte[64]);

        assertThat(service.store(7, webp)).endsWith("7-creative.webp");
    }

    @Test
    @DisplayName("An empty upload is refused before anything is written")
    void refusesEmptyUploads() {
        assertThatThrownBy(() -> service.store(7, gif("empty.gif", 0)))
                .isInstanceOf(MediaException.class);

        assertThat(bannerFolder).doesNotExist();
    }

    @Test
    @DisplayName("A filename carrying a path cannot escape the banner folder")
    void sanitisesTraversalAttempts() throws MediaException {
        String source = service.store(7, gif("../../etc/passwd", 64));

        assertThat(source).isEqualTo("/resources/banners/7-passwd");
        assertThat(bannerFolder.resolve("7-passwd")).exists();
        assertThat(tempDir.resolve("etc")).doesNotExist();
    }

    @Test
    @DisplayName("A non-ASCII filename becomes something a URL can carry")
    void sanitisesNonAsciiNames() throws MediaException {
        String source = service.store(7, gif("реклама.gif", 64));

        assertThat(source).startsWith("/resources/banners/7-");
        assertThat(source).matches("/resources/banners/[A-Za-z0-9._-]+");
    }

    @Test
    @DisplayName("Deleting removes a file this service wrote")
    void deletesItsOwnFiles() throws MediaException {
        String source = service.store(7, gif("banner.gif", 64));

        service.delete(source);

        assertThat(bannerFolder.resolve("7-banner.gif")).doesNotExist();
    }

    /**
     * A booking's source may name a file this service never wrote -- an HTML5 layout's folder, an
     * image placed elsewhere under {@code /assets/} by hand. Deleting a banner document must not
     * remove a file the console's creative upload never created.
     */
    @Test
    @DisplayName("Deleting ignores paths this service did not write")
    void leavesForeignPathsAlone() throws Exception {
        Files.createDirectories(bannerFolder);
        Path foreign = tempDir.resolve("partner.gif");
        Files.write(foreign, new byte[8]);

        service.delete("/assets/img/partners/partner.gif");
        service.delete("/resources/banners/acme2/index.html");
        service.delete(null);

        assertThat(foreign).exists();
    }

    @Test
    @DisplayName("Deleting an already-absent file is not an error")
    void toleratesAMissingFile() {
        service.delete("/resources/banners/7-gone.gif");
    }
}
