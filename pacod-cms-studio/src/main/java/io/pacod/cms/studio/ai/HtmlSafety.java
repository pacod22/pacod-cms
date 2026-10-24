package io.pacod.cms.studio.ai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import io.pacod.cms.core.design.BodyBlocks;

import java.util.HashSet;
import java.util.Set;

/**
 * What a writing-assist answer may contain once cleaned: text structure and emphasis, lists,
 * quotes, simple tables, links -- {@code http}, {@code https}, {@code mailto} or relative -- and
 * only those images the operator's own selection already had. Never a {@code style}, an
 * {@code on*}, a {@code class}, a script, a frame or a form.
 *
 * <p>An explicit allow-list here, unlike translation's skeleton rule ({@link HtmlTranslation}):
 * shortening or expanding a passage legitimately changes its markup, so the answer cannot be held
 * to its source's; it can be held to a small vocabulary instead. The operator sees the cleaned
 * result before it is inserted.
 *
 * <p>An article body ({@link #body}) may also hold the styled blocks of {@link BodyBlocks}: a
 * {@code class} survives only where it names a block on that block's own element, and a
 * {@code div} left with none is unwrapped.
 */
public final class HtmlSafety {

    private static final String BASE = "https://pacod.invalid/";

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "b", "strong", "i", "em", "u", "s", "sub", "sup", "small", "mark",
                    "ul", "ol", "li", "blockquote", "h2", "h3", "h4", "code", "pre", "hr",
                    "table", "thead", "tbody", "tr", "th", "td", "caption", "a", "img")
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true);

    private static final Safelist BODY = new Safelist(SAFELIST)
            .addTags("div", "figure", "figcaption")
            .addAttributes("p", "class")
            .addAttributes("div", "class")
            .addAttributes("blockquote", "class")
            .addAttributes("figure", "class");

    private HtmlSafety() {
    }

    /**
     * {@code html} cleaned to the allow-list, images kept only where {@code source} had the same
     * {@code src}.
     */
    public static String clean(String source, String html) {
        return cleaned(source, html, SAFELIST).body().html();
    }

    /**
     * {@code html} cleaned for an article body: {@link #clean}'s allow-list plus the blocks of
     * {@link BodyBlocks}, every other class removed and a {@code div} without one unwrapped.
     */
    public static String body(String source, String html) {
        Document clean = cleaned(source, html, BODY);
        for (Element element : clean.body().select("[class]")) {
            Set<String> kept = new java.util.LinkedHashSet<>();
            for (String name : element.classNames()) {
                if (BodyBlocks.allowed(element.normalName(), name)) {
                    kept.add(name);
                }
            }
            if (kept.isEmpty()) {
                element.removeAttr("class");
            } else {
                element.classNames(kept);
            }
        }
        for (Element div : clean.body().select("div:not([class])")) {
            div.unwrap();
        }
        return clean.body().html();
    }

    private static Document cleaned(String source, String html, Safelist safelist) {
        Set<String> sources = new HashSet<>();
        for (Element image : Jsoup.parseBodyFragment(source == null ? "" : source, BASE).select("img[src]")) {
            sources.add(image.attr("src"));
        }
        Document dirty = Jsoup.parseBodyFragment(html == null ? "" : html, BASE);
        Document clean = new Cleaner(safelist).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        for (Element image : clean.select("img")) {
            if (!sources.contains(image.attr("src"))) {
                image.remove();
            }
        }
        return clean;
    }

    /**
     * Whether {@code html} holds something a body's cleaning would remove without a trace in its
     * text -- a frame, a video, an embed, a script, a style block, a form. Laying such a body out
     * would lose it unnoticed: the wording check reads only the text.
     */
    public static boolean holdsEmbeds(String html) {
        return !Jsoup.parseBodyFragment(html == null ? "" : html)
                .select("iframe, video, audio, embed, object, script, style, form, svg, math, canvas").isEmpty();
    }

    /** Text only: what a description or a tag may be. */
    public static String text(String html) {
        return Jsoup.parseBodyFragment(html == null ? "" : html).body().text().strip();
    }
}
