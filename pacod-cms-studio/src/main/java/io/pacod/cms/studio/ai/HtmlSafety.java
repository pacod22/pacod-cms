package io.pacod.cms.studio.ai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

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

    private HtmlSafety() {
    }

    /**
     * {@code html} cleaned to the allow-list, images kept only where {@code source} had the same
     * {@code src}.
     */
    public static String clean(String source, String html) {
        Set<String> sources = new HashSet<>();
        for (Element image : Jsoup.parseBodyFragment(source == null ? "" : source, BASE).select("img[src]")) {
            sources.add(image.attr("src"));
        }
        Document dirty = Jsoup.parseBodyFragment(html == null ? "" : html, BASE);
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        for (Element image : clean.select("img")) {
            if (!sources.contains(image.attr("src"))) {
                image.remove();
            }
        }
        return clean.body().html();
    }

    /** Text only: what a description or a tag may be. */
    public static String text(String html) {
        return Jsoup.parseBodyFragment(html == null ? "" : html).body().text().strip();
    }
}
