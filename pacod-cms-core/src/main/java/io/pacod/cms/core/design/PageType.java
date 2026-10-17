package io.pacod.cms.core.design;

/**
 * The kinds of public page a website's layouts are designed for. The search-by-Google page, the
 * link-in-bio page and the error page keep their template set's fixed design.
 */
public enum PageType {

    /** {@code /}: no content of its own -- only sections. */
    HOME(false),
    /** {@code /article/{id}}. */
    ARTICLE(true),
    /** {@code /list/{id}}: one category's articles. */
    CATEGORY(true),
    /** {@code /latest}: the newest article in full. */
    LATEST(true),
    /** {@code /p/{slug}}: a standing page. */
    PAGE(true),
    /** {@code /search}. */
    SEARCH(true);

    private final boolean hasPrimary;

    PageType(boolean hasPrimary) {
        this.hasPrimary = hasPrimary;
    }

    /** Whether the page has content of its own, which its layout places as the {@code primary} section. */
    public boolean hasPrimary() {
        return hasPrimary;
    }
}
