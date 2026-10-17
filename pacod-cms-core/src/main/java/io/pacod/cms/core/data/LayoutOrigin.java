package io.pacod.cms.core.data;

/**
 * Where a {@link BannerLayout}'s folder came from, and therefore what the console may do to it.
 *
 * <p>The distinction is not cosmetic. A {@link #BUILT} layout's folder is <em>derived</em>: the
 * console rewrites {@code index.html}, {@code css/banner.css} and {@code js/banner.js} from the
 * document and its skeleton on every save, so the document is the authority and the files are
 * disposable. An {@link #IMPORTED} layout is the other way round -- the files are the authority and
 * the document only records that they exist -- so the builder is not offered for it. Offering it
 * anyway would mean the first save silently overwrote a folder the console never authored.
 */
public enum LayoutOrigin {

    BUILT("Built"),
    IMPORTED("Uploaded");

    private final String label;

    LayoutOrigin(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
