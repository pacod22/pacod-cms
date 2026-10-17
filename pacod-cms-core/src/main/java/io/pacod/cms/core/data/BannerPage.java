package io.pacod.cms.core.data;

import java.util.Arrays;
import java.util.List;

/**
 * The public pages a banner can be booked on, one constant per view rendered by
 * {@code ArticleTemplateController}.
 *
 * <p>A banner with an empty page list runs everywhere; one that names pages runs only on those
 * -- {@code pages = [LIST]} is a booking for the category listings alone.
 */
public enum BannerPage {

    LANDING("Home page"),
    INDEX("Latest article"),
    ARTICLE("Article"),
    LIST("List"),
    /**
     * The standing pages -- {@code PageController}, not {@code ArticleTemplateController}.
     *
     * <p>Adding a constant is safe for existing bookings: a banner runs on a page either because
     * its {@code pages} list is empty, which still means everywhere, or because the list names the
     * page -- and no booking written before the constant existed can name it. So every booking
     * keeps running exactly where it did, and the new page becomes bookable on its own.
     *
     * <p>It is also what keeps the monitoring screen honest: routing these through
     * {@link #ARTICLE} would fold page views into the article count and no column would show it.
     */
    PAGE("Page"),

    /**
     * The two search screens -- {@code SearchController}.
     *
     * <p>Its own constant for the reason {@link #PAGE} is: folding search into {@link #LIST}
     * would put searches in the listing count on the monitoring screen with no column showing
     * it, and the two are not the same reader behaviour. Safe for every booking written before
     * it existed, by the same argument -- an empty {@code pages} list still means everywhere,
     * and no existing list can name a constant that did not exist.
     *
     * <p>One constant covers both {@code /search} and {@code /search-google}: they are two
     * spellings of one intent, and an advertiser buying "the search page" means both.
     */
    SEARCH("Search"),

    /**
     * {@code /social}, the link-in-bio page -- {@code SocialController}.
     *
     * <p>A constant so the monitoring screen's per-page table gets a row for it: without one
     * its renders would count towards the site's views and land in no page column. It is
     * <b>not bookable</b>. That page carries no ad columns and never calls
     * {@code BannerService.forPage}, so a booking naming it would run nowhere -- and, the other way
     * round, an empty {@code pages} list means "everywhere" and must not come to mean "the bio page
     * too". {@link #isBookable()} is what keeps it off the banner form.
     */
    SOCIAL("Social page", false);

    private final String label;
    private final boolean bookable;

    BannerPage(String label) {
        this(label, true);
    }

    BannerPage(String label, boolean bookable) {
        this.label = label;
        this.bookable = bookable;
    }

    public String getLabel() {
        return label;
    }

    /** Whether a booking may name this page. False only for pages rendered without ad columns. */
    public boolean isBookable() {
        return bookable;
    }

    /** The pages the banner form offers, in declaration order. */
    public static List<BannerPage> bookable() {
        return Arrays.stream(values()).filter(BannerPage::isBookable).toList();
    }
}
