package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * {@code /social} -- the link-in-bio page the website's social-network profiles point at. One
 * document, id {@link #ID}, edited at {@code /mgmt/social}.
 *
 * <h2>A singleton, not a collection of pages</h2>
 * A profile can carry one link, so the site needs one page behind it. The id is a constant for
 * the reason {@link Page}'s is its slug: nothing to allocate, no {@code getMaxId} read-then-write.
 * There is no bootstrap seed -- the page is useless until someone writes its bio -- so the
 * document does not exist until the console's first write, and {@code /social} answers 404 until
 * then and whenever {@link #published} is false.
 *
 * <h2>Translations follow {@link Page}'s rules</h2>
 * Per-language {@link TextItem} lists, an absent entry for a missing translation, resolution
 * through a {@link LanguageChain}, and resolved/raw spelled as two different names
 * ({@code name(List)} / {@code rawName(String)}) so SpEL cannot pick the wrong one. See
 * {@link TextItems}.
 *
 * <h2>No statistics here</h2>
 * Views and clicks are counted into the traffic buckets ({@link SiteMetric}), never onto this
 * document -- the console saves it whole. See {@link SocialLink}.
 */
@Data
@Document(collection = SocialPage.COLLECTION)
public class SocialPage {

    public static final String COLLECTION = "socialPage";
    public static final String ID = "social";

    /** Where the public site serves the page. */
    public static final String PUBLIC_PATH = "/social";

    /** Bounds of the latest-articles block, enforced on save and again on read. */
    public static final int MIN_LATEST = 1;
    public static final int MAX_LATEST = 10;

    private static final String ID_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String ID_CHARS = ID_LETTERS + "0123456789";
    private static final int ID_LENGTH = 8;

    @Id
    private String id = ID;

    private boolean published;

    /** The profile's display name, per language. */
    private List<TextItem> names = new ArrayList<>();

    /** One or two sentences under the name, per language. Plain text, not HTML. */
    private List<TextItem> bios = new ArrayList<>();

    /**
     * An {@code ImageMedia} id from the console's image library, or {@code null} for the
     * website's logo. An id, not a path: if the image is later deleted the page falls back to
     * the logo rather than rendering a broken {@code <img>}.
     */
    private Integer avatarImageId;

    private boolean showLatestArticles;

    private int latestArticlesCount = 5;

    /** The latest-articles block's heading, per language; the page has a default when absent. */
    private List<TextItem> latestHeadings = new ArrayList<>();

    /** In display order. Icons and buttons share the list; see {@link SocialLinkPlacement}. */
    private List<SocialLink> links = new ArrayList<>();

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    /* ------------------------------------------------------------------ resolution */

    public String name(List<String> chain) {
        return TextItems.resolve(names, chain);
    }

    public String nameLang(List<String> chain) {
        return TextItems.resolvedLang(names, chain);
    }

    public String bio(List<String> chain) {
        return TextItems.resolve(bios, chain);
    }

    public String bioLang(List<String> chain) {
        return TextItems.resolvedLang(bios, chain);
    }

    public String latestHeading(List<String> chain) {
        return TextItems.resolve(latestHeadings, chain);
    }

    public String latestHeadingLang(List<String> chain) {
        return TextItems.resolvedLang(latestHeadings, chain);
    }

    public String rawName(String lang) {
        return TextItems.find(names, lang);
    }

    public String rawBio(String lang) {
        return TextItems.find(bios, lang);
    }

    public String rawLatestHeading(String lang) {
        return TextItems.find(latestHeadings, lang);
    }

    /** {@link #latestArticlesCount} clamped to its bounds, whatever was written by hand. */
    public int latestCount() {
        return Math.clamp(latestArticlesCount, MIN_LATEST, MAX_LATEST);
    }

    /**
     * Every language any text on the page is written in, in first-seen order: the name, the bio,
     * the latest-articles heading and every link title. What the public page's language switcher
     * offers, intersected with the language collection for the native names.
     */
    public List<String> writtenIn() {
        List<String> codes = new ArrayList<>();
        TextItems.collectLanguages(names, codes);
        TextItems.collectLanguages(bios, codes);
        TextItems.collectLanguages(latestHeadings, codes);
        for (SocialLink link : getLinks()) {
            TextItems.collectLanguages(link.getTitles(), codes);
        }
        return codes;
    }

    /* --------------------------------------------------------------------- links */

    /** Never null, so callers can iterate a document written without the field. */
    public List<SocialLink> getLinks() {
        if (links == null) {
            links = new ArrayList<>();
        }
        return links;
    }

    public Optional<SocialLink> link(String linkId) {
        if (linkId == null) {
            return Optional.empty();
        }
        return getLinks().stream().filter(l -> linkId.equals(l.getId())).findFirst();
    }

    /** The links of one placement that the public page shows at {@code now}, in display order. */
    public List<SocialLink> liveLinks(SocialLinkPlacement placement, LocalDateTime now) {
        return getLinks().stream()
                .filter(l -> l.getPlacement() == placement && l.isLive(now))
                .toList();
    }

    /** The links of one placement regardless of state, in display order -- the console's tables. */
    public List<SocialLink> linksOf(SocialLinkPlacement placement) {
        return getLinks().stream().filter(l -> l.getPlacement() == placement).toList();
    }

    /**
     * A fresh link id: a letter followed by seven of {@code [a-z0-9]}, unique within this page.
     * See {@link SocialLink} for why the shape is load-bearing. Uniqueness is checked against the
     * links present; a deleted link's id coming back would need a 1-in-10<sup>12</sup> draw, which
     * is the price of not keeping a tombstone list.
     */
    public String newLinkId(RandomGenerator random) {
        while (true) {
            StringBuilder id = new StringBuilder(ID_LENGTH);
            id.append(ID_LETTERS.charAt(random.nextInt(ID_LETTERS.length())));
            for (int i = 1; i < ID_LENGTH; i++) {
                id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
            }
            String candidate = id.toString();
            if (link(candidate).isEmpty()) {
                return candidate;
            }
        }
    }

    /** Whether {@code value} has the shape {@link #newLinkId} produces -- checked before it reaches a query or a key. */
    public static boolean isLinkId(String value) {
        return value != null && value.matches("[a-z][a-z0-9]{" + (ID_LENGTH - 1) + "}");
    }

    /* --------------------------------------------------------------------- writing */

    public void putName(String lang, String text) {
        names = TextItems.put(names, lang, text);
    }

    public void putBio(String lang, String text) {
        bios = TextItems.put(bios, lang, text);
    }

    public void putLatestHeading(String lang, String text) {
        latestHeadings = TextItems.put(latestHeadings, lang, text);
    }
}
