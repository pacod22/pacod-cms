package io.pacod.cms.core.services;

import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class ImageService {

    /** Limits the max-id lookup to a single document. */
    private static final Pageable TOP_ONE = PageRequest.of(0, 1);

    /**
     * Not configurable, and deliberately so. These are not a limit on what an image may be -- they
     * are the question "is this an image at all", asked of every caller of {@code save()}, a
     * future one included: this is the last guard in that door. The two settings below relax a
     * layout rule; neither may open it.
     */
    public static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");

    /**
     * The size a new upload must be, in pixels and in bytes.
     *
     * <p>Settings, not {@code public static final} constants: the console's
     * {@code MgmtControllerAdvice} and {@code UploadSizeAdvice} autowire this service to read
     * them, so there is no second copy of a number to drift -- the same reason the console
     * renders its upload help text from here rather than from a hardcoded string.
     *
     * <p>The defaults are written in the annotations and nowhere else. This class is scanned by
     * both applications and every module's test profile, so a {@code @Value} with no default
     * would fail startup in every one that does not declare the property.
     *
     * <p><b>{@code app.image.max-bytes} is a {@link DataSize}, so it takes {@code 131072} and
     * {@code 2MB} alike.</b> That is not a convenience: the console's own properties file spells
     * {@code spring.servlet.multipart.max-file-size=4MB} a few lines below it and the session
     * timeout {@code 8h}. An operator following that idiom on a plain {@code long} would fail
     * String-to-long conversion at bean creation -- the context never starts and the studio
     * container restart-loops, on the one setting whose whole purpose is raising a limit.
     * Accepting both spellings removes the trap rather than documenting it.
     *
     * <p><b>It is only effective up to the multipart ceiling.</b>
     * {@code spring.servlet.multipart.max-file-size} and Caddy's {@code request_body max_size} are
     * both 4MB, and past that the refusal stops being ours to phrase: Tomcat rejects an oversize
     * multipart inside the CSRF filter's first {@code getParameter()} call, below Spring, where no
     * {@code @ControllerAdvice} can see it. Raising this above 4MB moves nothing; raise all three
     * together or not at all.
     */
    @Value("${app.image.required-width:600}")
    private int requiredWidth;

    @Value("${app.image.required-height:400}")
    private int requiredHeight;

    @Value("${app.image.max-bytes:131072}")
    private DataSize maxImageBytes;

    /**
     * The two limits above, switched on and off independently. Four working combinations.
     *
     * <p>Two flags rather than one, because they are two different rules that happen to share a
     * screen. The dimension check is about LAYOUT: the public site takes an article's first visible
     * image as its share card and as every list and widget thumbnail, and renders the rest as a
     * scroll-snap gallery strip whose slides are one column wide -- with it off the site goes on
     * rendering, nothing crops and nothing fails, but those thumbnails and slides carry mixed
     * aspect ratios. The file-size check is about WEIGHT: what a reader downloads, and what the
     * storage holds. An operator who wants a 1200x800 hero image has no reason to also accept a 3MB
     * one, and an operator who wants a large 600x400 photograph has no reason to give up the
     * layout. One flag could not express either.
     *
     * <p><b>Neither switch reaches the content type or the {@code ImageIO} readability check.</b>
     * See {@link #ALLOWED_CONTENT_TYPES} for why that is not an omission.
     *
     * <p><b>Off is not unlimited.</b> With {@code file-size-check} off, an upload is still bounded
     * by {@code spring.servlet.multipart.max-file-size} and by Caddy's
     * {@code request_body max_size}, both 4MB -- and past those the refusal is a bare 413 that no
     * {@code @ControllerAdvice} can phrase. The console states that ceiling rather than saying
     * nothing about size, because a hint that omits it reads as "any size" and an operator would
     * learn otherwise from the 413.
     *
     * <p>Both apply on upload and nowhere else. Nothing migrates and nothing re-checks: an image
     * stored before a limit changed, or with a check off, may satisfy neither, which is why
     * {@code /mgmt/images/{id}} reports a mismatch as a note rather than an error.
     */
    @Value("${app.image.dimension-check.enabled:true}")
    private boolean dimensionCheckEnabled;

    @Value("${app.image.file-size-check.enabled:true}")
    private boolean fileSizeCheckEnabled;

    @Autowired
    private ImageMediaRepository imageMediaRepository;

    /**
     * Only ever read, and only by {@link #deleteImage(Integer)}. No cycle: {@code ArticleService}
     * depends on this class, this class depends on the repository rather than on that service.
     */
    @Autowired
    private ArticleRepository articleRepository;

    /**
     * A template: {@code {site}} is replaced by the bound website's id on every write (see
     * {@link SiteStorage}), so each website's images are in a folder of their own.
     */
    @Value("${app.image.folder}")
    private String imageFolder;

    /**
     * The last segment of a client-supplied file name, or {@code null} when nothing usable is left.
     * Backslashes count as separators too: a Windows browser may send a whole path.
     */
    static String safeFilename(String original) {
        if (original == null) {
            return null;
        }
        String name = original.replace('\\', '/');
        // Whitespace becomes a hyphen: the name is written verbatim into every src and href that
        // shows the image, where a raw space is not a valid URL character.
        name = name.substring(name.lastIndexOf('/') + 1).strip().replaceAll("\\s+", "-");
        return name.isEmpty() || name.equals(".") || name.equals("..") ? null : name;
    }

    /**
     * Deletes an image's file and document -- unless another article still holds it.
     *
     * <h2>Why the reference count is here and not in the caller</h2>
     * The management console's image library attaches an <em>existing</em> image to a second
     * article, so an unconditional delete would break the other article's page. Every path that
     * deletes an image comes through here -- {@code MgmtArticleService.removeImage},
     * {@code ArticleService.deleteArticle} and the image library's own delete in
     * {@code MgmtImageService} -- and a new one must not have to remember the rule, so it lives at
     * the point they share, exactly as {@code HttpUrls.isHttpUrl} does for the banner redirect.
     *
     * <p>The two that empty an article detach before they call this: {@code removeImage} saves the
     * article without the image first, and {@code deleteArticle} removes the article document
     * before cleaning its images. That ordering is what makes the count exclude the article being
     * emptied -- reverse either and nothing is ever deleted.
     *
     * @return {@code true} when the document was removed; {@code false} when the image is still
     *         referenced, when the file could not be deleted, or when there is no such image.
     */
    public boolean deleteImage(Integer id) {

        log.info("starting deleteImage (id = {})", id);

        boolean result = false;

        if(id != null) {
            long references = articleRepository.countArticleByImages(id);
            if (references > 0) {
                log.info("Image {} is still used by {} article(s); keeping the file and the record",
                        id, references);
                return false;
            }

            String imageFilename = null;

            File imageFile = null;

            Optional<ImageMedia> oImageMedia = imageMediaRepository.findById(id);

            if(oImageMedia.isPresent()) {

                ImageMedia imageMedia = oImageMedia.get();

                log.info("image has been found: image = {})", imageMedia);

                imageFilename = SiteStorage.folder(imageFolder) + File.separator + imageMedia.getFilename();
                imageFile = new File(imageFilename);

                log.info("about to delete image file: {})", imageFile);

                boolean fileDeleted = imageFile.delete();

                // A missing file means there is nothing left to delete, so the record goes either
                // way; only a file that still exists afterwards is a real failure. Keeping the
                // record whenever the file delete fails would leave an image whose file was
                // already gone (manually removed, failed upload, restored backup) as an orphaned
                // row this path could never clean up.
                if (!fileDeleted && imageFile.exists()) {
                    log.error("Could not delete image file {}; keeping the DB record so the "
                            + "two do not diverge", imageFile);
                    return false;
                }

                if (!fileDeleted) {
                    log.warn("Image file {} was already absent; removing the orphaned DB record", imageFile);
                }

                log.info("delete image db entry: {})", id);
                imageMediaRepository.deleteById(id);
                result = true;

            }
        }

        return result;
    }

    /**
     * The upload rules, for anything that has to <em>state</em> them rather than apply them.
     *
     * <p>The console's {@code MgmtControllerAdvice} puts these on every screen's model so the
     * editor's upload hint and the notes on {@code /mgmt/images/{id}} say what this method
     * would refuse, and {@code UploadSizeAdvice} names the byte limit in its flash message. They
     * are getters on the service rather than constants because a constant cannot be configured and
     * a second copy of the number would drift.
     */
    public int getRequiredWidth() {
        return requiredWidth;
    }

    public int getRequiredHeight() {
        return requiredHeight;
    }

    public long getMaxImageBytes() {
        return maxImageBytes.toBytes();
    }

    /** False when {@code app.image.dimension-check.enabled} is off; see the field's javadoc. */
    public boolean isDimensionCheckEnabled() {
        return dimensionCheckEnabled;
    }

    /**
     * False when {@code app.image.file-size-check.enabled} is off -- which removes this
     * application's cap and not the transport's. See the field's javadoc.
     */
    public boolean isFileSizeCheckEnabled() {
        return fileSizeCheckEnabled;
    }

    /** Reads one document off the index instead of loading every image's id. */
    private Integer getNextId() {
        return imageMediaRepository.getMaxId(TOP_ONE)
                .stream()
                .findFirst()
                .map(i -> i.getId() + 1)
                .orElse(1);
    }

    public void init() {

    }


    public ImageMedia save(MultipartFile file) throws MediaException {
        log.info("Starting save");
        if (file == null) {
            return null;
        }
        log.info("File: {}", file.getName());

        // Read once: the multipart stream is single-pass, and both the dimension check and
        // the write below need the content.
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            String message = String.format("IOException caught! Failed to read uploaded file: %s",
                    file.getOriginalFilename());
            log.error(message);
            throw new MediaException(message, "articles.media.readFailed", file.getOriginalFilename());
        }
        return save(file.getOriginalFilename(), file.getContentType(), bytes, file.getResource().getDescription());
    }

    /**
     * Stores an image the platform made itself -- the demo content's pictures -- under exactly the
     * rules and into exactly the folder an upload goes through: one {@code validate}, one write, so
     * the two cannot drift apart.
     *
     * @param originalFilename the name to store under, made safe as an upload's is
     */
    public ImageMedia save(String originalFilename, String contentType, byte[] bytes, String caption)
            throws MediaException {
        ImageMedia imageMedia = null;
        validate(originalFilename, contentType, bytes);

        Integer id = getNextId();
        log.info("Id: {}", id);
        if(id > 0) {
            imageMedia = new ImageMedia(id);
            imageMedia.setCaption(caption);
            imageMedia.setContent(contentType);
            // The last path segment only: the name arrives from the client, and a crafted
            // "../../x" must not choose where the file is written.
            imageMedia.setFilename(safeFilename(originalFilename));

            Optional<ImageMedia> oImageMedia = imageMediaRepository.findImageMediaByFilename(imageMedia.getFilename());

            if(oImageMedia.isEmpty()) {

                String imageFilename = SiteStorage.folder(imageFolder) + File.separator + imageMedia.getFilename();

                log.info("Full filename: {}", imageFilename);

                File imageFile = new File(imageFilename);


                log.info("Content type: {}", contentType);

                try {
                    // Each website's folder is created on its first upload.
                    Files.createDirectories(imageFile.toPath().toAbsolutePath().getParent());
                    Files.write(imageFile.toPath(), bytes);
                } catch (IOException e) {
                    String message = String.format("IOException caught! Failed to store image file: %s", imageMedia.getFilename());
                    log.error(message);
                    throw new MediaException(message, "articles.media.storeFailed", imageMedia.getFilename());
                }

                log.info("Image media: {}", imageMedia);
                imageMediaRepository.save(imageMedia);
            } else {
                String message = String.format("File already exists: %s", imageMedia.getFilename());
                log.error(message);
                throw new MediaException(message, "articles.media.exists", imageMedia.getFilename());
            }
        }

        return imageMedia;
    }

    /**
     * Enforces the upload rules on the server, where it counts.
     *
     * <p>A check made only in the browser is bypassed by anything posting straight at the
     * endpoint, so none of these may move to the client.
     *
     * <p>Two of the rules are switchable and two are not. {@code app.image.dimension-check.enabled}
     * governs the pixel size and {@code app.image.file-size-check.enabled} the byte cap, each
     * independently of the other -- all four combinations are supported and none is special-cased.
     * The empty-file, content-type and readability checks are not negotiable at any setting; see
     * {@link #ALLOWED_CONTENT_TYPES}.
     *
     * <p>Note {@code spring.servlet.multipart.max-file-size} rejects an oversize file before
     * this method ever runs; the size check here is what covers a request that arrives by some
     * other route.
     */
    private void validate(String originalFilename, String contentType, byte[] bytes) throws MediaException {

        String filename = safeFilename(originalFilename);

        if (filename == null || filename.isBlank()) {
            throw new MediaException("The uploaded file has no name.", "articles.media.noName");
        }

        if (bytes.length == 0) {
            throw new MediaException(String.format("The uploaded file is empty: %s", filename),
                    "articles.media.empty", filename);
        }

        long maxBytes = maxImageBytes.toBytes();
        if (fileSizeCheckEnabled && bytes.length > maxBytes) {
            throw new MediaException(String.format(
                    "%s is %d bytes; the limit is %d.", filename, bytes.length, maxBytes),
                    "articles.media.tooLarge", filename, bytes.length, maxBytes);
        }

        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new MediaException(String.format(
                    "%s has content type %s; allowed: %s.", filename, contentType, ALLOWED_CONTENT_TYPES),
                    "articles.media.contentType", filename, String.valueOf(contentType),
                    String.valueOf(ALLOWED_CONTENT_TYPES));
        }

        BufferedImage image;
        try {
            // ImageIO returns null rather than throwing when no reader recognises the stream,
            // which is how a renamed non-image arrives here.
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new MediaException(String.format("%s could not be read as an image.", filename),
                    "articles.media.unreadable", filename);
        }

        if (image == null) {
            throw new MediaException(String.format(
                    "%s is not a readable image, whatever its extension says.", filename),
                    "articles.media.notImage", filename);
        }

        // The read above is NOT skipped with the check off: its null result is what catches a
        // renamed non-image, and that is a validity question rather than a layout one.
        if (!dimensionCheckEnabled) {
            log.debug("Dimension check is off; accepting {} at {}x{}",
                    filename, image.getWidth(), image.getHeight());
            return;
        }

        if (image.getWidth() != requiredWidth || image.getHeight() != requiredHeight) {
            throw new MediaException(String.format(
                    "%s is %dx%d; the site layout requires exactly %dx%d.",
                    filename, image.getWidth(), image.getHeight(), requiredWidth, requiredHeight),
                    "articles.media.dimensions",
                    filename, image.getWidth(), image.getHeight(), requiredWidth, requiredHeight);
        }
    }

    public String getFiletype(String content) {
        String filetype;

        switch (content) {
            case "image/jpeg":
                filetype = "jpg";
                break;
            case "image/png":
                filetype = "png";
                break;
            case "image/gif":
                filetype = "gif";
                break;
            default:
                filetype = "jpg";
                break;
        }
        return filetype;
    }


    public Stream<Path> loadAll() {
        return null;
    }


    public Path load(String filename) {
        return null;
    }


    public Resource loadAsResource(String filename) {
        return null;
    }


    public void deleteAll() {

    }
}
