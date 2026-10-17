package io.pacod.cms.site.controller;

import io.pacod.cms.core.tenant.SiteStorage;
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

/**
 * Each website's files -- article images, banner creatives and HTML5 banner folders, article
 * PDFs -- served from that website's own storage folder, at {@code /assets/…} and
 * {@code /resources/…}.
 *
 * <p>Served here, not by the proxy: with many websites the proxy cannot know which website's
 * folder a path belongs to, and the Host is what says; so the application serves them, behind
 * {@code HostSiteResolverFilter}, and the proxy may cache.
 *
 * <p>The HTML5 banners have to stay <b>same-origin</b>: the public page counts clicks by listening
 * inside the banner's frame, which a browser only allows for a document of the same origin. This
 * is that origin.
 */
@Controller
public class MediaController {

    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();

    private final String storageRoot;

    public MediaController(@Value("${pacod.storage.root}") String storageRoot) {
        this.storageRoot = storageRoot;
    }

    @GetMapping({"/assets/**", "/resources/**"})
    public ResponseEntity<Resource> serve(HttpServletRequest request, ServletWebRequest web) throws IOException {
        String path = PATHS.getPathWithinApplication(request);
        Path root = SiteStorage.siteRoot(storageRoot);
        // Normalised and then checked to be inside the tree the address names -- assets/ or
        // resources/ of this website: "/assets/../../x" must reach neither another website's
        // files nor this website's own unpublished ones (its backups), nor anything on the host.
        String tree = path.startsWith("/assets/") ? "assets" : "resources";
        Path served = root.resolve(tree);
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(served) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        long lastModified = Files.getLastModifiedTime(file).toMillis();
        if (web.checkNotModified(lastModified)) {
            return null;
        }
        MediaType type = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CACHE)
                .lastModified(lastModified)
                .body(new FileSystemResource(file));
    }
}
