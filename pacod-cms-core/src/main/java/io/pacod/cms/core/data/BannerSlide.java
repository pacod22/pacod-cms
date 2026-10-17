package io.pacod.cms.core.data;

import lombok.Data;

/**
 * One rotating frame of a {@link BannerLayout}: an image, and where clicking it goes.
 *
 * <p>Embedded in the layout rather than a document of its own, because a slide has no life outside
 * the folder that holds its file -- unlike {@code ImageMedia}, which is shared between articles and
 * therefore reference-counted.
 *
 * <p>{@link #image} is a <em>bare filename</em> inside the layout's folder, not a public path. The
 * generated {@code index.html} sits in that folder and references it relatively ({@code <img
 * src="acme-1.webp">}), exactly as a hand-authored export does, so the whole folder can be
 * renamed or copied without rewriting its markup. That is also why the public path is built by
 * {@link BannerLayout#publicPath()} and never stored.
 */
@Data
public class BannerSlide {

    /** Bare filename inside the layout's folder. Never a path -- see the class javadoc. */
    private String image;

    /**
     * Where the slide links to. Validated with {@code HttpUrls.isHttpUrl} before it is written into
     * the folder, for the reason {@code BannerClickController} validates a booking's target twice:
     * a {@code javascript:} URL in markup the console generates is stored XSS on the public site.
     */
    private String href;

    /** The link's {@code title} attribute -- the tooltip an advertiser asks for by name. */
    private String title;

    /** The image's {@code alt} attribute. */
    private String alt;

    public BannerSlide() {
    }

    public BannerSlide(String image) {
        this.image = image;
    }
}
