package io.pacod.cms.core.data;

/**
 * The fixed set of icons a {@link SocialLink} can wear on {@code /social}.
 *
 * <p>A closed list rather than an upload, deliberately: the page is a sprite of inline SVG with
 * one {@code <symbol id="si-…">} per constant, so an icon costs no request and inherits
 * {@code currentColor} in both themes. {@link #symbol()} is the id {@code social.html} declares,
 * and {@code PublicTemplateRenderTest} checks that every constant has its symbol -- a constant
 * without one renders an empty, invisible icon and nothing else fails.
 *
 * <p>The name is stored on the link, so a constant must not be renamed.
 */
public enum SocialIcon {

    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    YOUTUBE("YouTube"),
    TIKTOK("TikTok"),
    X("X (Twitter)"),
    LINKEDIN("LinkedIn"),
    THREADS("Threads"),
    PINTEREST("Pinterest"),
    TELEGRAM("Telegram"),
    VIBER("Viber"),
    WHATSAPP("WhatsApp"),
    WEB("Website"),
    ARTICLE("Article"),
    PDF("PDF"),
    EMAIL("Email"),
    SHOP("Shop"),
    PODCAST("Podcast"),
    CALENDAR("Event"),
    GITHUB("GitHub");

    private final String label;

    SocialIcon(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** The sprite symbol id in {@code social.html}, e.g. {@code si-instagram}. */
    public String symbol() {
        return "si-" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
