package io.pacod.cms.studio.site;

import io.pacod.cms.studio.access.StudioPermission;

import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * The website's pictures, for the studio's own screens: thumbnails, the image library, and the
 * images an article body shows inside the editor.
 *
 * <p><b>Raster images only, and never anything that can run.</b> A website's folder also holds
 * HTML5 banners -- HTML and JavaScript its owner wrote -- and an SVG is a document that can carry
 * script. Served from the studio's origin, any of those would run with the studio's cookies, which
 * is the one thing the separate site domain exists to prevent. So this answers exactly the five
 * raster types, with {@code nosniff} and a sandboxing CSP on top, and 404s everything else; a
 * banner's own preview is framed from the website's origin instead.
 *
 * <p>Inside the authenticated chain, unlike {@code /mgmt-assets/**}: these are the website's files,
 * and only someone signed in to the studio has any business reading them here -- and, like every
 * other screen of a website, only someone who may read its content ({@code content.read}).
 */
@StudioPermission(read = "content.read")
@Controller
public class StudioMediaController {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static final Set<MediaType> RASTER = Set.of(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.IMAGE_GIF,
            MediaType.parseMediaType("image/webp"), MediaType.parseMediaType("image/avif"));

    private static final String SANDBOX = "default-src 'none'; sandbox";

    private final String storageRoot;

    public StudioMediaController(@Value("${pacod.storage.root}") String storageRoot) {
        this.storageRoot = storageRoot;
    }

    /** The addresses this answers; {@link StudioSiteFilter} resolves a website for them from the Referer. */
    static boolean isPreviewPath(String path) {
        return path.startsWith("/assets/img/") || path.startsWith("/resources/banners/");
    }

    @GetMapping({"/assets/img/**", "/resources/banners/**"})
    public ResponseEntity<Resource> preview(HttpServletRequest request, ServletWebRequest web) throws IOException {
        if (TenantContext.find().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String path = PATHS.getPathWithinApplication(request);
        Path root = SiteStorage.siteRoot(storageRoot);
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        MediaType type = MediaTypeFactory.getMediaType(file.getFileName().toString()).orElse(null);
        if (type == null || !RASTER.contains(type)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        long lastModified = Files.getLastModifiedTime(file).toMillis();
        if (web.checkNotModified(lastModified)) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .lastModified(lastModified)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", SANDBOX)
                .body(new FileSystemResource(file));
    }
}
