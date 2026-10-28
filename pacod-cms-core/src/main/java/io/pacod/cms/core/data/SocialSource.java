package io.pacod.cms.core.data;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where a visit to {@code /social} came from -- the classification its statistics are kept by.
 *
 * <p>The enum <b>name</b> is what is stored, as a key of the {@code socialViewsBySource} and
 * {@code socialClicks} maps in the traffic buckets, so a constant must never be renamed: the
 * buckets already written under the old name would stop being counted under any row. Adding one is
 * safe -- a bucket written before it existed simply does not mention it.
 *
 * <p>Only the classified key is ever stored. Never the {@code Referer}, never the User-Agent: the
 * rule {@code SiteVisitor}'s javadoc states for the reader statistics holds here too.
 *
 * <p>{@link #key()} is the lowercase spelling carried on the page's own links
 * ({@code /social/go/{id}?src=instagram}) and printed in the console's ready-to-paste addresses.
 * {@link #of(String)} reads it back, together with the short aliases an operator is likely to type
 * into a bio by hand.
 */
public enum SocialSource {

    INSTAGRAM("Instagram", "ig", "insta"),
    FACEBOOK("Facebook", "fb"),
    MESSENGER("Messenger", "fbm"),
    THREADS("Threads", "th"),
    TIKTOK("TikTok", "tt"),
    YOUTUBE("YouTube", "yt"),
    X("X (Twitter)", "twitter", "tw"),
    LINKEDIN("LinkedIn", "li"),
    PINTEREST("Pinterest", "pin"),
    SNAPCHAT("Snapchat", "snap", "sc"),
    TELEGRAM("Telegram", "tg"),
    VIBER("Viber", "vb"),
    WHATSAPP("WhatsApp", "wa"),
    GITHUB("GitHub", "gh"),
    EMAIL("Email", "e-mail", "mail", "newsletter"),
    /** Only ever explicit: a QR code printed on paper or on a stand, tagged {@code ?src=qr}. */
    QR("QR code"),
    /** A link from this site's own pages, e.g. the footer. */
    SITE("The site"),
    /** A signal was present -- a tag or a referrer -- but it named nothing this list knows. */
    OTHER("Other"),
    /** No signal at all: typed, bookmarked, or opened from an app that sends no referrer. */
    DIRECT("Direct");

    private final String label;
    private final List<String> aliases;

    SocialSource(String label, String... aliases) {
        this.label = label;
        this.aliases = List.of(aliases);
    }

    public String getLabel() {
        return label;
    }

    /** The lowercase name -- what the page's own links carry in {@code ?src=}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the operator can usefully post a tagged address for it -- the console's card. */
    public boolean isTaggable() {
        return this != SITE && this != OTHER && this != DIRECT;
    }

    /**
     * The source an explicit tag names, by key, enum name or alias, case-insensitively.
     *
     * @return {@code null} for a blank tag -- there was no tag -- and {@link #OTHER} for one that
     *         names nothing known. The distinction matters to the classifier: a blank tag lets it
     *         look at the next signal, an unknown one is still a deliberate statement that the
     *         visit came from somewhere, and is not overridden by a guess.
     */
    public static SocialSource of(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String normalised = tag.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.key().equals(normalised) || s.aliases.contains(normalised))
                .findFirst()
                .orElse(OTHER);
    }

    /** A stored map key read back; anything unrecognised -- a renamed constant -- is {@link #OTHER}. */
    public static SocialSource fromStored(String name) {
        if (name == null) {
            return OTHER;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
