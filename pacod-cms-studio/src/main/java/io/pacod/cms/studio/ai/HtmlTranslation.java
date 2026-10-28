package io.pacod.cms.studio.ai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whether a translation the model wrote may be stored: it must have exactly its source's
 * skeleton. Same elements in the same order, the same attributes with the same values, the same
 * script and style content, the same comments, and the same code: the text of every
 * {@code <pre>} and {@code <code>} ({@link #CODE}), whitespace included. Only the other text
 * between tags, and the values of the few attributes that are words ({@link #TRANSLATABLE}), may
 * differ.
 *
 * <p>Code is held to its source because a model asked to translate an article will also translate
 * what reads like words in a code sample -- a comment, a string, an identifier -- and a command
 * that no longer runs is worse than an untranslated one.
 *
 * <p>So a model can add nothing executable the source did not already have -- no
 * {@code <script>}, no {@code on*}, no changed {@code href} or {@code src} -- whatever a body's
 * text asked of it, and whatever the editor's allow-everything schema lets an author write: an
 * older body with tables, {@code <font>}, {@code <style>} or a YouTube frame translates with all
 * of it kept, where an allow-list sanitiser would strip formatting the source had. What is stored
 * is jsoup's serialisation of the output it checked, never the model's raw string.
 */
public final class HtmlTranslation {

    /** Attribute values that are words a reader sees or hears, and so are translated. */
    static final Set<String> TRANSLATABLE = Set.of("alt", "title", "aria-label", "placeholder");

    /** The elements whose text is code, kept exactly: a code sample, and code within a sentence. */
    static final Set<String> CODE = Set.of("pre", "code");

    private HtmlTranslation() {
    }

    /** A rich body's translation, serialised as checked, if its skeleton is the source's. */
    public static Optional<String> html(String source, String translation) {
        if (translation == null) {
            return Optional.empty();
        }
        Document output = parse(translation);
        if (!skeleton(parse(source == null ? "" : source)).equals(skeleton(output))) {
            return Optional.empty();
        }
        return Optional.of(output.body().html());
    }

    /**
     * A field that may be text or markup -- a title. Text in the source stays text: a translation
     * that brings an element is refused, and the text is stored as the source is, unescaped.
     */
    public static Optional<String> field(String source, String translation) {
        if (translation == null) {
            return Optional.empty();
        }
        Document from = parse(source == null ? "" : source);
        if (!from.body().children().isEmpty()) {
            return html(source, translation);
        }
        Document output = parse(translation);
        if (!output.body().children().isEmpty() || !skeleton(output).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(output.body().text());
    }

    private static Document parse(String html) {
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        return document;
    }

    /**
     * Everything but the translatable: elements, attributes, script and style data, comments, and
     * the whole text of each outermost {@code pre} or {@code code}.
     */
    static List<String> skeleton(Document document) {
        List<String> parts = new ArrayList<>();
        document.body().traverse(new NodeVisitor() {
            /** How many pre and code elements the visit is inside; their text is counted once, by the outermost. */
            private int inCode;

            @Override
            public void head(Node node, int depth) {
                if (depth == 0) {
                    return;
                }
                if (node instanceof Element element) {
                    TreeMap<String, String> attributes = new TreeMap<>();
                    for (Attribute attribute : element.attributes()) {
                        String name = attribute.getKey().toLowerCase(Locale.ROOT);
                        attributes.put(name, TRANSLATABLE.contains(name) ? "*" : attribute.getValue());
                    }
                    parts.add("<" + element.normalName() + attributes);
                    if (CODE.contains(element.normalName()) && inCode++ == 0) {
                        parts.add("code:" + element.wholeText());
                    }
                } else if (node instanceof DataNode data) {
                    parts.add("data:" + data.getWholeData());
                } else if (node instanceof Comment comment) {
                    parts.add("comment:" + comment.getData());
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (depth > 0 && node instanceof Element element) {
                    parts.add("</" + element.normalName());
                    if (CODE.contains(element.normalName())) {
                        inCode--;
                    }
                }
            }
        });
        return parts;
    }
}
