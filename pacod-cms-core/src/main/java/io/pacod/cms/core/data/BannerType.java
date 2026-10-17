package io.pacod.cms.core.data;

/**
 * The two shapes an advertising creative comes in.
 *
 * <p>{@link #HTML5} is a self-contained mini-site in the website's storage -- an
 * {@code index.html} with its own css, js and images -- shown in an {@code <iframe>}. The booking
 * stores only its path; the folder is a {@link BannerLayout} the console builds or imports.
 *
 * <p>{@link #IMAGE} is a single still or animated file wrapped in a link, optionally with a line
 * of text underneath. Those are uploaded through the console.
 */
public enum BannerType {

    HTML5("HTML5 (iframe)"),
    IMAGE("Image");

    private final String label;

    BannerType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
