package io.pacod.cms.studio;

import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.data.BannerSlide;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The only writer of a banner layout folder.
 *
 * <p>Writes into {@code app.banner.folder} -- the website's {@code resources/banners} -- which is
 * the same tree {@link BannerCreativeService} puts single-file creatives in and hand-placed HTML5
 * folders live in. The public site serves it, per website; the studio is the only writer.
 *
 * <h2>Why this is not part of {@link BannerCreativeService}</h2>
 *
 * <p>That class deletes a <em>file</em>, and its refusal of any path containing a {@code /} is
 * load-bearing: it is what stops deleting an image booking from removing a hand-placed HTML5 folder.
 * A layout is a directory, so extending that method to walk into one would remove exactly the guard
 * it exists to provide. Deleting a booking must never delete a layout; the two do not share a code
 * path, on purpose.
 *
 * <h2>Every path is checked for containment</h2>
 *
 * <p>The root is resolved absolute and normalised once, and every path derived from an id, a stored
 * filename or a ZIP entry is normalised and checked to still start with it -- the same shape
 * {@code MgmtImageService.fileFor} uses, and for the same reason: these names come from uploads and
 * from documents that may have been written by hand.
 */
@Slf4j
@Service
public class BannerLayoutStorage {

    /** At most this many entries in an imported archive. */
    public static final int MAX_ZIP_ENTRIES = 200;

    /** At most this much data after decompression, across all entries. */
    public static final long MAX_ZIP_UNCOMPRESSED_BYTES = 12L * 1024L * 1024L;

    /** At most this much from any single entry. */
    public static final long MAX_ZIP_ENTRY_BYTES = 4L * 1024L * 1024L;

    /**
     * What an imported archive may contain.
     *
     * <p>An allow-list rather than a deny-list, and it includes {@code js} because a hand-authored
     * banner ships one -- refusing it would refuse the archives the feature exists to absorb. What
     * it excludes is everything with no business in a creative: {@code .php}, {@code .jsp},
     * {@code .htaccess}, archives inside archives. The folder is served as static files (the public
     * site's {@code MediaController}), so none of these ever executes on the server; the list is
     * about not silently accepting rubbish, not about sandboxing.
     */
    public static final Set<String> ALLOWED_ZIP_EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "json", "map",
            "jpg", "jpeg", "png", "gif", "webp", "svg", "ico",
            "woff", "woff2", "ttf", "eot", "txt", "xml");

    private static final String CSS_FILE = "css/banner.css";
    private static final String JS_FILE = "js/banner.js";
    private static final String ROTATOR_RESOURCE = "banner-skeletons/rotator.js";

    /**
     * Where a folder is copied before the studio takes it over, when {@code app.banner.backup-folder}
     * names nowhere: a sibling of the banner root, so {@link #orphanFolders} never offers a backup
     * for adoption. Only the unit tests rely on it.
     */
    private static final String BACKUP_DIR = "banner-backups";

    /**
     * The website's backup folder ({@code {site}} template), outside {@code assets/} and
     * {@code resources/}: the public site serves those two trees of a website's storage, and a
     * backup is the advertiser's markup as it was, not something to publish. A sibling of the
     * banner root would be {@code resources/banner-backups/} -- served.
     */
    @Value("${app.banner.backup-folder:}")
    private String backupFolder;

    @Value("${app.banner.folder}")
    private String bannerFolder;

    /**
     * What is actually on disk for a layout.
     *
     * <p>{@code present} false means the folder is not there at all -- a booking can name a folder
     * that was never copied in, and short of this screen the only symptom is an empty iframe.
     */
    public record LayoutFiles(boolean present, boolean readable, int files, long bytes,
                              LocalDateTime modifiedOn, List<String> names) {

        public static LayoutFiles missing() {
            return new LayoutFiles(false, false, 0, 0L, null, List.of());
        }

        public static LayoutFiles unreadable() {
            return new LayoutFiles(true, false, 0, 0L, null, List.of());
        }
    }

    // ---------------------------------------------------------------- generating a built layout

    /**
     * Rewrites the three generated files of a {@link io.pacod.cms.core.data.LayoutOrigin#BUILT}
     * layout from the document and its skeleton.
     *
     * <p>Never touches image files: they are uploaded separately and are the one part of the folder
     * the document does not fully describe. That is also what makes this safe to call on every save
     * -- the generated files are derived and disposable, the images are not.
     */
    public void write(BannerLayout layout, BannerSkeleton skeleton) {

        Path folder = folderOf(layout.getId());
        try {
            Files.createDirectories(folder.resolve("css"));
            Files.createDirectories(folder.resolve("js"));

            writeText(folder.resolve(layout.getEntryFile()), renderIndex(layout, skeleton));
            writeText(folder.resolve(CSS_FILE), renderCss(layout, skeleton));
            writeText(folder.resolve(JS_FILE), rotator());
        } catch (IOException e) {
            log.error("Failed to write banner layout {}", folder, e);
            throw new MgmtOperationException("banners.error.layoutWriteFailed");
        }

        log.info("Wrote banner layout {} ({} slides)", folder, layout.slideCount());
    }

    /**
     * Assembles {@code index.html}: skeleton head, container start tag with the rotation interval
     * appended, one element per slide, then the skeleton tail -- which already carries the
     * container's closing tag.
     */
    String renderIndex(BannerLayout layout, BannerSkeleton skeleton) {

        StringBuilder html = new StringBuilder();
        html.append(skeleton.getHtmlHead());
        html.append(skeleton.getContainerTag())
                .append(" data-speed=\"").append(layout.getRotationMs()).append("\">")
                .append(System.lineSeparator());

        List<BannerSlide> slides = layout.getSlides() == null ? List.of() : layout.getSlides();
        for (int i = 0; i < slides.size(); i++) {
            html.append("      ").append(renderSlide(slides.get(i), i)).append(System.lineSeparator());
        }

        html.append(skeleton.getHtmlTail());

        String gtmId = layout.getGtmId();
        return gtmId == null || gtmId.isBlank()
                ? html.toString()
                : html.toString().replace(BannerSkeletonParser.GTM_TOKEN, gtmId);
    }

    /**
     * One slide, in the shape the hand-authored folders use -- {@code id="banner-1"} outside,
     * {@code id="a-banner-1"} on the link -- so a generated folder and a copied one read the same.
     *
     * <p>Slides after the first are written {@code display: none}, which is the state
     * {@code rotator.js} expects to inherit: the first frame is visible before any interval fires.
     *
     * <p>Every operator-supplied value goes through {@link #attr}. A quotation mark in an
     * advertiser's tooltip would otherwise close the attribute and put the rest of the copy into
     * the markup as tag soup.
     */
    private String renderSlide(BannerSlide slide, int index) {
        int n = index + 1;
        StringBuilder div = new StringBuilder();
        div.append("<div id=\"banner-").append(n).append('"');
        if (index > 0) {
            div.append(" style=\"display: none\"");
        }
        div.append("><a id=\"a-banner-").append(n).append('"')
                .append(" class=\"banner-link\"")
                .append(" title=\"").append(attr(slide.getTitle())).append('"')
                .append(" href=\"").append(attr(slide.getHref())).append('"')
                .append(" target=\"_blank\" rel=\"noopener\"")
                .append("><img src=\"").append(attr(slide.getImage())).append('"')
                .append(" alt=\"").append(attr(slide.getAlt())).append('"')
                .append(" class=\"banner-img\"></a></div>");
        return div.toString();
    }

    /**
     * The skeleton's stylesheet verbatim, with the layout's dimensions appended when it has any.
     *
     * <p>Appended rather than substituted into the skeleton's own rule. Rewriting the sidebar
     * skeleton's hardcoded {@code 300px}/{@code 250px} would work for exactly the one file it was
     * written against and silently do nothing for an operator-added skeleton that spells its size
     * differently. Equal specificity and a later declaration is a rule the cascade already has.
     *
     * <p>A fluid layout -- the top leaderboard -- leaves both null and gets nothing appended, which
     * is why the top skeleton declares no size of its own: there the
     * {@code .partner-card__frame--top} height on the site governs.
     */
    String renderCss(BannerLayout layout, BannerSkeleton skeleton) {
        String css = skeleton.getCss() == null ? "" : skeleton.getCss();
        if (layout.getWidth() == null || layout.getHeight() == null) {
            return css;
        }
        return css
                + System.lineSeparator()
                + "/* Размери на макета, зададени от конзолата. */" + System.lineSeparator()
                + ".banner-container-inline {" + System.lineSeparator()
                + "  width: " + layout.getWidth() + "px;" + System.lineSeparator()
                + "  height: " + layout.getHeight() + "px;" + System.lineSeparator()
                + "  max-width: " + layout.getWidth() + "px;" + System.lineSeparator()
                + "  max-height: " + layout.getHeight() + "px;" + System.lineSeparator()
                + "}" + System.lineSeparator();
    }

    /** The vendored rotator, read from the classpath. See {@code banner-skeletons/VENDORED.md}. */
    String rotator() {
        try (InputStream in = new ClassPathResource(ROTATOR_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Packaged in the jar beside this class; absent means the build is broken, not the data.
            throw new IllegalStateException("Vendored rotator " + ROTATOR_RESOURCE + " is missing", e);
        }
    }

    // ------------------------------------------------------------------------------- slide images

    /**
     * Stores a slide image inside the layout's folder and returns the bare filename to record on the
     * slide.
     *
     * <p>Reuses {@link BannerCreativeService#MAX_BANNER_BYTES} and
     * {@link BannerCreativeService#ALLOWED_CONTENT_TYPES} by reference rather than restating them:
     * a banner creative is a banner creative whether it arrives as a single file or as one frame of
     * a rotation, and two copies of the rule would drift.
     *
     * <p>No id prefix, unlike {@code BannerCreativeService.store}. There the files share one flat
     * directory with every other banner, so a prefix is what stops two advertisers' {@code
     * banner.gif} colliding; here each layout has a folder of its own and a prefix would only make
     * the generated markup harder to read against a hand-authored folder.
     */
    public String storeSlideImage(String layoutId, MultipartFile file) throws MediaException {

        if (file == null || file.isEmpty()) {
            throw MgmtText.media("banners.error.noFile");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read uploaded slide image for layout {}", layoutId, e);
            throw MgmtText.media("banners.error.unreadable");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw MgmtText.media("banners.error.noName");
        }
        if (bytes.length == 0) {
            throw MgmtText.media("banners.error.empty", filename);
        }
        if (bytes.length > BannerCreativeService.MAX_BANNER_BYTES) {
            throw MgmtText.media("banners.error.tooLarge",
                    filename, bytes.length / 1024, BannerCreativeService.MAX_BANNER_BYTES / 1024);
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !BannerCreativeService.ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw MgmtText.media("banners.error.wrongType",
                    filename, String.valueOf(contentType),
                    String.valueOf(BannerCreativeService.ALLOWED_CONTENT_TYPES));
        }

        String stored = uniqueName(layoutId, sanitise(filename));
        Path target = requireInsideFolder(layoutId, stored, "banners.error.fileOutsideLayout");

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Failed to store slide image {}", target, e);
            throw MgmtText.media("banners.error.writeFailed");
        }

        log.info("Stored slide image {} ({} bytes) for layout {}", target, bytes.length, layoutId);
        return stored;
    }

    /**
     * Removes a slide's file, once nothing in the layout references it any more.
     *
     * <p>Tolerant of an absent file for the reason {@code ImageService.deleteImage} is: a record
     * without a file is a state the console has to be able to get out of.
     */
    public void deleteSlideImage(String layoutId, String filename) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        Path target;
        try {
            target = requireInsideFolder(layoutId, filename, "banners.error.fileOutsideLayout");
        } catch (MgmtOperationException e) {
            // Logged rather than thrown: this runs while removing a slide, and a stored name that
            // escapes the folder must not stop the operator removing the row that carries it.
            log.warn("Refusing to delete slide image outside the layout folder: {}/{}", layoutId, filename);
            return;
        }
        try {
            if (Files.deleteIfExists(target)) {
                log.info("Deleted slide image {}", target);
            }
        } catch (IOException e) {
            log.error("Could not delete slide image {}", target, e);
        }
    }

    // ------------------------------------------------------------------------------------ import

    /** What an imported archive turned out to contain. */
    public record Imported(String entryFile, int files) {
    }

    /**
     * Unpacks an uploaded archive into the layout's folder and works out which file the booking
     * should point at.
     *
     * <p>Two rules here are not free choices, and both come from the archives this feature has to
     * absorb rather than from taste:
     *
     * <ul>
     *   <li><b>A single shared leading directory is stripped.</b> Zipping a folder -- which is what
     *       every operator will do -- produces {@code banner-top-template/index.html}. Unpacked
     *       verbatim the entry file lands a level below the folder the booking names, and the
     *       symptom is a 404 inside an iframe.</li>
     *   <li><b>The entry file is {@code index.html} at the root, or the single {@code .html} if
     *       there is exactly one, or the import is refused naming the candidates.</b>
     *       A hand-built folder such as {@code /resources/banners/Globex_300_250/} may answer at
     *       {@code Globex_300_250.html} and have no index, so assuming one would refuse a folder
     *       already serving a booking -- and picking arbitrarily between several is a decision the
     *       console must not make silently.</li>
     * </ul>
     */
    public Imported importArchive(String layoutId, MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new MgmtOperationException("banners.error.noFile");
        }

        List<ZipFileEntry> entries = readArchive(file);
        if (entries.isEmpty()) {
            throw new MgmtOperationException("banners.error.zipEmpty");
        }

        String prefix = sharedLeadingDirectory(entries);
        List<ZipFileEntry> flattened = new ArrayList<>();
        for (ZipFileEntry entry : entries) {
            String name = prefix == null ? entry.name() : entry.name().substring(prefix.length());
            flattened.add(new ZipFileEntry(name, entry.bytes()));
        }

        String entryFile = chooseEntryFile(flattened);

        Path folder = folderOf(layoutId);
        try {
            Files.createDirectories(folder);
            for (ZipFileEntry entry : flattened) {
                Path target = requireInsideFolder(layoutId, entry.name(),
                        "banners.error.zipPathOutside", entry.name());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.bytes());
            }
        } catch (IOException e) {
            log.error("Failed to unpack banner layout archive into {}", folder, e);
            throw new MgmtOperationException("banners.error.zipUnpackFailed");
        }

        log.info("Imported banner layout {} from archive: {} files, entry {}",
                layoutId, flattened.size(), entryFile);
        return new Imported(entryFile, flattened.size());
    }

    private record ZipFileEntry(String name, byte[] bytes) {
    }

    /**
     * Reads every file entry into memory, refusing anything oversized, misnamed or pointing out of
     * the folder.
     *
     * <p>Containment is checked twice -- on the entry name here and on the resolved path at write
     * time -- because a zip-slip name is the one input in this class that arrives from outside and
     * both checks are cheap. The uncompressed-size cap is what a compression ratio makes necessary:
     * the multipart limit bounds what arrives, not what it expands to.
     */
    private List<ZipFileEntry> readArchive(MultipartFile file) {

        List<ZipFileEntry> entries = new ArrayList<>();
        long total = 0;

        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {

                if (entry.isDirectory()) {
                    continue;
                }
                if (entries.size() >= MAX_ZIP_ENTRIES) {
                    throw new MgmtOperationException("banners.error.zipTooManyFiles", MAX_ZIP_ENTRIES);
                }

                String name = normaliseEntryName(entry.getName());
                if (name == null) {
                    continue;
                }
                requireAllowedExtension(name);

                byte[] bytes = readEntry(zip, name);
                total += bytes.length;
                if (total > MAX_ZIP_UNCOMPRESSED_BYTES) {
                    throw new MgmtOperationException("banners.error.zipTooLarge",
                            MAX_ZIP_UNCOMPRESSED_BYTES / 1024 / 1024);
                }

                entries.add(new ZipFileEntry(name, bytes));
            }
        } catch (IOException e) {
            log.warn("Could not read uploaded banner layout archive", e);
            throw new MgmtOperationException("banners.error.zipUnreadable");
        }

        return entries;
    }

    private byte[] readEntry(ZipInputStream zip, String name) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = zip.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_ZIP_ENTRY_BYTES) {
                throw new MgmtOperationException("banners.error.zipEntryTooLarge", name,
                        MAX_ZIP_ENTRY_BYTES / 1024 / 1024);
            }
        }
        return out.toByteArray();
    }

    /**
     * A ZIP entry name reduced to a relative path, or null for something to skip.
     *
     * <p>Refuses absolute paths and {@code ..} outright rather than sanitising them: an archive
     * carrying either was not produced by zipping a folder, and silently repairing it would hide
     * that. Skips the metadata directories the two desktop archivers add, which are noise rather
     * than an attack.
     */
    private String normaliseEntryName(String raw) {
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isBlank()) {
            return null;
        }
        if (name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store") || name.equals(".DS_Store")
                || name.contains("/.")) {
            return null;
        }
        // The `..` check is on a whole path SEGMENT, not a substring: `sprite..webp` is a legal
        // filename, and refusing it with a message about escaping the folder would be a lie.
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")
                || List.of(name.split("/")).contains("..")) {
            throw new MgmtOperationException("banners.error.zipPathOutside", raw);
        }
        return name;
    }

    private void requireAllowedExtension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_ZIP_EXTENSIONS.contains(ext)) {
            throw new MgmtOperationException("banners.error.zipExtension", name,
                    String.join(", ", new java.util.TreeSet<>(ALLOWED_ZIP_EXTENSIONS)));
        }
    }

    /** The one directory every entry sits under, or null when they do not share one. */
    private String sharedLeadingDirectory(List<ZipFileEntry> entries) {
        String prefix = null;
        for (ZipFileEntry entry : entries) {
            int slash = entry.name().indexOf('/');
            if (slash < 0) {
                return null;
            }
            String first = entry.name().substring(0, slash + 1);
            if (prefix == null) {
                prefix = first;
            } else if (!prefix.equals(first)) {
                return null;
            }
        }
        return prefix;
    }

    /** See {@link #importArchive} for why this is a rule rather than a guess. */
    private String chooseEntryFile(List<ZipFileEntry> entries) {

        List<String> rootHtml = entries.stream()
                .map(ZipFileEntry::name)
                .filter(name -> !name.contains("/"))
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".html") || lower.endsWith(".htm");
                })
                .sorted()
                .toList();

        for (String name : rootHtml) {
            if (name.equalsIgnoreCase("index.html")) {
                return name;
            }
        }
        if (rootHtml.size() == 1) {
            return rootHtml.getFirst();
        }
        if (rootHtml.isEmpty()) {
            throw new MgmtOperationException("banners.error.zipNoHtml");
        }
        throw new MgmtOperationException("banners.error.zipSeveralHtml", String.join(", ", rootHtml));
    }

    // ------------------------------------------------------------------------------ discovery

    /** A folder in the banner tree that no layout document describes. */
    public record Orphan(String id, String entryFile, int files, boolean adoptable) {
    }

    /**
     * Folders sitting in the banner tree that the console does not yet know about.
     *
     * <p>This is what makes a folder the console did not create -- one copied in by hand --
     * reachable at all. Without it the screens describe only folders the console itself created.
     *
     * <p>Directories only. {@link BannerCreativeService} writes single files into the same tree --
     * an image booking's creative -- and those are not layouts.
     */
    public List<Orphan> orphanFolders(Set<String> known) {

        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<Orphan> orphans = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> folders = entries.filter(Files::isDirectory).sorted().toList();
            for (Path folder : folders) {
                String id = folder.getFileName().toString();
                if (known.contains(id) || id.startsWith(".")) {
                    continue;
                }
                String entry = entryFileIn(folder);
                orphans.add(new Orphan(id, entry, countFiles(folder), entry != null));
            }
        } catch (IOException e) {
            // Same contract as stat(): the screen states what it found, and finding nothing is a
            // worse answer than an empty list only if it pretends otherwise.
            log.warn("Could not list the banner folder {}", root, e);
            return List.of();
        }
        return orphans;
    }

    /**
     * The file a discovered folder answers at, or null when it has no HTML at its root.
     *
     * <p>Same rule as an imported archive, and for the same reason: a folder such as
     * {@code Globex_300_250/} may answer at {@code Globex_300_250.html} and have no index, so
     * assuming one would hide a live booking.
     */
    private String entryFileIn(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            List<String> html = entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return lower.endsWith(".html") || lower.endsWith(".htm");
                    })
                    .sorted()
                    .toList();
            for (String name : html) {
                if (name.equalsIgnoreCase("index.html")) {
                    return name;
                }
            }
            return html.size() == 1 ? html.getFirst() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private int countFiles(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Reads a text file out of a layout's folder, or null if it is absent or unreadable. */
    public String readText(String layoutId, String relativePath) {
        try {
            Path target = requireInsideFolder(layoutId, relativePath, "banners.error.pathOutsideLayout");
            return Files.isRegularFile(target) ? Files.readString(target, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {} in layout {}", relativePath, layoutId, e);
            return null;
        }
    }

    /**
     * Copies a folder aside before the console rewrites it for the first time.
     *
     * <p>Adoption makes a folder console-owned, and the first save regenerates markup nobody here
     * authored. That is the same bet {@code MgmtArticleService.save} hedges by keeping
     * {@code Article.previousText} -- and the same answer: keep the previous state where an operator
     * can get at it. The copy goes <b>outside</b> the served tree, so nothing publishes it and it
     * cannot be mistaken for a booking.
     */
    public void backup(String layoutId) {

        Path source = folderOf(layoutId);
        if (!Files.isDirectory(source)) {
            return;
        }

        Path target = backupRoot().resolve(layoutId);
        try {
            if (Files.exists(target)) {
                log.info("Banner layout {} already has a pre-adoption backup; keeping the original", layoutId);
                return;
            }
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path path : walk.toList()) {
                    Path relative = source.relativize(path);
                    Path copy = target.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(copy);
                    } else {
                        Files.createDirectories(copy.getParent());
                        Files.copy(path, copy);
                    }
                }
            }
            log.info("Backed up banner layout {} to {} before adoption", layoutId, target);
        } catch (IOException e) {
            // Loud, and fatal to the adoption: taking over a folder the console cannot first copy
            // means the operator has no way back to what the advertiser supplied.
            log.error("Could not back up banner layout {} before adoption", layoutId, e);
            throw new MgmtOperationException("banners.error.adoptBackupFailed");
        }
    }

    // ---------------------------------------------------------------------------- the file editor

    /**
     * What may be edited as text.
     *
     * <p>Narrower than {@link #ALLOWED_ZIP_EXTENSIONS} on purpose: a font or an image is in a folder
     * legitimately but is not something a textarea can round-trip, and offering to "edit" one would
     * corrupt it silently.
     */
    public static final Set<String> EDITABLE_TEXT_EXTENSIONS =
            Set.of("html", "htm", "css", "js", "json", "txt", "xml", "map");

    /** What may be swapped by upload. The same set the slide uploader accepts, plus svg. */
    public static final Set<String> REPLACEABLE_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "ico");

    /** At most this much text in one file, which is well past any real banner script. */
    public static final long MAX_TEXT_BYTES = 512L * 1024L;

    /** One file of a layout folder, as the file screen lists it. */
    public record LayoutFile(String path, long bytes, boolean text, boolean image) {
    }

    /** Every file in the folder, with what can be done to each. */
    public List<LayoutFile> listFiles(String layoutId) {

        Path folder = folderOf(layoutId);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        List<LayoutFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                String ext = extensionOf(relative);
                files.add(new LayoutFile(relative, Files.size(file),
                        EDITABLE_TEXT_EXTENSIONS.contains(ext),
                        REPLACEABLE_IMAGE_EXTENSIONS.contains(ext)));
            }
        } catch (IOException e) {
            log.warn("Could not list the files of layout {}", layoutId, e);
            return List.of();
        }
        return files;
    }

    /**
     * Overwrites a text file inside the folder.
     *
     * <p>This is how an advertiser's animation export gets a new destination URL: the address is a
     * {@code clickTag} string in its own script, and there is nothing structured about it for the
     * console to bind a form field to. Rather than pretend otherwise, the file itself is editable.
     *
     * <p>Three guards, and none is optional. The path must resolve <b>inside the layout's folder</b>,
     * the extension must be one a textarea can round-trip, and the content is capped. The tree is
     * served as static files so nothing here executes on the server -- but a path that
     * escaped the folder would still let one booking overwrite another's creative.
     */
    public void writeTextFile(String layoutId, String relativePath, String content) {

        Path target = resolveExisting(layoutId, relativePath);
        String extension = extensionOf(relativePath);

        if (!EDITABLE_TEXT_EXTENSIONS.contains(extension)) {
            throw new MgmtOperationException("banners.error.notText", relativePath,
                    String.join(", ", new java.util.TreeSet<>(EDITABLE_TEXT_EXTENSIONS)));
        }

        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new MgmtOperationException("banners.error.textTooLarge", relativePath, MAX_TEXT_BYTES / 1024);
        }

        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Could not write {} in layout {}", relativePath, layoutId, e);
            throw new MgmtOperationException("banners.error.writeFailed");
        }
        log.info("Edited {} in banner layout {} ({} bytes)", relativePath, layoutId, bytes.length);
    }

    /**
     * Replaces one file with an uploaded one, <b>keeping its exact name</b>.
     *
     * <p>Deliberately unlike {@link #storeSlideImage}, which stores under a fresh name. There the
     * document holds the filename and can be pointed at a new one; here the name is referenced from
     * markup and scripts this console did not write -- an animation export names its sprite atlas
     * from inside its own JavaScript -- so a new name would leave the banner loading a file that is
     * no longer there.
     *
     * <p>The cost is that browsers and the proxy cache by path, so a reader may keep the previous
     * creative until their cache expires. That is the trade an in-place replacement makes, and it is
     * stated on the screen rather than engineered around.
     */
    public void replaceFile(String layoutId, String relativePath, MultipartFile file) throws MediaException {

        Path target = resolveExisting(layoutId, relativePath);
        String extension = extensionOf(relativePath);

        if (!REPLACEABLE_IMAGE_EXTENSIONS.contains(extension)) {
            throw MgmtText.media("banners.error.notImage", relativePath);
        }
        if (file == null || file.isEmpty()) {
            throw MgmtText.media("banners.error.noFile");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw MgmtText.media("banners.error.unreadable");
        }
        if (bytes.length > BannerCreativeService.MAX_BANNER_BYTES) {
            throw MgmtText.media("banners.error.replaceTooLarge",
                    bytes.length / 1024, BannerCreativeService.MAX_BANNER_BYTES / 1024);
        }
        // The uploaded file has to BE what the name says it is, because everything referencing it
        // does so by that name: swapping a .png for a .webp under the old name serves the wrong
        // content type and renders nothing.
        if (!extension.equals(extensionOf(file.getOriginalFilename()))) {
            throw MgmtText.media("banners.error.sameKind", extension);
        }

        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Could not replace {} in layout {}", relativePath, layoutId, e);
            throw MgmtText.media("banners.error.writeFailed");
        }
        log.info("Replaced {} in banner layout {} ({} bytes)", relativePath, layoutId, bytes.length);
    }

    /** A path that exists inside the layout's folder, or a refusal naming which rule it broke. */
    private Path resolveExisting(String layoutId, String relativePath) {
        Path target = requireInsideFolder(layoutId, relativePath, "banners.error.pathOutsideLayout");
        if (!Files.isRegularFile(target)) {
            throw new MgmtNotFoundException("banners.error.noSuchFile", relativePath);
        }
        return target;
    }

    private String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------ reading, delete

    /**
     * What is on disk for {@code layoutId}.
     *
     * <p>Never throws. Absent, unreadable and empty are three different facts the screen states, the
     * same contract {@code MgmtImageService.fileOf} keeps -- and for the same reason: this is the
     * only place a booking whose folder is missing becomes visible as anything other than an empty
     * iframe on the public site.
     */
    public LayoutFiles stat(String layoutId) {

        Path folder;
        try {
            folder = folderOf(layoutId);
        } catch (RuntimeException e) {
            log.warn("Refusing to stat a layout folder outside the banner root: {}", layoutId);
            return LayoutFiles.missing();
        }

        if (!Files.isDirectory(folder)) {
            return LayoutFiles.missing();
        }

        List<String> names = new ArrayList<>();
        long bytes = 0;
        LocalDateTime newest = null;

        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                names.add(folder.relativize(file).toString().replace('\\', '/'));
                bytes += Files.size(file);
                LocalDateTime modified = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()),
                        ZoneId.systemDefault());
                if (newest == null || modified.isAfter(newest)) {
                    newest = modified;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read banner layout folder {}", folder, e);
            return LayoutFiles.unreadable();
        }

        return new LayoutFiles(true, true, names.size(), bytes, newest, names);
    }

    /**
     * Removes the layout's folder and everything in it.
     *
     * <p>Deletes depth-first through {@link Files#walk} with a reverse ordering, checking every path
     * stays inside the root as it goes. Deliberately its own method rather than a widened
     * {@link BannerCreativeService#delete} -- see the class javadoc for what that guard protects.
     */
    public void deleteFolder(String layoutId) {

        Path folder = folderOf(layoutId);
        if (!Files.exists(folder)) {
            log.warn("Banner layout folder {} was already absent", folder);
            return;
        }

        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                requireInside(path.normalize(), "banners.error.deleteOutside");
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.error("Could not delete banner layout folder {}", folder, e);
            throw new MgmtOperationException("banners.error.layoutDeleteFailed");
        }

        log.info("Deleted banner layout folder {}", folder);
    }

    // ------------------------------------------------------------------------------------ helpers

    /** The absolute, normalised root every layout folder sits under. */
    Path backupRoot() {
        return backupFolder == null || backupFolder.isBlank()
                ? root().resolveSibling(BACKUP_DIR)
                : Path.of(SiteStorage.folder(backupFolder)).toAbsolutePath().normalize();
    }

    Path root() {
        // The bound website's banner folder: app.banner.folder is a {site} template (SiteStorage).
        return Path.of(SiteStorage.folder(bannerFolder)).toAbsolutePath().normalize();
    }

    private Path folderOf(String layoutId) {
        if (layoutId == null || layoutId.isBlank()) {
            throw new MgmtOperationException("banners.error.layoutNoFolder");
        }
        Path folder = root().resolve(layoutId).normalize();
        requireInside(folder, "banners.error.layoutOutside");
        return folder;
    }

    private void requireInside(Path path, String message) {
        Path root = root();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new MgmtOperationException(message);
        }
    }

    /**
     * The stricter containment check: inside <em>this layout's</em> folder, not merely inside the
     * banner tree.
     *
     * <p>The difference is the whole guard for anything that takes a path from a request.
     * {@code ../index.html} out of layout {@code a} normalises to a path that still starts with the
     * banner root, so {@link #requireInside} lets it through -- and it names another booking's
     * creative. Every path that arrives as a parameter goes through this one; {@link #requireInside}
     * remains right for a path derived from an id alone.
     */
    private Path requireInsideFolder(String layoutId, String relativePath, String message, Object... args) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new MgmtOperationException("banners.error.noFileNamed");
        }
        Path folder = folderOf(layoutId);
        Path target = folder.resolve(relativePath).normalize();
        if (!target.startsWith(folder) || target.equals(folder)) {
            throw new MgmtOperationException(message, args);
        }
        return target;
    }

    /**
     * Escapes a value for an HTML attribute written between double quotes.
     *
     * <p>Not optional and not paranoia: an advertiser's tooltip is free text an operator pastes in,
     * and a single quotation mark in it would close the attribute and spill the rest of the copy
     * into the markup as tags. Ampersand first, or the escapes would escape each other. The console
     * is the author of this file, so nothing downstream escapes it again.
     */
    static String attr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void writeText(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Same rules as {@code BannerCreativeService.sanitise}, which this deliberately mirrors. */
    private String sanitise(String original) {
        String base = original == null ? "" : original;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "-");
        return base.isBlank() || base.replace("-", "").replace(".", "").isEmpty() ? "slide.png" : base;
    }

    /**
     * A name not already taken inside the folder.
     *
     * <p>Unlike a single creative, a layout holds several images and the operator uploads them one
     * at a time. Two frames called {@code banner.webp} are entirely normal, and overwriting the
     * first would replace a slide the operator was not editing.
     */
    private String uniqueName(String layoutId, String candidate) {
        Path folder = root().resolve(layoutId);
        if (!Files.exists(folder.resolve(candidate))) {
            return candidate;
        }
        int dot = candidate.lastIndexOf('.');
        String stem = dot < 0 ? candidate : candidate.substring(0, dot);
        String ext = dot < 0 ? "" : candidate.substring(dot);
        for (int i = 2; i < 500; i++) {
            String next = stem + "-" + i + ext;
            if (!Files.exists(folder.resolve(next))) {
                return next;
            }
        }
        throw new MgmtOperationException("banners.error.tooManyNamed");
    }
}
