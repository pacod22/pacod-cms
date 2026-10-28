package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.services.TagService;
import io.pacod.cms.site.data.ArticleModel;
import io.pacod.cms.site.data.HeadMeta;
import io.pacod.cms.site.data.SiteChrome;
import io.pacod.cms.site.data.SiteOrigin;
import io.pacod.cms.site.services.SiteChromeReader;
import io.pacod.cms.site.services.WebArticleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The website's Atom feed, {@code /feed.xml}, and a category's, {@code /list/{id}/feed.xml}: the
 * newest {@link #ENTRIES} published articles, answered for the website the Host names, as every
 * page is.
 *
 * <ul>
 *   <li><b>The same queries as the lists</b>: {@code findPublished} for the website's, as
 *       {@code /latest} reads it, and {@code findArticleByCategoriesAndStatus} for a category's, in
 *       the category list's order -- PUBLISHED inside the query, so a draft's or a private
 *       article's body never leaves the database; a status that is not public is dropped here
 *       too.</li>
 *   <li><b>{@code ?lang=}</b> another language than the original lists only the articles
 *       translated into it ({@link ArticleModel#languages()}, a body of its own, the rule the head's
 *       alternates follow), with their translated title, summary and body. It reads the newest
 *       {@link #SCAN_PAGES} pages of {@link #ENTRIES} to find them, so a website translating
 *       little has a short feed rather than an expensive one.</li>
 *   <li><b>Every address is absolute</b>, from {@link SiteOrigin} like the head's canonical: the
 *       entry's link and id are the article's canonical address in the feed's language
 *       ({@link HeadMeta#articlePath}), and a root-relative {@code src}, {@code href},
 *       {@code poster} or {@code srcset} in a body is made absolute, since a reader resolves
 *       against the feed, not the page. {@code xml:base} says the same.</li>
 *   <li>Written by the JDK's {@link XMLStreamWriter}, so every value is escaped; characters XML
 *       1.0 cannot carry are dropped first, which the writer would not do.</li>
 *   <li>A database that cannot be read is a 503 with {@code Retry-After}, as for the sitemap: an
 *       empty feed would tell a reader every article was gone. Cached an hour, likewise.</li>
 * </ul>
 *
 * <p>{@code LocaleConfig} keeps its {@code ?lang} interceptor off these two paths: a feed reader
 * is no reader to remember a language for, and a cookie on a publicly cached response is one
 * every client of the cache would be handed.
 */
@Slf4j
@Controller
public class FeedController {

    /** The two paths, for {@code LocaleConfig}'s exclusion. */
    public static final String[] PATHS = {"/feed.xml", "/list/*/feed.xml"};

    static final int ENTRIES = 20;

    /** How many pages of {@link #ENTRIES} a translated feed reads to fill itself. */
    static final int SCAN_PAGES = 5;

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final MediaType ATOM = new MediaType("application", "atom+xml", StandardCharsets.UTF_8);
    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    /** A root-relative address in an attribute that names one: {@code src="/assets/…"}, not {@code //host}. */
    private static final Pattern ROOT_RELATIVE =
            Pattern.compile("(\\s(?:src|href|poster)\\s*=\\s*)([\"'])/(?![/\\\\])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRCSET =
            Pattern.compile("(\\ssrcset\\s*=\\s*)([\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** What XML 1.0 cannot carry: C0 controls but tab, newline and return, surrogates alone, U+FFFE/F. */
    private static final Pattern NOT_XML =
            Pattern.compile("[^\\x09\\x0A\\x0D\\x20-\\uD7FF\\uE000-\\uFFFD\\x{10000}-\\x{10FFFF}]");

    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final SiteChromeReader chrome;
    private final TagService tags;

    public FeedController(ArticleRepository articles, CategoryRepository categories, SiteChromeReader chrome,
                          TagService tags) {
        this.articles = articles;
        this.categories = categories;
        this.chrome = chrome;
        this.tags = tags;
    }

    @GetMapping("/feed.xml")
    public ResponseEntity<String> feed(HttpServletRequest request, @RequestParam(required = false) String lang) {
        String code = code(lang);
        SiteChrome site = site(request);
        try {
            List<ArticleModel> entries = entries(articles::findPublished, code);
            Feed feed = new Feed(HeadMeta.feedPath(code), HeadMeta.homePath(code), publisher(site, code),
                    site.tagline(code));
            return ResponseEntity.ok().contentType(ATOM).cacheControl(CACHE).body(write(feed, entries, code, site));
        } catch (DataAccessException e) {
            return unavailable(e);
        }
    }

    /** A category's own, linked from its list's head; a tag and the most-read list have none. */
    @GetMapping("/list/{id:\\d{1,9}}/feed.xml")
    public ResponseEntity<String> category(HttpServletRequest request, @PathVariable Integer id,
                                           @RequestParam(required = false) String lang) {
        String code = code(lang);
        SiteChrome site = site(request);
        try {
            Category category = WebArticleService.isMostRead(id) ? null : categories.findById(id).orElse(null);
            if (category == null) {
                return ResponseEntity.notFound().build();
            }
            List<ArticleModel> entries = entries(
                    page -> articles.findArticleByCategoriesAndStatus(id, ArticleRepository.PUBLIC, page), code);
            String name = categoryName(category, code);
            Feed feed = new Feed(HeadMeta.categoryFeedPath(String.valueOf(id), code),
                    HeadMeta.listPath(String.valueOf(id), code),
                    name.isEmpty() ? publisher(site, code) : name + " | " + publisher(site, code),
                    site.tagline(code));
            return ResponseEntity.ok().contentType(ATOM).cacheControl(CACHE).body(write(feed, entries, code, site));
        } catch (DataAccessException e) {
            return unavailable(e);
        }
    }

    /** The feed's own facts: where it is, the page it stands for, its title and subtitle. */
    private record Feed(String self, String alternate, String title, String subtitle) {
    }

    /**
     * The newest public articles in {@code code}: the first page of the query in the original
     * language; in any other, only those translated into it, from up to {@link #SCAN_PAGES} pages.
     */
    private List<ArticleModel> entries(Function<Pageable, Page<Article>> query, String code) {
        List<Tag> known = knownTags();
        boolean translatedOnly = !code.equals(LanguageChain.original());
        List<ArticleModel> found = new ArrayList<>(ENTRIES);
        for (int page = 0; page < SCAN_PAGES && found.size() < ENTRIES; page++) {
            Page<Article> batch = query.apply(PageRequest.of(page, ENTRIES));
            for (Article article : batch.getContent()) {
                if (found.size() == ENTRIES) {
                    break;
                }
                if (!WebArticleService.isPublic(article) || article.getId() == null) {
                    continue;
                }
                ArticleModel model = new ArticleModel(article, code, known);
                if (!translatedOnly || model.languages().contains(code)) {
                    found.add(model);
                }
            }
            if (!translatedOnly || !batch.hasNext()) {
                break;
            }
        }
        return found;
    }

    private String write(Feed feed, List<ArticleModel> entries, String code, SiteChrome site) {
        ZoneId zone = HeadMeta.zone();
        ZoneId stampZone = ZoneId.systemDefault();
        OffsetDateTime updated = null;
        for (ArticleModel entry : entries) {
            OffsetDateTime modified = entry.modifiedAt(zone, stampZone);
            if (modified != null && (updated == null || modified.isAfter(updated))) {
                updated = modified;
            }
        }
        if (updated == null) {
            // Nothing dated to go by: an empty feed is as new as the request.
            updated = OffsetDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
        }

        StringWriter out = new StringWriter(16 * 1024);
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeCharacters("\n");
            xml.writeStartElement("feed");
            xml.writeDefaultNamespace(ATOM_NS);
            xml.writeAttribute(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI, "lang", code);
            xml.writeAttribute(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI, "base", site.url("/"));

            text(xml, "id", site.url(feed.self()));
            element(xml, "title", "text", feed.title());
            if (feed.subtitle() != null && !feed.subtitle().isBlank()) {
                element(xml, "subtitle", "text", feed.subtitle());
            }
            text(xml, "updated", HeadMeta.format(updated));
            link(xml, "self", "application/atom+xml", site.url(feed.self()));
            link(xml, "alternate", "text/html", site.url(feed.alternate()));
            text(xml, "icon", site.url(site.favicon() != null ? site.favicon() : "/site-assets/favicon.svg"));
            // An entry without authors of its own takes the feed's, as Atom requires one or the other.
            author(xml, publisher(site, code));

            for (ArticleModel article : entries) {
                entry(xml, article, code, site, zone, stampZone, updated);
            }

            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not write the feed", e);
        }
        return out.append('\n').toString();
    }

    private void entry(XMLStreamWriter xml, ArticleModel article, String code, SiteChrome site, ZoneId zone,
                       ZoneId stampZone, OffsetDateTime fallback) throws XMLStreamException {
        String url = site.url(HeadMeta.articlePath(article.getId(), code));
        OffsetDateTime published = article.publishedAt(zone);
        OffsetDateTime modified = article.modifiedAt(zone, stampZone);

        xml.writeStartElement("entry");
        text(xml, "id", url);
        // Titles may carry markup; the page renders it, so the feed says so.
        element(xml, "title", "html", article.getTitle() != null ? article.getTitle() : "");
        link(xml, "alternate", "text/html", url);
        if (published != null) {
            text(xml, "published", HeadMeta.format(published));
        }
        text(xml, "updated", HeadMeta.format(modified != null ? modified : fallback));
        if (article.getAuthors() != null) {
            for (var author : article.getAuthors()) {
                String name = article.authorName(author);
                if (!name.isBlank()) {
                    author(xml, name);
                }
            }
        }
        List<String> terms = new ArrayList<>();
        if (article.sectionName() != null) {
            terms.add(article.sectionName());
        }
        article.tagNames().stream().filter(term -> !terms.contains(term)).forEach(terms::add);
        for (String term : terms) {
            xml.writeEmptyElement("category");
            xml.writeAttribute("term", clean(term));
        }
        // The article's own description in this language, not getDescription()'s byline stand-in.
        String summary = plain(article.descriptionIn(code));
        if (!summary.isEmpty()) {
            element(xml, "summary", "text", summary);
        }
        String body = article.getText();
        if (body != null && !body.isBlank()) {
            xml.writeStartElement("content");
            xml.writeAttribute("type", "html");
            xml.writeAttribute(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI, "base", url);
            xml.writeCharacters(clean(absolute(body, site.origin())));
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    /**
     * {@code html} with every root-relative address made absolute against {@code origin}: what a
     * feed reader, which resolves against the feed's address or nothing, needs to show a picture.
     */
    static String absolute(String html, String origin) {
        String bare = ROOT_RELATIVE.matcher(html).replaceAll(m ->
                Matcher.quoteReplacement(m.group(1) + m.group(2) + origin + "/"));
        return SRCSET.matcher(bare).replaceAll(m -> {
            List<String> candidates = new ArrayList<>();
            for (String candidate : m.group(3).split(",")) {
                String trimmed = candidate.strip();
                candidates.add(trimmed.startsWith("/") && !trimmed.startsWith("//") ? origin + trimmed : trimmed);
            }
            return Matcher.quoteReplacement(m.group(1) + m.group(2) + String.join(", ", candidates) + m.group(2));
        });
    }

    private ResponseEntity<String> unavailable(DataAccessException e) {
        log.error("Could not read the feed's articles: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "600")
                .contentType(TEXT).body("The feed cannot be read right now.\n");
    }

    /** The website's name, tagline and origin; settings degrade to empty rather than throw. */
    private SiteChrome site(HttpServletRequest request) {
        return SiteChrome.of(chrome.settings(), Map.of(), SiteOrigin.ofRequest(request));
    }

    /** The website's name, or its host while it has none: a feed's title and author are required. */
    private static String publisher(SiteChrome site, String code) {
        String name = site.name(code);
        return !name.isBlank() ? name : site.origin().replaceFirst("^[a-z]+://", "");
    }

    /** The translations the tag collection holds; none when it cannot be read -- the names stand. */
    private List<Tag> knownTags() {
        try {
            return tags.getAllTags();
        } catch (RuntimeException e) {
            log.error("Could not read the tags for the feed: {}", e.getMessage());
            return List.of();
        }
    }

    /** {@code lang} as a bare code a route accepts, else the website's own language. */
    private static String code(String lang) {
        String code = LanguageChain.normalise(lang);
        return code != null && code.matches("[a-z]{2,3}") ? code : LanguageChain.original();
    }

    private static String categoryName(Category category, String code) {
        String name = null;
        if (!code.equals(LanguageChain.original()) && category.getNames() != null) {
            name = category.getNames().stream()
                    .filter(item -> item != null && code.equalsIgnoreCase(item.getLang()))
                    .map(item -> item.getText()).filter(text -> text != null && !text.isBlank())
                    .findFirst().orElse(null);
        }
        if (name == null) {
            name = category.getName();
        }
        return name != null ? name.strip() : "";
    }

    private static void text(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(clean(value));
        xml.writeEndElement();
    }

    private static void element(XMLStreamWriter xml, String name, String type, String value) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeAttribute("type", type);
        xml.writeCharacters(clean(value));
        xml.writeEndElement();
    }

    private static void link(XMLStreamWriter xml, String rel, String type, String href) throws XMLStreamException {
        xml.writeEmptyElement("link");
        xml.writeAttribute("rel", rel);
        xml.writeAttribute("type", type);
        xml.writeAttribute("href", clean(href));
    }

    private static void author(XMLStreamWriter xml, String name) throws XMLStreamException {
        if (name == null || name.isBlank()) {
            return;
        }
        xml.writeStartElement("author");
        text(xml, "name", name);
        xml.writeEndElement();
    }

    /** Text with its markup dropped and its entities decoded: a summary is plain text. */
    private static String plain(String text) {
        if (text == null) {
            return "";
        }
        return HtmlUtils.htmlUnescape(text.replaceAll("<[^>]*>", " ")).replaceAll("\\s+", " ").strip();
    }

    private static String clean(String value) {
        return value == null ? "" : NOT_XML.matcher(value).replaceAll("");
    }
}
