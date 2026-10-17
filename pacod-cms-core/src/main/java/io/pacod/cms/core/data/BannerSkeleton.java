package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A reusable look for a {@link BannerLayout}: the chrome of an {@code index.html} plus a stylesheet.
 *
 * <p>Two are built in -- {@link #SIDEBAR} and {@link #TOP}, seeded into every website -- and an
 * operator may add more. What a skeleton contributes is <em>look and chrome</em>: the document head,
 * any tag-manager block, the container element and {@code css/banner.css}. What it does
 * <em>not</em> contribute is rotation; see below.
 *
 * <h2>Stored as three pieces, not one file</h2>
 *
 * <p>{@link #htmlHead}, {@link #containerTag} and {@link #htmlTail} are one {@code index.html} split
 * at the {@code .banner-container-inline} element by {@code BannerSkeletonParser}, once, at import.
 * Generating a layout is then string concatenation with the slides in the middle, so nothing parses
 * HTML at save time and a malformed skeleton fails loudly when it is added rather than quietly on
 * every folder built from it afterwards.
 *
 * <h2>No filesystem, and no images</h2>
 *
 * <p>A skeleton is two short text fields; storing them in the document avoids a second storage
 * path for files the public site would then serve for no reason. It carries no images either: a
 * logo shared across an advertiser's creatives belongs to the layout, which is the thing that has
 * a folder.
 *
 * <h2>Rotation belongs to the console</h2>
 *
 * <p>The generated {@code js/banner.js} is always the console's own rotator, and a skeleton's own
 * script -- if the operator uploaded one -- is ignored. A hand-authored rotator tends to hardcode
 * its slide count (a {@code modulo}, a {@code switch}, a block of {@code getElementById} calls),
 * and a three-slide folder built on a two-slot script shows two slides forever with nothing
 * reporting it. An operator who genuinely needs different rotation imports a finished folder
 * instead, which is served verbatim.
 */
@Data
@Document(collection = "bannerSkeleton")
public class BannerSkeleton {

    /** Id of the seeded sidebar skeleton. */
    public static final String SIDEBAR = "sidebar";

    /** Id of the seeded top-leaderboard skeleton. */
    public static final String TOP = "top";

    @Id
    private String id;

    private String title;

    /**
     * Intrinsic size the layouts built from this skeleton default to, or null for fluid. Only a
     * default -- {@link BannerLayout} carries its own, because one skeleton serves several sizes.
     */
    private Integer width;

    private Integer height;

    /**
     * Whether this is one of the two seeded skeletons. Built-ins are editable but not deletable:
     * the seed only runs while the collection is empty, so deleting one would remove it for good
     * without anything saying so.
     */
    private boolean builtIn;

    /** Everything before the container element's start tag, including the whole document head. */
    private String htmlHead;

    /** The container element's start tag, minus its closing {@code >}. */
    private String containerTag;

    /**
     * The container's closing tag and everything after it.
     *
     * <p>Inclusive of the closing tag so that generating a layout never has to work out what the
     * container element was called -- the split is the only place that question is answered.
     */
    private String htmlTail;

    /** Written verbatim to {@code css/banner.css}; a layout's dimensions are appended after it. */
    private String css;

    /** The GTM container id a new layout on this skeleton starts with. */
    private String gtmId;
}
