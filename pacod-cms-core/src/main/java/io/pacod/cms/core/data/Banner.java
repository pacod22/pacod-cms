package io.pacod.cms.core.data;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One advertising booking.
 *
 * <p>A booking is data, never markup: what runs, where, from when and until when are fields
 * here, which {@code fragments/ad-sidebar.html} renders. Selling, replacing or expiring a booking
 * is an edit in the console, not a template change and a redeploy.
 *
 * <p>The document lives in core because both applications read it, and both declare
 * {@code @EnableMongoRepositories({"io.pacod.cms.core.repository"})}, so a repository anywhere else
 * is not scanned. The reader, the counters and the studio's services live in their applications.
 *
 * <h2>Fields that are not what they look like</h2>
 * <ul>
 *   <li>{@link #source} is a <em>public path</em> for both types, not a filename. An
 *       {@link BannerType#HTML5} banner points at its folder's {@code index.html}; an uploaded
 *       {@link BannerType#IMAGE} points at {@code /resources/banners/{id}-{file}}. Storing a bare
 *       filename would tie every creative to one folder.</li>
 *   <li>{@link #pages} empty or null means <em>every</em> page. That is the useful default rather
 *       than a degenerate case: a booking names pages only to narrow where it runs.</li>
 *   <li>{@link #impressions} and {@link #clicks} are written by an atomic {@code $inc} outside
 *       any {@code save()} on this document -- see {@code BannerImpressionCounter}. They are
 *       deliberately absent from {@code BannerForm}, because a console save writes the whole
 *       document and would otherwise restore whatever was read at the start of the request.</li>
 * </ul>
 */
@Slf4j
@Data
@Document(collection = "banner")
public class Banner {

    @Id
    private Integer id;

    /** Operator-facing label. Never rendered on the public site. */
    private String name;

    private BannerType type;

    /**
     * The public path of the creative: the iframe URL for {@link BannerType#HTML5}, the image URL
     * for {@link BannerType#IMAGE}.
     */
    private String source;

    /** Where an {@link BannerType#IMAGE} banner links to. Unused for HTML5 -- see the class javadoc. */
    private String targetUrl;

    /** {@code alt} text for an image creative. */
    private String altText;

    /** Optional line of text rendered under the creative, inside the ad box. */
    private String caption;

    /**
     * Deliberately not {@code @Indexed}. Both readers -- {@code BannerReader} for the site and
     * {@code MgmtBannerService} for the console -- load the whole collection and filter in the
     * JVM, so nothing queries on this field. An index nothing uses would still be attempted by
     * {@code SiteIndexes} for every website, and a failure there is logged at ERROR: it would
     * become a false alarm for a query that does not exist.
     */
    private BannerPlacement placement;

    private BannerSize size;

    /** Ascending. Ties fall back to the id, so the order is always total. */
    private Integer sortOrder;

    /** {@link BannerPage} names. Empty or null runs the banner on every page. */
    private List<String> pages;

    /**
     * The sections ({@code Category} ids) it is booked for: an article in one of them, or one of
     * their lists. Empty or null books it regardless of section. Where a booking for the page's
     * section runs, the general ones of that placement step aside -- see {@code BannerService}.
     */
    private List<Integer> categories;

    private boolean active;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime activeFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime activeTo;

    private long impressions;

    private long clicks;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    public Banner() {
        this.pages = new ArrayList<>();
        this.categories = new ArrayList<>();
        this.active = true;
        this.type = BannerType.IMAGE;
        this.placement = BannerPlacement.SIDEBAR;
        this.size = BannerSize.AUTO;
        this.sortOrder = 0;
    }

    public Banner(Integer id) {
        this();
        this.id = id;
        LocalDateTime now = LocalDateTime.now();
        this.createdOn = now;
        this.lastModifiedOn = now;
    }

    /**
     * Whether this banner should render on {@code page} at {@code now}.
     *
     * <p>Lives on the document rather than in the service so the console and the public site
     * cannot disagree about what "running" means: the list screen shows the same verdict the
     * reader applies.
     */
    public boolean runsOn(BannerPage page, LocalDateTime now) {
        return active && withinWindow(now) && coversPage(page);
    }

    public boolean withinWindow(LocalDateTime now) {
        if (activeFrom != null && now.isBefore(activeFrom)) {
            return false;
        }
        return activeTo == null || !now.isAfter(activeTo);
    }

    /** An empty list means every page, so the absence of a filter is not the absence of a booking. */
    public boolean coversPage(BannerPage page) {
        if (pages == null || pages.isEmpty()) {
            return true;
        }
        return page != null && pages.contains(page.name());
    }

    /** Whether it is booked for some sections only. */
    public boolean isTargeted() {
        return categories != null && !categories.isEmpty();
    }

    /**
     * Whether it runs on a page of these sections: always when it names none; when it does, only
     * where the page has one of them -- never on a page with no section (the home page, search, a
     * standing page, a tag's list).
     */
    public boolean coversCategories(java.util.Collection<Integer> pageCategories) {
        if (!isTargeted()) {
            return true;
        }
        return pageCategories != null && pageCategories.stream().anyMatch(categories::contains);
    }

    /**
     * Whether this booking's clicks can be counted at all.
     *
     * <p>An image banner always can: its link goes through {@code /b/{id}}. An {@code HTML5} one
     * can when its {@link #source} is <b>same-origin</b> -- a root-relative path, which every
     * booking under {@code /resources/banners/} is. Clicks inside an iframe never reach the parent
     * document as events, but a same-origin frame with no {@code sandbox} lets the parent's
     * {@code site.js} listen inside the frame's own document and send a beacon. An off-origin
     * source ({@code https://…}, or protocol-relative {@code //…}) is the one case the parent
     * cannot see into, and reports no clicks.
     */
    public boolean isClickTracked() {
        if (type == BannerType.IMAGE) {
            return true;
        }
        return source != null && source.startsWith("/") && !source.startsWith("//");
    }
}
