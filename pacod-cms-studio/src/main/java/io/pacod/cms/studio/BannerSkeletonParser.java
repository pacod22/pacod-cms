package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.data.BannerSlide;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a skeleton's {@code index.html} at its {@code .banner-container-inline} element, and
 * refuses anything the generator could not honestly build a folder from.
 *
 * <h2>Why a split, and why here</h2>
 *
 * <p>Generating a layout is then string concatenation -- head, container start tag, slides, tail --
 * so nothing parses HTML on the save path. Everything that can go wrong goes wrong once, when a
 * skeleton is added, with a message naming what is wrong. The alternative was parsing on every save,
 * which would fail on the same input over and over, a long way from the change that caused it.
 *
 * <h2>Why no HTML parser</h2>
 *
 * <p>A parser would hand back its own serialisation of the head and the tail, not the skeleton's
 * bytes, and the generated folder is their concatenation. What is here instead is a scan: find the
 * class literal, walk back to the start tag, walk forward through quoted attribute values to its
 * {@code >}, then depth-count the element to its close. It handles the two built-in skeletons and
 * every shape near them, and where it cannot be certain it refuses rather than
 * guessing -- which is the property that matters, because a wrong guess here produces markup that
 * renders with a 200 and shows the wrong thing.
 *
 * <h2>The three tags it insists on</h2>
 *
 * <p>This is the whole safety story of letting an operator add a skeleton. The generator writes
 * {@code css/banner.css} and {@code js/banner.js} at fixed paths, but the {@code <link>} and
 * {@code <script>} that load them come from the <em>skeleton</em>. For the two seeded skeletons those
 * agree. For an uploaded one they need not, and each mismatch fails silently with a 200 response: a
 * stylesheet link naming something else gives an unstyled banner, a missing or differently-named
 * script src leaves the first slide showing forever, and a tail that never calls {@code banner()}
 * does the same. So all three are required, and the refusal names which is missing.
 */
@Slf4j
@Component
public class BannerSkeletonParser {

    /** The class the generator writes slides into. Also the hook {@code rotator.js} queries. */
    public static final String CONTAINER_CLASS = "banner-container-inline";

    /** Stands in for the GTM container id in the stored skeleton; see {@link #parse}. */
    public static final String GTM_TOKEN = "__GTM_ID__";

    /** The stylesheet the generator writes, relative to the folder's entry file. */
    private static final String CSS_PATH = "css/banner.css";

    /** The script the generator writes. */
    private static final String JS_PATH = "js/banner.js";

    private static final Pattern GTM_ID = Pattern.compile("GTM-[A-Z0-9]{4,}");

    private static final Pattern LINK_HREF =
            Pattern.compile("<link\\b[^>]*\\bhref\\s*=\\s*[\"']?\\s*([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCRIPT_SRC =
            Pattern.compile("<script\\b[^>]*\\bsrc\\s*=\\s*[\"']?\\s*([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern BANNER_CALL = Pattern.compile("\\bbanner\\s*\\(\\s*\\)");

    /** {@code var speed = 10000} in a hand-written rotator. */
    private static final Pattern SPEED = Pattern.compile("\\bspeed\\s*=\\s*(\\d+)");

    /**
     * The result of a split. {@code gtmId} is null when the skeleton loads no Google Tag Manager.
     *
     * <p>{@code htmlTail} includes the container's closing tag, so a caller never has to know what
     * the element was called.
     */
    public record Split(String htmlHead, String containerTag, String htmlTail, String gtmId) {
    }

    /**
     * Splits {@code html}, or throws with a message an operator can act on.
     *
     * <p>A GTM container id found in the markup is replaced throughout by {@link #GTM_TOKEN} and
     * returned separately, so a layout can be booked under a different container. The built-in
     * skeletons carry none; a skeleton with more than one distinct id is refused,
     * because there is no honest answer to which one a layout's setting should replace.
     */
    public Split parse(String html) {

        if (html == null || html.isBlank()) {
            throw new MgmtOperationException("banners.error.skelEmpty");
        }

        int marker = html.indexOf(CONTAINER_CLASS);
        if (marker < 0) {
            throw new MgmtOperationException("banners.error.skelNoContainer", CONTAINER_CLASS);
        }
        if (html.indexOf(CONTAINER_CLASS, marker + CONTAINER_CLASS.length()) >= 0) {
            throw new MgmtOperationException("banners.error.skelSeveralContainers", CONTAINER_CLASS);
        }

        int tagStart = html.lastIndexOf('<', marker);
        if (tagStart < 0) {
            throw new MgmtOperationException("banners.error.skelNotOnElement", CONTAINER_CLASS);
        }

        String name = elementName(html, tagStart);
        if (name == null) {
            throw new MgmtOperationException("banners.error.skelNotOnElement", CONTAINER_CLASS);
        }

        int tagEnd = endOfStartTag(html, tagStart);
        if (tagEnd < 0) {
            throw new MgmtOperationException("banners.error.skelTagNotClosed", CONTAINER_CLASS);
        }
        if (html.charAt(tagEnd - 1) == '/') {
            throw new MgmtOperationException("banners.error.skelSelfClosing", CONTAINER_CLASS);
        }

        int closeStart = matchingClose(html, name, tagEnd + 1);
        if (closeStart < 0) {
            throw new MgmtOperationException("banners.error.skelNoClose", CONTAINER_CLASS, "</" + name + ">");
        }

        String head = html.substring(0, tagStart);
        String container = html.substring(tagStart, tagEnd);
        String tail = html.substring(closeStart);

        requireGeneratedAssets(head, tail);

        String gtmId = singleGtmId(html);
        if (gtmId != null) {
            head = head.replace(gtmId, GTM_TOKEN);
            container = container.replace(gtmId, GTM_TOKEN);
            tail = tail.replace(gtmId, GTM_TOKEN);
        }

        return new Split(head, container, tail, gtmId);
    }

    /**
     * Reads the frames out of a folder the console did not write.
     *
     * <p>This is the inverse of generation, and it is what makes an existing booking editable rather
     * than only replaceable. It is deliberately <b>lenient in the direction of reading more</b>,
     * because hand-copied folders drift from the template they were copied from: a first frame may
     * carry neither {@code id="a-banner-1"} nor {@code class="banner-link"} (the {@code acme2}
     * fixture's does not), the attribute order varies, and the indentation is a mix of tabs and
     * spaces. Matching what {@link BannerLayoutStorage} emits would therefore adopt hardly any of
     * them.
     *
     * <p>So a frame is: a direct child element of the container that contains an {@code <img>}. Its
     * link, tooltip and alt text are whatever the first {@code <a>} and the first {@code <img>}
     * inside it say. A child with no image is not a frame and is skipped rather than adopted as an
     * empty one.
     *
     * <p>Attribute values are entity-decoded on the way in, the exact inverse of
     * {@code BannerLayoutStorage.attr}: a folder's markup stores {@code &amp;} inside a URL's query
     * string, and a round trip that did not decode it would turn one {@code &} into
     * {@code &amp;amp;} on every save.
     */
    public List<BannerSlide> readSlides(String html) {

        Split split = parse(html);
        String inner = innerContent(html, split);

        List<BannerSlide> slides = new ArrayList<>();
        for (String child : directChildren(inner)) {
            String img = firstTag(child, "img");
            if (img == null) {
                continue;
            }
            String anchor = firstTag(child, "a");

            BannerSlide slide = new BannerSlide(decode(attribute(img, "src")));
            slide.setAlt(decode(attribute(img, "alt")));
            if (anchor != null) {
                slide.setHref(decode(attribute(anchor, "href")));
                slide.setTitle(decode(attribute(anchor, "title")));
            }
            slides.add(slide);
        }
        return slides;
    }

    /**
     * The rotation interval a folder's own script declares, or null when it does not.
     *
     * <p>Read rather than assumed, because adopting a five-second rotation and then writing the
     * console's ten-second default would change what the advertiser booked, silently, on the first
     * save. {@code var modulo} is deliberately <b>not</b> read: it is the slide count the
     * hand-written script hardcodes, and it is exactly the thing adoption replaces -- a folder can
     * declare {@code modulo = 2} while carrying three frames (the {@code acme2} fixture does), and
     * then its third creative is never shown.
     */
    public Integer speedFrom(String js) {
        if (js == null) {
            return null;
        }
        Matcher matcher = SPEED.matcher(js);
        if (!matcher.find()) {
            return null;
        }
        try {
            int speed = Integer.parseInt(matcher.group(1));
            return speed >= 1000 && speed <= 600000 ? speed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The container's contents, between its start tag and its close. */
    private String innerContent(String html, Split split) {
        int from = split.htmlHead().length() + split.containerTag().length() + 1;
        int to = html.length() - split.htmlTail().length();
        return from < to ? html.substring(from, to) : "";
    }

    /**
     * The top-level element children of a fragment, as raw markup.
     *
     * <p>Depth-counted per element name so a nested {@code <div>} inside a frame does not end it.
     */
    private List<String> directChildren(String fragment) {
        List<String> children = new ArrayList<>();
        int i = 0;
        while (i < fragment.length()) {
            int open = fragment.indexOf('<', i);
            if (open < 0) {
                break;
            }
            String name = elementName(fragment, open);
            if (name == null) {
                i = open + 1;
                continue;
            }
            int startTagEnd = endOfStartTag(fragment, open);
            if (startTagEnd < 0) {
                break;
            }
            int close = matchingClose(fragment, name, startTagEnd + 1);
            if (close < 0) {
                // Unclosed or void element: take the start tag alone and carry on.
                children.add(fragment.substring(open, startTagEnd + 1));
                i = startTagEnd + 1;
                continue;
            }
            children.add(fragment.substring(open, close));
            i = close + name.length() + 3;
        }
        return children;
    }

    /** The first {@code <name ...>} start tag inside {@code fragment}, or null. */
    private String firstTag(String fragment, String name) {
        int at = indexOfTag(fragment, "<" + name, 0);
        if (at < 0) {
            return null;
        }
        int end = endOfStartTag(fragment, at);
        return end < 0 ? null : fragment.substring(at, end + 1);
    }

    /** An attribute's value, quoted or bare, in any order. Empty string when absent. */
    private String attribute(String tag, String name) {
        Matcher matcher = Pattern.compile(
                        "\\b" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
                        Pattern.CASE_INSENSITIVE)
                .matcher(tag);
        if (!matcher.find()) {
            return "";
        }
        for (int group = 2; group <= 4; group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group);
            }
        }
        return "";
    }

    /** The inverse of {@code BannerLayoutStorage.attr}. Ampersand last, or it would double-decode. */
    private String decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /**
     * Reassembles a skeleton into the file it came from, for the editing screen.
     *
     * <p>The console stores three pieces and shows one file, so this is what makes editing a
     * skeleton reversible: what an operator reads is what {@link #parse} would accept back.
     *
     * <p>{@link #GTM_TOKEN} is substituted back to the recorded id, which is load-bearing rather
     * than cosmetic. The token matches no {@code GTM-} pattern, so a round trip through the editor
     * without it would come back with {@code gtmId} null and the skeleton would silently stop
     * loading Google Tag Manager for every layout built from it afterwards.
     */
    public String assemble(BannerSkeleton skeleton) {
        String html = skeleton.getHtmlHead()
                + skeleton.getContainerTag() + ">"
                + System.lineSeparator()
                + skeleton.getHtmlTail();
        return skeleton.getGtmId() == null ? html : html.replace(GTM_TOKEN, skeleton.getGtmId());
    }

    /**
     * The three things the generated folder needs the skeleton to load. See the class javadoc for
     * what each one silently breaks.
     */
    private void requireGeneratedAssets(String head, String tail) {

        String document = head + tail;

        if (!referencesPath(LINK_HREF, document, CSS_PATH)) {
            throw new MgmtOperationException("banners.error.skelNoCss", CSS_PATH, "<link>");
        }
        if (!referencesPath(SCRIPT_SRC, document, JS_PATH)) {
            throw new MgmtOperationException("banners.error.skelNoJs", JS_PATH, "<script>");
        }
        if (!BANNER_CALL.matcher(document).find()) {
            throw new MgmtOperationException("banners.error.skelNoBannerCall");
        }
    }

    private boolean referencesPath(Pattern pattern, String document, String wanted) {
        Matcher matcher = pattern.matcher(document);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            // "./css/banner.css" and "css/banner.css" resolve to the same file; a leading slash
            // does not -- that would be an absolute URL on the site, not the folder's own copy.
            if (value.startsWith("./")) {
                value = value.substring(2);
            }
            if (value.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Null when the markup loads no GTM at all, which is a legitimate skeleton. */
    private String singleGtmId(String html) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = GTM_ID.matcher(html);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            throw new MgmtOperationException("banners.error.skelSeveralGtm", String.join(", ", found));
        }
        return found.iterator().next();
    }

    /** The element name at {@code tagStart}, or null if that {@code <} does not open one. */
    private String elementName(String html, int tagStart) {
        int i = tagStart + 1;
        StringBuilder name = new StringBuilder();
        while (i < html.length() && Character.isLetterOrDigit(html.charAt(i))) {
            name.append(html.charAt(i));
            i++;
        }
        return name.isEmpty() ? null : name.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The index of the {@code >} closing the start tag at {@code tagStart}.
     *
     * <p>Skips over quoted attribute values, because a {@code >} inside one is not the end of the
     * tag -- {@code title="5 > 4"} is legal and is exactly the kind of thing an advertiser's copy
     * contains.
     */
    private int endOfStartTag(String html, int tagStart) {
        char quote = 0;
        for (int i = tagStart + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The index of the {@code </name>} matching a start tag already consumed, counting nested
     * elements of the same name. Returns -1 if the document ends first.
     */
    private int matchingClose(String html, String name, int from) {
        String open = "<" + name;
        String close = "</" + name;
        int depth = 1;
        int i = from;
        while (i < html.length()) {
            int nextOpen = indexOfTag(html, open, i);
            int nextClose = indexOfTag(html, close, i);
            if (nextClose < 0) {
                return -1;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + open.length();
                continue;
            }
            depth--;
            if (depth == 0) {
                return nextClose;
            }
            i = nextClose + close.length();
        }
        return -1;
    }

    /**
     * {@code indexOf} for a tag prefix, insisting the next character ends the name.
     *
     * <p>Without that, {@code <divider>} would be counted as an opening {@code <div>} and the
     * depth would never return to zero.
     */
    private int indexOfTag(String html, String prefix, int from) {
        int at = from;
        while (at < html.length()) {
            int found = html.indexOf(prefix, at);
            if (found < 0) {
                return -1;
            }
            int after = found + prefix.length();
            if (after >= html.length()) {
                return -1;
            }
            char c = html.charAt(after);
            if (!Character.isLetterOrDigit(c) && c != '-') {
                return found;
            }
            at = found + 1;
        }
        return -1;
    }
}
