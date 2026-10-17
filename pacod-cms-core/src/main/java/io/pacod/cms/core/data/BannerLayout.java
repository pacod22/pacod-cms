package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An HTML5 creative: one folder in the website's storage, and the configuration that produced it.
 *
 * <p>The document is the record of how the folder was made, so the console can rebuild it. A
 * folder edited by hand -- images swapped, the advertiser URL rewritten, {@code js/banner.js}
 * edited in several places -- leaves no record of what was done, and a booking that names a
 * folder which is not there shows nothing but an empty iframe.
 *
 * <p>The document lives in core for the reason {@link Banner}'s javadoc gives: both applications
 * declare {@code @EnableMongoRepositories({"io.pacod.cms.core.repository"})} and enumerate
 * {@code io.pacod.cms.core.data} in their component scan, so a document anywhere else is not
 * mapped. Only the console reads or writes it; the public site knows nothing about layouts and
 * never needs to -- a layout still reaches the site as a {@link Banner} whose {@code source}
 * names {@link #publicPath()}.
 *
 * <h2>The id is the folder name, and is never editable</h2>
 *
 * <p>Not a max-plus-one {@code Integer} like {@link Banner}, {@code Article} and {@code ImageMedia}.
 * Making the folder name the {@code @Id} turns uniqueness into a primary-key property, which holds
 * from the first write: a {@code @Indexed(unique = true)} exists only once the website has been
 * provisioned, and an index that failed to build enforces nothing at all.
 *
 * <p>It is never editable for the reason {@code ImageMedia.filename} is not bindable and a
 * {@code Language} code is not: renaming it would leave every {@link Banner#getSource()} naming a
 * path that no longer resolves, and the symptom -- an empty ad column -- appears nowhere near the
 * rename.
 *
 * <h2>{@link #entryFile} is a field because exported folders are not uniform</h2>
 *
 * <p>Most folders answer at {@code index.html}, but an advertiser's export may hold a single
 * {@code Name_300_250.html} and no index. An imported folder therefore records which file it
 * answers at rather than having one assumed for it.
 */
@Data
@Document(collection = "bannerLayout")
public class BannerLayout {

    /** How long a slide is shown before the next one, when nothing is configured. */
    public static final int DEFAULT_ROTATION_MS = 10000;

    /**
     * Every layout folder lives under this prefix, which the public site serves from the
     * website's folder.
     */
    public static final String PUBLIC_PREFIX = "/resources/banners/";

    /** The folder name. See the class javadoc: assigned once, never edited. */
    @Id
    private String id;

    /** Operator-facing label. Free text, unlike the id. */
    private String title;

    private LayoutOrigin origin;

    /**
     * The {@link BannerSkeleton} this layout is generated from. Null for {@link LayoutOrigin#IMPORTED},
     * which has no skeleton by definition -- its files arrived finished.
     */
    private String skeleton;

    /**
     * Intrinsic size in pixels, or null for a fluid layout.
     *
     * <p>Null is not an oversight: the top skeleton deliberately declares no size, because a
     * leaderboard fills the {@code .partner-card__frame--top} height the stylesheet gives it. The sidebar
     * skeleton pins 300x250 because a square creative has to reserve its own box. Both are honest
     * about what they are; a layout that stated a size it did not have would drive the booking
     * hand-off to the wrong {@link BannerSize}.
     */
    private Integer width;

    private Integer height;

    /** Milliseconds per slide. Read by the generated {@code js/banner.js} off the container. */
    private int rotationMs;

    /**
     * Google Tag Manager container id written into the generated {@code index.html}, or null for
     * none. A skeleton that loads a tag manager would otherwise put one container in every folder
     * built from it; a field per layout is what lets an advertiser without a GTM account have a
     * folder that does not report to somebody else's.
     */
    private String gtmId;

    private List<BannerSlide> slides;

    /** The file inside the folder the booking points at. See the class javadoc. */
    private String entryFile;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    public BannerLayout() {
        this.slides = new ArrayList<>();
        this.origin = LayoutOrigin.BUILT;
        this.rotationMs = DEFAULT_ROTATION_MS;
        this.entryFile = "index.html";
    }

    public BannerLayout(String id) {
        this();
        this.id = id;
        LocalDateTime now = LocalDateTime.now();
        this.createdOn = now;
        this.lastModifiedOn = now;
    }

    /**
     * The path a {@link Banner#getSource()} has to carry to run this layout.
     *
     * <p>On the document rather than in the service for the reason {@link Banner#runsOn} is: the
     * console screen that offers to create a booking and the check that refuses to delete a booked
     * layout must agree about what path a layout occupies, and one method is how they cannot drift.
     */
    public String publicPath() {
        return PUBLIC_PREFIX + id + "/" + entryFile;
    }

    /**
     * The prefix every file of this layout sits under.
     *
     * <p>This -- not {@link #publicPath()} -- is what a booking is matched against when deciding
     * whether a layout is in use: a hand-written booking may name a different file in the same
     * folder, and deleting the folder would break it just the same.
     */
    public String publicFolder() {
        return PUBLIC_PREFIX + id + "/";
    }

    /** Whether the console generated this folder and may therefore rewrite it. */
    public boolean isBuilt() {
        return origin == LayoutOrigin.BUILT;
    }

    public int slideCount() {
        return slides == null ? 0 : slides.size();
    }
}
