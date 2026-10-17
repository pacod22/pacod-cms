package io.pacod.cms.studio;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.data.BannerSlide;
import io.pacod.cms.core.data.LayoutOrigin;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.BannerLayoutRepository;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.core.util.HttpUrls;
import io.pacod.cms.studio.form.BannerLayoutForm;
import io.pacod.cms.studio.form.BannerSlideForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Banner layout administration: the folders behind the site's HTML5 bookings.
 *
 * <p>In {@code io.pacod.cms.studio} rather than a sub-package because {@link MgmtSearch} is
 * package-private and the list screen filters through it -- the same reason
 * {@code MgmtPageService} and {@code MgmtBannerService} live here.
 *
 * <p>Standard console shape otherwise: {@link #require} then a read-modify-write, refusals as
 * {@link MgmtOperationException} for the controller to turn into a flash message. What is unusual is
 * that a save has a filesystem side, and the ordering matters in two places -- see {@link #save} and
 * {@link #delete}.
 */
@Slf4j
@Service
public class MgmtBannerLayoutService {

    /**
     * What a folder name may be.
     *
     * <p>Narrow on purpose. It becomes a path segment on the public site, a directory in the
     * website's storage and the id of a document, so anything needing escaping in one of the three
     * would be wrong in the other two. It also has to stay clear of {@code BannerCreativeService}'s
     * single-file names in the same directory, which are {@code {id}-{file}} and always contain a
     * dot -- this pattern admits neither dots nor digits at the start.
     */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");

    /**
     * Names a layout may not take, because a literal path segment beats {@code /{id}} on
     * specificity and one of these would shadow a real route -- reachable by nothing, and 404ing
     * with a message about a missing layout.
     */
    private static final Set<String> RESERVED = Set.of("new", "import", "adopt", "skeletons");

    private final BannerLayoutRepository layoutRepository;
    private final BannerRepository bannerRepository;
    private final MgmtBannerSkeletonService skeletonService;
    private final BannerLayoutStorage storage;
    private final BannerSkeletonParser parser;

    public MgmtBannerLayoutService(BannerLayoutRepository layoutRepository,
                                   BannerRepository bannerRepository,
                                   MgmtBannerSkeletonService skeletonService,
                                   BannerLayoutStorage storage,
                                   BannerSkeletonParser parser) {
        this.layoutRepository = layoutRepository;
        this.bannerRepository = bannerRepository;
        this.skeletonService = skeletonService;
        this.storage = storage;
        this.parser = parser;
    }

    /**
     * One row of the list: the layout, how many bookings run it, and whether its folder is there.
     *
     * <p>The folder check is the point of the column. A booking can name a folder that is absent
     * from storage, and short of this screen the only symptom is an empty iframe on the public
     * site.
     */
    public record LayoutRow(BannerLayout layout, long bookings, boolean folderPresent) {
    }

    /**
     * The list, optionally filtered.
     *
     * <p>Reads both collections whole and joins them in the JVM -- tens of documents each, and
     * {@code BannerRepository} has no finder over {@code source} because adding one would not remove
     * the read {@code findAll} already does. The same trade the banner, author and category lists
     * make.
     */
    public List<LayoutRow> findAll(String search) {

        List<Banner> banners = bannerRepository.findAll();
        List<LayoutRow> rows = new ArrayList<>();

        for (BannerLayout layout : layoutRepository.findAll()) {
            if (!MgmtSearch.matches(search, layout.getId(), layout.getTitle(),
                    layout.getOrigin(), layout.getSkeleton(), layout.getEntryFile())) {
                continue;
            }
            rows.add(new LayoutRow(layout, countBookings(banners, layout),
                    storage.stat(layout.getId()).present()));
        }

        rows.sort(Comparator.comparing(row -> row.layout().getId()));
        return rows;
    }

    public Optional<BannerLayout> find(String id) {
        return id == null || id.isBlank() ? Optional.empty() : layoutRepository.findById(id);
    }

    /** What is on disk. Delegated so a screen never talks to the filesystem service directly. */
    public BannerLayoutStorage.LayoutFiles files(String id) {
        return storage.stat(id);
    }

    /**
     * The bookings that would break if this layout's folder went away.
     *
     * <p>Matched on {@link BannerLayout#publicFolder()}, not {@link BannerLayout#publicPath()}: a
     * booking written by hand may point at a different file in the same folder, and deleting the
     * folder breaks it just the same.
     */
    public List<Banner> bookingsUsing(String id) {
        BannerLayout layout = require(id);
        List<Banner> using = new ArrayList<>();
        for (Banner banner : bannerRepository.findAll()) {
            if (banner.getSource() != null && banner.getSource().startsWith(layout.publicFolder())) {
                using.add(banner);
            }
        }
        return using;
    }

    /** Creates the document and writes the folder from the skeleton, with no slides yet. */
    public BannerLayout create(String id, String title, String skeletonId) {

        String normalized = normaliseId(id);
        if (layoutRepository.existsById(normalized)) {
            throw new MgmtOperationException("banners.error.layoutExists", normalized);
        }

        BannerSkeleton skeleton = skeletonService.require(skeletonId);

        BannerLayout layout = new BannerLayout(normalized);
        layout.setTitle(title == null || title.isBlank() ? normalized : title.strip());
        layout.setOrigin(LayoutOrigin.BUILT);
        layout.setSkeleton(skeleton.getId());
        layout.setWidth(skeleton.getWidth());
        layout.setHeight(skeleton.getHeight());
        layout.setGtmId(skeleton.getGtmId());

        storage.write(layout, skeleton);
        log.info("Created banner layout {} from skeleton {}", normalized, skeleton.getId());
        return layoutRepository.save(layout);
    }

    /**
     * Registers a folder uploaded whole and served verbatim.
     *
     * <p>The archive is unpacked before the document is written, so a refused import leaves no row
     * behind -- the opposite ordering to {@link #delete}, and for the mirror-image reason.
     */
    public BannerLayout importArchive(String id, String title, MultipartFile file) {

        String normalized = normaliseId(id);
        if (layoutRepository.existsById(normalized)) {
            throw new MgmtOperationException("banners.error.layoutExists", normalized);
        }

        BannerLayoutStorage.Imported imported = storage.importArchive(normalized, file);

        BannerLayout layout = new BannerLayout(normalized);
        layout.setTitle(title == null || title.isBlank() ? normalized : title.strip());
        layout.setOrigin(LayoutOrigin.IMPORTED);
        layout.setEntryFile(imported.entryFile());

        log.info("Imported banner layout {} ({} files, entry {})",
                normalized, imported.files(), imported.entryFile());
        return layoutRepository.save(layout);
    }

    // ------------------------------------------------------------------------------- discovery

    /** Folders in the banner tree that no document describes yet. */
    public List<BannerLayoutStorage.Orphan> discover() {
        Set<String> known = layoutRepository.findAll().stream()
                .map(BannerLayout::getId)
                .collect(Collectors.toSet());
        return storage.orphanFolders(known);
    }

    /**
     * Takes an existing folder under console management.
     *
     * <p>This is what makes an advertisement the console did not create <em>editable</em> rather
     * than only replaceable, and it is the reason the whole feature is worth having: without it the
     * only way to change a URL in a hand-copied folder is to edit a file on the server.
     *
     * <p>Two outcomes, decided by what is actually in the folder rather than by what the operator
     * hopes:
     *
     * <ul>
     *   <li>A folder whose entry file has a {@code .banner-container-inline} with frames in it
     *       becomes {@link LayoutOrigin#BUILT}: its chrome becomes a skeleton, its frames become
     *       slides, its rotation interval comes off its own script, and every screen in the builder
     *       works on it.</li>
     *   <li>Anything else -- an Adobe Animate or Google Web Designer export, where the animation is a
     *       timeline inside a sprite atlas -- becomes {@link LayoutOrigin#IMPORTED}. There is no
     *       honest way to present a frame count for those, so the console does not invent one and
     *       offers the file editor instead.</li>
     * </ul>
     *
     * <p>The folder is <b>copied aside before anything is written</b>, and the id is taken
     * <b>verbatim</b> -- see {@link #adoptedId}.
     */
    public BannerLayout adopt(String folderName) {

        String id = adoptedId(folderName);
        if (layoutRepository.existsById(id)) {
            throw new MgmtOperationException("banners.error.layoutExists", id);
        }

        BannerLayoutStorage.Orphan orphan = discover().stream()
                .filter(o -> o.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new MgmtNotFoundException("banners.error.noOrphan", id));

        BannerLayout layout = new BannerLayout(id);
        layout.setTitle(id);
        layout.setEntryFile(orphan.entryFile() == null ? "index.html" : orphan.entryFile());

        String html = orphan.entryFile() == null ? null : storage.readText(id, orphan.entryFile());
        BannerSkeleton skeleton = html == null ? null : skeletonService.adoptFrom(id, html,
                storage.readText(id, "css/banner.css"));

        if (skeleton == null) {
            layout.setOrigin(LayoutOrigin.IMPORTED);
            log.info("Adopted banner layout {} as imported ({} files, entry {})",
                    id, orphan.files(), layout.getEntryFile());
            return layoutRepository.save(layout);
        }

        // Only now, and before a single byte is written back.
        storage.backup(id);

        layout.setOrigin(LayoutOrigin.BUILT);
        layout.setSkeleton(skeleton.getId());
        layout.setWidth(skeleton.getWidth());
        layout.setHeight(skeleton.getHeight());
        layout.setGtmId(skeleton.getGtmId());
        layout.setSlides(parser.readSlides(html));

        Integer speed = parser.speedFrom(storage.readText(id, "js/banner.js"));
        if (speed != null) {
            layout.setRotationMs(speed);
        }

        log.info("Adopted banner layout {} as built: {} slides, skeleton {}, {} ms",
                id, layout.slideCount(), skeleton.getId(), layout.getRotationMs());
        return layoutRepository.save(layout);
    }

    /**
     * A discovered folder's id is its name on disk, exactly.
     *
     * <p>Deliberately <b>not</b> {@link #normaliseId}. That pattern is right for a folder the console
     * is about to create and wrong for one that already exists: hand-made folders are named like
     * {@code Globex_300_250} or {@code acme2_}, which it does not admit, and the booking's
     * {@code source} names the path as it is. Lowercasing would be worse than refusing --
     * {@code globex_300_250} resolves on a case-insensitive filesystem such as macOS's, and 404s on
     * a Linux host.
     *
     * <p>What is still checked is containment, by {@code BannerLayoutStorage}: the name comes off the
     * filesystem here, but it reaches the same {@code resolve().normalize().startsWith(root)} guard
     * every other path in that class does.
     */
    private String adoptedId(String folderName) {
        String id = folderName == null ? "" : folderName.strip();
        if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new MgmtOperationException("banners.error.badFolderName");
        }
        // The one place an id arrives from the filesystem rather than a form, and therefore the one
        // place nothing else applies this rule. A folder literally called `adopt` or `skeletons`
        // would take over a route of this controller's own: the document would be created and then
        // be permanently unreachable, 404ing about a missing layout.
        requireNotReserved(id);
        return id;
    }

    private void requireNotReserved(String id) {
        if (RESERVED.contains(id.toLowerCase(Locale.ROOT))) {
            throw new MgmtOperationException("banners.error.reservedName", id);
        }
    }

    /**
     * Writes the edited fields and regenerates the folder.
     *
     * <p>The folder is written <em>before</em> the document is saved. Both orders lose something if
     * the second step fails, and this is the cheaper loss: a folder that is one save ahead of its
     * document renders the same markup the operator just submitted, whereas a document ahead of its
     * folder shows the console one thing and the public site another, with nothing on any screen
     * saying so.
     */
    public BannerLayout save(String id, BannerLayoutForm form) {

        BannerLayout layout = require(id);
        if (!layout.isBuilt()) {
            throw new MgmtOperationException("banners.error.importedNotEditable");
        }

        validate(layout, form);

        layout.setTitle(form.getTitle().strip());
        layout.setWidth(form.getWidth());
        layout.setHeight(form.getHeight());
        layout.setRotationMs(form.getRotationMs());
        layout.setGtmId(blankToNull(form.getGtmId()));
        applySlides(layout, form.getSlides());
        layout.setLastModifiedOn(LocalDateTime.now());

        storage.write(layout, skeletonService.require(layout.getSkeleton()));
        log.info("Saved banner layout {} ({} slides)", layout.getId(), layout.slideCount());
        return layoutRepository.save(layout);
    }

    /**
     * Uploads one more frame.
     *
     * <p>Its own endpoint rather than a field on the form, exactly as an article image and a banner
     * creative are: the file is durable the moment it succeeds, so an operator who uploads and then
     * navigates away has not lost it.
     */
    public BannerLayout addSlide(String id, MultipartFile file) throws MediaException {

        BannerLayout layout = requireBuilt(id);

        String stored = storage.storeSlideImage(layout.getId(), file);
        layout.getSlides().add(new BannerSlide(stored));
        layout.setLastModifiedOn(LocalDateTime.now());

        storage.write(layout, skeletonService.require(layout.getSkeleton()));
        return layoutRepository.save(layout);
    }

    /** Removes a frame and, if nothing else references it, its file. */
    public BannerLayout removeSlide(String id, int index) {

        BannerLayout layout = requireBuilt(id);
        BannerSlide removed = layout.getSlides().remove(requireIndex(layout, index));

        dropFileIfUnused(layout, removed.getImage());

        layout.setLastModifiedOn(LocalDateTime.now());
        storage.write(layout, skeletonService.require(layout.getSkeleton()));
        return layoutRepository.save(layout);
    }

    /**
     * Swaps the picture on a frame, keeping its link, caption and position.
     *
     * <p>Its own action rather than a field on the form, for the reason every upload in this console
     * is: the file is durable the moment it is accepted. It is also the difference between
     * <em>replacing a creative</em> and rebuilding a booking -- an advertiser sending a new image for
     * the same campaign should not cost the operator the URL and the ordering that go with it, which
     * remove-then-add would.
     *
     * <p>The new file is stored under a fresh name rather than overwriting the old one. Browsers and
     * the proxy cache these by path, so writing new bytes to {@code acme-1.webp} would leave
     * readers on the previous creative for as long as their cache said so -- and there would be
     * nothing on any screen to explain it.
     */
    public BannerLayout replaceSlideImage(String id, int index, MultipartFile file) throws MediaException {

        BannerLayout layout = requireBuilt(id);
        BannerSlide slide = layout.getSlides().get(requireIndex(layout, index));

        String previous = slide.getImage();
        slide.setImage(storage.storeSlideImage(layout.getId(), file));
        layout.setLastModifiedOn(LocalDateTime.now());

        storage.write(layout, skeletonService.require(layout.getSkeleton()));
        BannerLayout saved = layoutRepository.save(layout);

        // Only after the document points at the new file, exactly as MgmtBannerService.uploadCreative
        // orders it: a replacement that fails halfway still leaves a frame with a picture.
        dropFileIfUnused(saved, previous);
        return saved;
    }

    /**
     * Removes a file once no frame references it.
     *
     * <p>Two frames may legitimately share one picture -- the same creative linking to two campaigns
     * -- so this is the reference-counted rule {@code ImageService.deleteImage} applies across
     * articles, at the scale of one folder.
     */
    private void dropFileIfUnused(BannerLayout layout, String image) {
        if (image == null) {
            return;
        }
        boolean stillUsed = layout.getSlides().stream().anyMatch(slide -> image.equals(slide.getImage()));
        if (!stillUsed) {
            storage.deleteSlideImage(layout.getId(), image);
        }
    }

    /**
     * Moves a frame one place.
     *
     * <p>Order is the whole mechanism here, as it is for an article's images: the first slide is the
     * one a reader sees before any interval fires, so it is the frame that runs on a page nobody
     * stays on. There is no "first slide" field for the same reason there is no cover image field.
     */
    public BannerLayout moveSlide(String id, int index, int delta) {

        BannerLayout layout = requireBuilt(id);
        int from = requireIndex(layout, index);
        int to = from + delta;
        if (to < 0 || to >= layout.getSlides().size()) {
            return layout;
        }

        List<BannerSlide> slides = layout.getSlides();
        BannerSlide moving = slides.remove(from);
        slides.add(to, moving);
        layout.setLastModifiedOn(LocalDateTime.now());

        storage.write(layout, skeletonService.require(layout.getSkeleton()));
        return layoutRepository.save(layout);
    }

    // ------------------------------------------------------------------------------ the files

    /** Every file in the folder, with what may be done to each. */
    public List<BannerLayoutStorage.LayoutFile> listFiles(String id) {
        require(id);
        return storage.listFiles(id);
    }

    /** One file's text, for the editor. Null when it is absent or not text. */
    public String fileText(String id, String path) {
        require(id);
        return storage.readText(id, path);
    }

    /**
     * Saves an edited file.
     *
     * <p>Available on <em>any</em> layout, built or imported, and that is deliberate: it is the only
     * way to change the destination of an advertiser's animation export, where the address is a
     * {@code clickTag} string inside the export's own script and nothing about it is structured
     * enough to bind a form field to.
     *
     * <p>On a {@link LayoutOrigin#BUILT} layout the three generated files are refused, because the
     * next save would overwrite the edit and the operator would have no way of knowing which of the
     * two they were looking at. Everything else in a built folder -- an extra stylesheet, a tracking
     * snippet -- is fair game.
     */
    public void saveFile(String id, String path, String content) {
        BannerLayout layout = require(id);
        refuseIfGenerated(layout, path);
        storage.writeTextFile(id, path, content);
    }

    /** Swaps one file for an uploaded one under the same name. */
    public void replaceFile(String id, String path, MultipartFile file) throws MediaException {
        BannerLayout layout = require(id);
        refuseIfGenerated(layout, path);
        storage.replaceFile(id, path, file);
    }

    /**
     * The files a built layout regenerates, which must not be hand-edited.
     *
     * <p>Editing one is not harmful so much as futile and confusing: the next «Запази» rewrites it
     * from the document, and nothing on screen would say the edit had gone. The builder is where
     * these three change.
     */
    private void refuseIfGenerated(BannerLayout layout, String path) {
        if (!layout.isBuilt()) {
            return;
        }
        String normalized = path == null ? "" : path.replace('\\', '/');
        if (normalized.equals(layout.getEntryFile())
                || normalized.equals("css/banner.css")
                || normalized.equals("js/banner.js")) {
            throw new MgmtOperationException("banners.error.generatedFile", normalized);
        }
    }

    /**
     * Removes the layout and its folder.
     *
     * <p>Refused while any booking runs it, <b>naming</b> the bookings rather than counting them --
     * the same choice {@code /mgmt/images/{id}} makes about the articles behind an image. A count
     * tells an operator they cannot proceed; the names tell them what to do about it.
     *
     * <p>The document goes first, then the folder. That is the ordering
     * {@code ArticleService.deleteArticle} settled on and for the same reason: clean the files first
     * and a failure halfway leaves a row pointing at a folder that is gone, which is the state this
     * screen exists to make visible.
     */
    public void delete(String id) {

        BannerLayout layout = require(id);

        List<Banner> using = bookingsUsing(id);
        if (!using.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Banner banner : using) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append("№ ").append(banner.getId()).append(" ").append(banner.getName());
            }
            throw new MgmtOperationException("banners.error.layoutInUse", names.toString());
        }

        log.info("Deleting banner layout {} ({})", layout.getId(), layout.getTitle());
        layoutRepository.deleteById(layout.getId());
        storage.deleteFolder(layout.getId());
    }

    /** How many layouts are built on {@code skeletonId}; read by the skeleton delete guard. */
    public long countUsingSkeleton(String skeletonId) {
        return layoutRepository.findAll().stream()
                .filter(layout -> skeletonId != null && skeletonId.equals(layout.getSkeleton()))
                .count();
    }

    // ------------------------------------------------------------------- the booking hand-off

    /**
     * The frame height a booking on this layout should run at.
     *
     * <p>Never {@link BannerSize#AUTO}. {@code .partner-card__frame--auto} is {@code height: auto} and the
     * iframe inside it is {@code height: 100%}, so an HTML5 booking on AUTO collapses to nothing --
     * a blank column with a 200 response. A layout whose dimensions match none of the three known
     * frames returns empty and the operator picks; guessing would be worse than asking.
     */
    public Optional<BannerSize> suggestedSize(BannerLayout layout) {
        Integer w = layout.getWidth();
        Integer h = layout.getHeight();
        if (w == null || h == null) {
            // Fluid, which in practice is the top leaderboard: the frame's own height governs.
            return Optional.of(BannerSize.TOP);
        }
        if (w == 728 && h == 90) {
            return Optional.of(BannerSize.TOP);
        }
        if (w == 300 && h == 250) {
            return Optional.of(BannerSize.SQUARE);
        }
        if (w == 300 && h == 600) {
            return Optional.of(BannerSize.TALL);
        }
        return Optional.empty();
    }

    /** A leaderboard goes on top, everything else beside the article. */
    public BannerPlacement suggestedPlacement(BannerLayout layout) {
        return suggestedSize(layout).filter(size -> size == BannerSize.TOP).isPresent()
                ? BannerPlacement.TOP
                : BannerPlacement.SIDEBAR;
    }

    /** The layout a booking's {@code source} belongs to, for the back-link on the banner screen. */
    public Optional<BannerLayout> layoutOf(String source) {
        if (source == null || !source.startsWith(BannerLayout.PUBLIC_PREFIX)) {
            return Optional.empty();
        }
        String rest = source.substring(BannerLayout.PUBLIC_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash <= 0 ? Optional.empty() : find(rest.substring(0, slash));
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Writes the editable slide fields onto the stored slides, by position.
     *
     * <p>The image filename is never written -- {@code BannerSlideForm} does not carry it. A count
     * mismatch means the layout changed under the operator (a slide uploaded or removed in another
     * tab), and is refused rather than reconciled: applying by position across a shift would put one
     * advertiser's link under another's creative, silently.
     */
    private void applySlides(BannerLayout layout, List<BannerSlideForm> forms) {

        List<BannerSlideForm> posted = forms == null ? List.of() : forms;
        if (posted.size() != layout.slideCount()) {
            throw new MgmtOperationException("banners.error.slidesChanged");
        }

        for (int i = 0; i < posted.size(); i++) {
            BannerSlideForm form = posted.get(i);
            BannerSlide slide = layout.getSlides().get(i);
            slide.setHref(blankToNull(form.getHref()));
            slide.setTitle(blankToNull(form.getTitle()));
            slide.setAlt(blankToNull(form.getAlt()));
        }
    }

    /** The rules that span fields, and so cannot be bean-validation annotations. */
    private void validate(BannerLayout layout, BannerLayoutForm form) {

        if ((form.getWidth() == null) != (form.getHeight() == null)) {
            throw new MgmtOperationException("banners.error.widthHeightTogether");
        }

        // Same check, same method, as MgmtBannerService.validate and BannerClickController: a
        // javascript: URL written into a folder the console generates is stored XSS on the site.
        for (BannerSlideForm slide : form.getSlides() == null ? List.<BannerSlideForm>of() : form.getSlides()) {
            if (slide.getHref() != null && !slide.getHref().isBlank()
                    && !HttpUrls.isHttpUrl(slide.getHref())) {
                throw new MgmtOperationException("banners.error.slideHref");
            }
        }

        // A skeleton that loads Google Tag Manager has a gtm.js?id= URL with nothing to put in it
        // otherwise, and the folder would report to a container called __GTM_ID__.
        BannerSkeleton skeleton = skeletonService.require(layout.getSkeleton());
        if (skeleton.getGtmId() != null && blankToNull(form.getGtmId()) == null) {
            throw new MgmtOperationException("banners.error.gtmRequired", skeleton.getTitle());
        }
    }

    /** See {@link #ID} for why this is narrow. */
    String normaliseId(String raw) {
        String id = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new MgmtOperationException("banners.error.layoutIdPattern");
        }
        requireNotReserved(id);
        return id;
    }

    private long countBookings(List<Banner> banners, BannerLayout layout) {
        String folder = layout.publicFolder();
        return banners.stream()
                .filter(banner -> banner.getSource() != null && banner.getSource().startsWith(folder))
                .count();
    }

    private int requireIndex(BannerLayout layout, int index) {
        if (index < 0 || index >= layout.slideCount()) {
            throw new MgmtNotFoundException("banners.error.noSlide", index + 1);
        }
        return index;
    }

    private BannerLayout requireBuilt(String id) {
        BannerLayout layout = require(id);
        if (!layout.isBuilt()) {
            throw new MgmtOperationException("banners.error.importedNotEditable");
        }
        return layout;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public BannerLayout require(String id) {
        return find(id).orElseThrow(
                () -> new MgmtNotFoundException("banners.error.noLayout", id));
    }
}
