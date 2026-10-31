package io.pacod.cms.core.services.sitefiles;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stored body as Markdown, for {@code llms-full.txt}: what a language model reads best, and
 * what the website's markup means without its markup.
 *
 * <ul>
 *   <li>Headings, paragraphs, lists (nested), block quotes, emphasis, inline code, rules and
 *       simple tables keep their meaning.</li>
 *   <li>A code sample -- {@code <pre class="language-x">}, or Markdown's
 *       {@code <pre><code class="language-x">} -- becomes a fenced block naming its language, its
 *       text verbatim.</li>
 *   <li>Links keep their address, a site-relative one made absolute on {@code origin}; one that is
 *       not http, https or mailto keeps only its text. A picture is its alt text.</li>
 *   <li>Scripts, styles, frames, embeds, forms and comments are dropped, and every other element
 *       (legacy {@code <font>}, Word's {@code <o:p>}, a {@code <span>}) is unwrapped.</li>
 * </ul>
 */
public final class HtmlMarkdown {

    private static final Set<String> DROPPED = Set.of("script", "style", "iframe", "object", "embed", "form",
            "noscript", "template", "svg", "video", "audio", "canvas", "button", "input", "select", "textarea",
            "head", "title", "meta", "link");

    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "header", "footer", "main",
            "aside", "nav", "figure", "figcaption", "blockquote", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5",
            "h6", "pre", "table", "hr", "dl", "dt", "dd", "center", "address", "details", "summary");

    private static final Pattern LANGUAGE = Pattern.compile("(?:^|\\s)(?:language|lang)-([A-Za-z0-9+#-]{1,24})(?:\\s|$)");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0]+");

    private HtmlMarkdown() {
    }

    /** {@code html} as Markdown; {@code ""} for a blank body. */
    public static String convert(String html, String origin) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Element body = Jsoup.parseBodyFragment(html).body();
        List<String> blocks = new ArrayList<>();
        new HtmlMarkdown.Walker(origin == null ? "" : origin).blocks(body, blocks);
        return String.join("\n\n", blocks).strip();
    }

    private record Walker(String origin) {

        /** The children of {@code parent} as Markdown blocks, consecutive inline content as one paragraph. */
        void blocks(Element parent, List<String> out) {
            StringBuilder run = new StringBuilder();
            for (Node child : parent.childNodes()) {
                if (child instanceof Element element && isBlock(element)) {
                    flush(run, out);
                    block(element, out);
                } else if (!(child instanceof Element element && DROPPED.contains(element.normalName()))) {
                    inline(child, run);
                }
            }
            flush(run, out);
        }

        private void flush(StringBuilder run, List<String> out) {
            String text = tidy(run.toString());
            if (!text.isEmpty()) {
                out.add(text);
            }
            run.setLength(0);
        }

        private void block(Element element, List<String> out) {
            String name = element.normalName();
            switch (name) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    String text = inlineOf(element);
                    if (!text.isEmpty()) {
                        out.add("#".repeat(name.charAt(1) - '0') + " " + text.replace('\n', ' '));
                    }
                }
                case "pre" -> out.add(fence(element));
                case "hr" -> out.add("---");
                case "blockquote" -> {
                    String inner = blocksOf(element);
                    if (!inner.isEmpty()) {
                        out.add(prefix(inner, "> ", "> "));
                    }
                }
                case "ul", "ol" -> {
                    String list = list(element, "ol".equals(name));
                    if (!list.isEmpty()) {
                        out.add(list);
                    }
                }
                case "table" -> {
                    String table = table(element);
                    if (!table.isEmpty()) {
                        out.add(table);
                    }
                }
                default -> blocks(element, out);
            }
        }

        private String blocksOf(Element element) {
            List<String> inner = new ArrayList<>();
            blocks(element, inner);
            return String.join("\n\n", inner);
        }

        private String list(Element list, boolean ordered) {
            List<String> items = new ArrayList<>();
            int n = 1;
            for (Element child : list.children()) {
                if (!"li".equals(child.normalName())) {
                    continue;
                }
                String content = blocksOf(child);
                if (content.isEmpty()) {
                    continue;
                }
                String marker = ordered ? (n++) + ". " : "- ";
                items.add(prefix(content, marker, " ".repeat(marker.length())));
            }
            return String.join("\n", items);
        }

        private String table(Element table) {
            List<List<String>> rows = new ArrayList<>();
            for (Element row : table.select("tr")) {
                if (row.closest("table") != table) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (Element cell : row.children()) {
                    if ("td".equals(cell.normalName()) || "th".equals(cell.normalName())) {
                        cells.add(inlineOf(cell).replace('\n', ' ').replace("|", "\\|"));
                    }
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }
            if (rows.isEmpty()) {
                return "";
            }
            int width = rows.stream().mapToInt(List::size).max().orElse(1);
            StringBuilder out = new StringBuilder();
            for (int r = 0; r < rows.size(); r++) {
                List<String> cells = new ArrayList<>(rows.get(r));
                while (cells.size() < width) {
                    cells.add("");
                }
                out.append("| ").append(String.join(" | ", cells)).append(" |\n");
                if (r == 0) {
                    out.append("|").append(" --- |".repeat(width)).append("\n");
                }
            }
            return out.toString().strip();
        }

        private String fence(Element pre) {
            String language = language(pre.className());
            Element code = pre.children().size() == 1 && "code".equals(pre.child(0).normalName()) ? pre.child(0) : null;
            if (language == null && code != null) {
                language = language(code.className());
            }
            String text = (code != null ? code : pre).wholeText();
            text = text.replaceAll("^\\n+", "").stripTrailing();
            String fence = "```";
            while (text.contains(fence)) {
                fence += "`";
            }
            return fence + (language != null ? language : "") + "\n" + text + "\n" + fence;
        }

        private static String language(String classes) {
            Matcher m = LANGUAGE.matcher(classes == null ? "" : classes);
            return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
        }

        private String inlineOf(Element element) {
            StringBuilder run = new StringBuilder();
            for (Node child : element.childNodes()) {
                inline(child, run);
            }
            return tidy(run.toString());
        }

        private void inline(Node node, StringBuilder out) {
            if (node instanceof TextNode text) {
                out.append(SPACES.matcher(text.getWholeText()).replaceAll(" "));
                return;
            }
            if (!(node instanceof Element element)) {
                return; // comments, data
            }
            String name = element.normalName();
            if (DROPPED.contains(name)) {
                return;
            }
            switch (name) {
                case "br" -> out.append('\n');
                case "img" -> {
                    String alt = element.attr("alt").strip();
                    if (!alt.isEmpty()) {
                        out.append(SPACES.matcher(alt).replaceAll(" "));
                    }
                }
                case "strong", "b" -> wrap(element, "**", out);
                case "em", "i" -> wrap(element, "*", out);
                case "code", "kbd", "samp", "tt" -> {
                    String code = SPACES.matcher(element.wholeText()).replaceAll(" ").strip();
                    if (!code.isEmpty()) {
                        String tick = code.contains("`") ? "``" : "`";
                        out.append(tick).append(code).append(tick);
                    }
                }
                case "a" -> {
                    String text = inlineOf(element).replace('\n', ' ');
                    String href = href(element.attr("href"));
                    if (text.isEmpty()) {
                        return;
                    }
                    if (href == null) {
                        out.append(text);
                    } else {
                        out.append('[').append(text.replace("]", "\\]")).append("](").append(href).append(')');
                    }
                }
                default -> {
                    for (Node child : element.childNodes()) {
                        if (child instanceof Element e && isBlock(e)) {
                            // A block inside an inline element (Word does this): a line break keeps it apart.
                            out.append('\n').append(blocksOf(e)).append('\n');
                        } else {
                            inline(child, out);
                        }
                    }
                }
            }
        }

        private void wrap(Element element, String mark, StringBuilder out) {
            String text = inlineOf(element);
            if (!text.isEmpty()) {
                out.append(mark).append(text).append(mark);
            }
        }

        /** An address a reader may follow, absolute; {@code null} for anything else. */
        private String href(String raw) {
            String href = raw == null ? "" : raw.strip();
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
                return safe(href);
            }
            if (href.startsWith("/") && !href.startsWith("//") && !href.startsWith("/\\")) {
                return safe(origin + href);
            }
            return null;
        }

        private static String safe(String url) {
            return url.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
        }

        private static boolean isBlock(Element element) {
            return BLOCKS.contains(element.normalName());
        }

        /** Each line trimmed, runs of blank lines removed: a paragraph's text. */
        private static String tidy(String text) {
            StringBuilder out = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(trimmed);
                }
            }
            return out.toString();
        }

        private static String prefix(String text, String first, String rest) {
            StringBuilder out = new StringBuilder();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                String marker = i == 0 ? first : rest;
                out.append(lines[i].isEmpty() ? marker.stripTrailing() : marker + lines[i]);
            }
            return out.toString();
        }
    }
}
