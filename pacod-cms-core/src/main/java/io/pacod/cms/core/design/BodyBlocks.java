package io.pacod.cms.core.design;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The styled blocks an article body may hold: a closed vocabulary of classes, each allowed on
 * one element, styled by the site's {@code site.css} (and so by every template set that links it,
 * the print views included) and by the studio's {@code body-blocks.css} inside the editor.
 *
 * <p>The studio's AI answers are cleaned against this table ({@code HtmlSafety.body}): any other
 * class goes. The names begin {@code body-} and were checked against EasyList, EasyPrivacy and both
 * Fanboy lists, which hide generic names such as {@code .body-ad} and site-specific
 * {@code .callout}s -- grep all four before renaming one.
 *
 * <p>A code sample's {@code language-…} class is not a block: it is {@link CodeBlocks}'.
 */
public enum BodyBlocks {

    LEAD("body-lead", "p"),
    CALLOUT("body-callout", "div"),
    PULLQUOTE("body-pullquote", "blockquote"),
    FACTS("body-facts", "div"),
    COLUMNS("body-columns", "div"),
    FIGURE("body-figure", "figure"),
    FIGURE_LEFT("body-figure-left", "figure"),
    FIGURE_RIGHT("body-figure-right", "figure");

    private final String cssClass;
    private final String element;

    BodyBlocks(String cssClass, String element) {
        this.cssClass = cssClass;
        this.element = element;
    }

    public String cssClass() {
        return cssClass;
    }

    /** The one element the class may sit on. */
    public String element() {
        return element;
    }

    /** The elements that may carry a block's class. */
    public static final Set<String> ELEMENTS = Set.of("p", "div", "blockquote", "figure");

    public static Optional<BodyBlocks> byClass(String cssClass) {
        return Arrays.stream(values()).filter(b -> b.cssClass.equals(cssClass)).findFirst();
    }

    /** Whether {@code cssClass} is a block's class allowed on {@code element}. */
    public static boolean allowed(String element, String cssClass) {
        return byClass(cssClass).filter(b -> b.element.equals(element)).isPresent();
    }

    /** How a model is told about the vocabulary: one line per block. */
    public static List<String> describe() {
        return List.of(
                "<p class=\"body-lead\"> -- the opening paragraph that sums up the article, at most one, first",
                "<div class=\"body-callout\"> -- a boxed note or aside holding paragraphs",
                "<blockquote class=\"body-pullquote\"> -- a short striking sentence repeated from the text, large",
                "<div class=\"body-facts\"> -- a key-facts box: an <h3> heading, then a <ul>",
                "<div class=\"body-columns\"> -- two columns of paragraphs or lists, for short parallel content",
                "<figure class=\"body-figure\"> (optionally also body-figure-left or body-figure-right) -- an existing "
                        + "image with a <figcaption>");
    }
}
