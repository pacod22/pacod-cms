package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.SiteFileKind;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an operator may store for a crawler file, checked where the studio saves it -- so a crafted
 * POST meets the same rules as the form. Every refusal is a {@link Refused} carrying a message key
 * ({@code siteFiles.error.*}, the studio's {@code ops} bundles) and, for a line, its number.
 *
 * <ul>
 *   <li>Every text is {@link #clean}ed: line ends made {@code \n}, NUL and other control
 *       characters but the tab removed.</li>
 *   <li>Sizes are UTF-8 bytes, against {@link SiteFileKind#customLimit()} and
 *       {@link SiteFileKind#additionsLimit()}.</li>
 *   <li>A custom sitemap must parse -- with DOCTYPE refused outright, so no entity is ever
 *       expanded and nothing outside is read -- into a {@code urlset} or {@code sitemapindex} of the
 *       sitemaps namespace, with at most {@link #MAX_URLS} {@code loc}s, each absolute http(s).</li>
 *   <li>robots additions are {@code field: value} lines or comments; sitemap additions a path or
 *       an absolute address on one of the website's own hosts; exclusions a path or a path prefix
 *       ending in {@code *}.</li>
 * </ul>
 */
public final class SiteFileRules {

    /** The sitemap protocol's limit for one file. */
    public static final int MAX_URLS = 50_000;

    public static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private static final Pattern ROBOTS_LINE = Pattern.compile("[A-Za-z][A-Za-z-]{0,40}\\s*:.*");
    private static final Pattern PATH = Pattern.compile("/(?![/\\\\])[^\\s\\\\]*");

    /** A refusal: a {@code siteFiles.error.*} key and its arguments. */
    public static final class Refused extends RuntimeException {

        private final String code;
        private final transient Object[] args;

        public Refused(String code, Object... args) {
            super(code);
            this.code = code;
            this.args = args;
        }

        public String code() {
            return code;
        }

        public Object[] args() {
            return args.clone();
        }
    }

    private SiteFileRules() {
    }

    /** {@code text} with {@code \n} line ends and no control character but the tab; {@code null} stays null. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The meaningful lines of {@code text}: trimmed, blanks and {@code #} comments left out. */
    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /** An exclusion: a path from the root, a {@code *} allowed only at its end. */
    public static boolean isExclusion(String line) {
        if (line == null) {
            return false;
        }
        String path = line.endsWith("*") ? line.substring(0, line.length() - 1) : line;
        return !path.isEmpty() && PATH.matcher(path).matches() && !path.contains("*");
    }

    /** A site-relative path an addition may name: one leading {@code /}, never {@code //} or {@code /\}. */
    public static boolean isPath(String line) {
        return line != null && PATH.matcher(line).matches() && !line.contains("*");
    }

    /** The host of an absolute http(s) address, lowercase; {@code null} for anything else. */
    public static String httpHost(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                return null;
            }
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** The custom text, cleaned and checked; {@code null} for a blank one. */
    public static String checkCustom(SiteFileKind kind, String text) {
        String clean = clean(text);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        if (!kind.customisable()) {
            throw new Refused("siteFiles.error.notCustomisable");
        }
        size(clean, kind.customLimit());
        if (kind == SiteFileKind.SITEMAP) {
            checkSitemapXml(clean);
        }
        return clean;
    }

    /** The additions, cleaned and checked line by line; {@code null} for blank. */
    public static String checkAdditions(SiteFileKind kind, String text, Set<String> ownHosts) {
        String clean = clean(text);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        size(clean, kind.additionsLimit());
        String[] lines = clean.split("\n");
        int urls = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            switch (kind) {
                case ROBOTS -> {
                    if (!ROBOTS_LINE.matcher(line).matches()) {
                        throw new Refused("siteFiles.error.robotsLine", i + 1, line);
                    }
                }
                case SITEMAP -> {
                    if (!isPath(line)) {
                        String host = httpHost(line);
                        if (host == null) {
                            throw new Refused("siteFiles.error.sitemapLine", i + 1, line);
                        }
                        if (!ownHosts.contains(host)) {
                            throw new Refused("siteFiles.error.foreignHost", i + 1, line);
                        }
                    }
                    if (++urls > MAX_URLS) {
                        throw new Refused("siteFiles.error.tooManyUrls", MAX_URLS);
                    }
                }
                default -> {
                    // Markdown, appended as written.
                }
            }
        }
        return clean;
    }

    /** The exclusions, cleaned and checked line by line; {@code null} for blank. */
    public static String checkExclusions(String text) {
        String clean = clean(text);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        size(clean, 64 * 1024);
        String[] lines = clean.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (!line.isEmpty() && !line.startsWith("#") && !isExclusion(line)) {
                throw new Refused("siteFiles.error.exclusionLine", i + 1, line);
            }
        }
        return clean;
    }

    /** Refuses a text heavier than {@code limit} UTF-8 bytes. */
    public static void size(String text, int limit) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limit) {
            throw new Refused("siteFiles.error.tooLarge", (limit + 1023) / 1024);
        }
    }

    static void checkSitemapXml(String xml) {
        Document document;
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            document = builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.toLowerCase(Locale.ROOT).contains("doctype")) {
                throw new Refused("siteFiles.error.doctype");
            }
            throw new Refused("siteFiles.error.xml", message.length() > 200 ? message.substring(0, 200) : message);
        } catch (IOException | ParserConfigurationException e) {
            throw new Refused("siteFiles.error.xml", String.valueOf(e.getMessage()));
        }
        Element root = document.getDocumentElement();
        String name = root.getLocalName();
        if (!SITEMAP_NS.equals(root.getNamespaceURI()) || !("urlset".equals(name) || "sitemapindex".equals(name))) {
            throw new Refused("siteFiles.error.sitemapRoot");
        }
        NodeList locs = document.getElementsByTagNameNS(SITEMAP_NS, "loc");
        if (locs.getLength() > MAX_URLS) {
            throw new Refused("siteFiles.error.tooManyUrls", MAX_URLS);
        }
        for (int i = 0; i < locs.getLength(); i++) {
            String loc = locs.item(i).getTextContent().strip();
            if (httpHost(loc) == null) {
                throw new Refused("siteFiles.error.sitemapLoc", loc.length() > 200 ? loc.substring(0, 200) : loc);
            }
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
