package io.pacod.cms.core.data;

/** What a {@link MenuItem} links to. */
public enum MenuLinkKind {

    /** No link: a group heading. */
    NONE,

    /** The front page. */
    HOME,

    /** The newest articles. */
    LATEST,

    /** A category listing; the target is the category id. */
    CATEGORY,

    /** A standing page; the target is its slug. */
    PAGE,

    /** The site search. */
    SEARCH,

    /** The link-in-bio page. */
    SOCIAL,

    /** Any address: site-relative, or http(s) to elsewhere. */
    URL
}
