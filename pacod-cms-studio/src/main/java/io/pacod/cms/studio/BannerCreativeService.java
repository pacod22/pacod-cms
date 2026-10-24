package io.pacod.cms.studio;

import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Stores an uploaded banner creative on the content volume.
 *
 * <h2>Why this is not {@code ImageService}</h2>
 * That service enforces 600x400 and 128 KB by default ({@code app.image.*}), and both numbers are
 * layout constraints for the article carousel rather than general upload hygiene. Banner
 * creatives are 728x90, 300x250, 300x600 and whatever else an advertiser books, and an animated
 * GIF is routinely larger than 128 KB. Reusing it would mean loosening the article rules to
 * accommodate advertising, which is exactly backwards.
 *
 * <p>Note that raising {@code spring.servlet.multipart.max-file-size} for this service does
 * <em>not</em> loosen article images: {@code ImageService.validate} checks
 * {@code app.image.max-bytes} itself, independently of the container limit, precisely so that the
 * two cannot drift.
 *
 * <h2>Where the bytes go</h2>
 * {@code app.banner.folder}, the website's {@code resources/banners} -- the same tree the HTML5
 * banner folders live in, served by the public site. That shared tree is why the stored name is
 * prefixed with the banner id rather than trusting the uploaded filename to be unique: two
 * advertisers both sending {@code banner.gif} would otherwise silently overwrite one another, and
 * {@code ImageService}'s answer to that -- refusing the upload -- would be a worse experience for
 * a screen whose whole job is replacing creatives.
 */
@Slf4j
@Service
public class BannerCreativeService {

    /**
     * Deliberately larger than the article limit ({@code app.image.max-bytes}, 400 KB by default):
     * an animated GIF leaderboard routinely exceeds it. Must stay at or below
     * {@code spring.servlet.multipart.max-file-size}, which rejects an oversize upload before
     * this class ever runs.
     */
    public static final long MAX_BANNER_BYTES = 1024L * 1024L;

    /**
     * Wider than the article set by one, WebP: there is no carousel layout here to be fussy on
     * behalf of.
     */
    public static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /** The public prefix the stored files are served under. Also the {@code Banner.source} prefix. */
    public static final String PUBLIC_PREFIX = "/resources/banners/";

    @Value("${app.banner.folder}")
    private String bannerFolder;

    /**
     * Writes {@code file} for banner {@code bannerId} and returns the public path to store in
     * {@code Banner.source}.
     *
     * <p>Deliberately does no dimension check. A banner's frame height is chosen by the operator
     * through {@code BannerSize}, so there is no single correct size to enforce, and refusing a
     * creative the advertiser supplied would make the console unusable for its actual purpose.
     */
    public String store(Integer bannerId, MultipartFile file) throws MediaException {

        if (bannerId == null) {
            throw MgmtText.media("banners.error.noBanner");
        }
        if (file == null || file.isEmpty()) {
            throw MgmtText.media("banners.error.noFile");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read uploaded banner creative for banner {}", bannerId, e);
            throw MgmtText.media("banners.error.unreadable");
        }

        validate(file, bytes);

        String stored = bannerId + "-" + sanitise(file.getOriginalFilename());
        Path target = Path.of(SiteStorage.folder(bannerFolder), stored);

        try {
            // ImageService does not do this and fails with NoSuchFileException on a fresh volume.
            // The banner directory is far more likely to be absent: unlike the article image
            // folder, nothing else in the stack creates it.
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Failed to store banner creative {}", target, e);
            throw MgmtText.media("banners.error.writeFailed");
        }

        log.info("Stored banner creative {} ({} bytes) for banner {}", target, bytes.length, bannerId);
        return PUBLIC_PREFIX + stored;
    }

    /**
     * Deletes a previously stored creative, given the public path held in {@code Banner.source}.
     *
     * <p>A no-op for anything this service did not write -- an HTML5 banner's {@code index.html},
     * or a creative placed in the website's folder by hand. Deleting a banner document must not
     * remove a file the console never created.
     */
    public void delete(String publicPath) {
        if (publicPath == null || !publicPath.startsWith(PUBLIC_PREFIX)) {
            return;
        }

        String name = publicPath.substring(PUBLIC_PREFIX.length());
        // The path came out of store(), but a hand-edited document could carry anything.
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            log.warn("Refusing to delete banner creative with a suspicious path: {}", publicPath);
            return;
        }

        Path target = Path.of(SiteStorage.folder(bannerFolder), name);
        try {
            if (Files.deleteIfExists(target)) {
                log.info("Deleted banner creative {}", target);
            } else {
                log.warn("Banner creative {} was already absent", target);
            }
        } catch (IOException e) {
            // Losing the document while the file lingers is the lesser problem; an orphaned file
            // costs disk, an aborted delete costs the operator the screen.
            log.error("Could not delete banner creative {}", target, e);
        }
    }

    private void validate(MultipartFile file, byte[] bytes) throws MediaException {

        String filename = file.getOriginalFilename();

        if (filename == null || filename.isBlank()) {
            throw MgmtText.media("banners.error.noName");
        }

        if (bytes.length == 0) {
            throw MgmtText.media("banners.error.empty", filename);
        }

        if (bytes.length > MAX_BANNER_BYTES) {
            throw MgmtText.media("banners.error.tooLarge",
                    filename, bytes.length / 1024, MAX_BANNER_BYTES / 1024);
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw MgmtText.media("banners.error.wrongType",
                    filename, String.valueOf(contentType), String.valueOf(ALLOWED_CONTENT_TYPES));
        }
    }

    /**
     * Reduces an uploaded filename to something safe to concatenate into a path.
     *
     * <p>Strips any directory component first -- some browsers still send a full client path --
     * then replaces everything outside {@code [A-Za-z0-9._-]}. That covers traversal, but it is
     * also what keeps a Cyrillic filename from producing a URL the markup has to escape.
     */
    private String sanitise(String original) {
        String base = original == null ? "" : original;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "-");
        // A name that was entirely non-ASCII collapses to dashes; give it something to be.
        return base.isBlank() || base.replace("-", "").replace(".", "").isEmpty() ? "creative" : base;
    }
}
