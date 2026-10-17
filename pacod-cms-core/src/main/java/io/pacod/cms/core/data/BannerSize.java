package io.pacod.cms.core.data;

/**
 * The height of the frame a banner is rendered into.
 *
 * <p>Each constant maps 1:1 onto a modifier that already exists in
 * {@code static/site-assets/site.css} -- {@code .partner-card__frame--top} and friends. Storing the
 * suffix rather than a pixel height keeps the heights in the stylesheet, where the responsive
 * rules that adjust them also live, and stops an operator from entering a number the layout was
 * never designed around.
 *
 * <p>{@link #AUTO} is the one an image creative normally wants: the frame takes the intrinsic
 * height of the file. The fixed heights exist for iframes, which have no intrinsic size.
 */
public enum BannerSize {

    TOP("top", "Leaderboard 728x90"),
    SQUARE("square", "Square 300x250"),
    TALL("tall", "Tall 300x600"),
    AUTO("auto", "Size of the file");

    private final String modifier;
    private final String label;

    BannerSize(String modifier, String label) {
        this.modifier = modifier;
        this.label = label;
    }

    /** The {@code .partner-card__frame--<modifier>} suffix this size renders as. */
    public String getModifier() {
        return modifier;
    }

    public String getLabel() {
        return label;
    }
}
