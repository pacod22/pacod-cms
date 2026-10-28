package io.pacod.cms.site.data;

import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a reader's page says about itself in its head, beyond its title and description: the
 * address it is filed under, the same page in the website's other languages, what kind of page it
 * is, and its feed. {@code ${head}}, put in the model by the controllers that render a reader's
 * page and read by {@code fragments/head.html}.
 *
 * <h2>One address per language</h2>
 * The original language ({@link LanguageChain#original()}) is the bare route -- {@code /article/5},
 * {@code /list/9}, {@code /p/about} -- and every other language the {@code /{code}/} prefix; the
 * home page and {@code /latest}, which take no prefix, are {@code ?lang={code}}. The canonical
 * address names the language whose text the page actually shows, so a request in a language the
 * content does not have is filed under the one it fell back to, never under itself.
 * {@code hreflang} alternates are offered only for languages the content is written in
 * <em>and</em> the website offers, only when there are two or more, and {@code x-default} is the
 * original's.
 *
 * <h2>Paths, not URLs</h2>
 * Every address here is site-relative; the head makes it absolute with {@code site.url}, so the
 * origin is {@link SiteOrigin}'s and nothing here needs the request. Not a bean either: the
 * controllers build it from what they already hold, and a standalone controller test needs no
 * wiring. A null {@link SiteChrome} -- such a test runs no advice -- yields no alternates.
 *
 * <p>A page rendered without one -- the error page, which no controller renders, the issues and
 * the print views -- keeps the head it had: no canonical, no alternates, {@code og:type website}.
 *
 * @param canonical   the page's own address, or null for none (search results)
 * @param alternates  the {@code hreflang} links, {@code x-default} last; empty for none
 * @param type        {@code og:type}: {@code article} or {@code website}
 * @param published   ISO-8601 with an offset, or null: {@code article:published_time}
 * @param modified    likewise, never before {@code published}: {@code article:modified_time}
 * @param section     the first category's name, or null: {@code article:section}
 * @param tags        {@code article:tag}, as the reader sees them
 * @param authors     the authors' names, for the structured data
 * @param feed        the website's feed in the page's language
 * @param sectionFeed a category list's own feed, or null
 */
public record HeadMeta(String canonical, List<Alternate> alternates, String type, String published,
                       String modified, String section, List<String> tags, List<String> authors,
                       String feed, String sectionFeed) {

    public static final String ARTICLE = "article";
    public static final String WEBSITE = "website";
    public static final String X_DEFAULT = "x-default";

    /** The shape every route's language prefix has ({@code /{pathLang:[a-z]{2,3}}/…}). */
    private static final String CODE = "[a-z]{2,3}";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** One {@code <link rel="alternate" hreflang>}: a language code or {@link #X_DEFAULT}, and its path. */
    public record Alternate(String hreflang, String path) {
    }

    public HeadMeta {
        alternates = alternates != null ? List.copyOf(alternates) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
        authors = authors != null ? List.copyOf(authors) : List.of();
        type = type != null ? type : WEBSITE;
    }

    public boolean isArticle() {
        return ARTICLE.equals(type);
    }

    /* ------------------------------------------------------------------ pages */

    /** {@code /}, {@code /?lang=de}: the home page resolves its language from the query, or the cookie. */
    public static HeadMeta home(String lang, SiteChrome site) {
        return website(lang, site, HeadMeta::homePath);
    }

    /** {@code /latest}, {@code /latest?lang=de}: its own address, though it shows the newest article. */
    public static HeadMeta latest(String lang, SiteChrome site) {
        return website(lang, site, code -> withLang("/latest", code));
    }

    private static HeadMeta website(String lang, SiteChrome site, Function<String, String> path) {
        String code = code(lang);
        return new HeadMeta(path.apply(code), alternates(site, any -> true, path), WEBSITE,
                null, null, null, null, null, feedPath(feedLanguage(code, site)), null);
    }

    /**
     * An article, filed under the language whose body it shows ({@link ArticleModel#servedLanguage()}),
     * dated in the website's zone.
     */
    public static HeadMeta article(ArticleModel article, SiteChrome site) {
        return article(article, site, zone(), ZoneId.systemDefault());
    }

    /**
     * @param zone      the website's ({@link Tenant#timezone()}): the zone the dates are written in
     * @param stampZone the JVM's: the zone {@code lastModifiedOn} was stamped in
     */
    public static HeadMeta article(ArticleModel article, SiteChrome site, ZoneId zone, ZoneId stampZone) {
        Integer id = article.getId();
        if (id == null) {
            // An empty database's /latest renders a blank article; it has no address of its own.
            return minimal(article.getLang(), site);
        }
        List<String> written = article.languages();
        return new HeadMeta(articlePath(id, article.servedLanguage()),
                alternates(site, written::contains, code -> articlePath(id, code)), ARTICLE,
                format(article.publishedAt(zone)), format(article.modifiedAt(zone, stampZone)),
                article.sectionName(), article.tagNames(), authorNames(article),
                feedPath(feedLanguage(code(article.getLang()), site)), null);
    }

    /**
     * A category's or a tag's list: {@code /list/9}, {@code /de/list/9}, and {@code ?page=N} past the
     * first page -- {@code page} counts from 0, as the pager's links do. Size and sort are left out:
     * a page of another size is a variant, not a page of its own.
     *
     * @param id       the path's own segment, a category number or a tag name -- never the list's
     *                 displayed name, which for a tag is its translation and resolves to nothing
     * @param category whether the list has a feed of its own: a real category, not a tag and not
     *                 the most-read list
     */
    public static HeadMeta list(String id, int page, boolean category, String lang, SiteChrome site) {
        String code = code(lang);
        String query = page > 0 ? "?page=" + page : "";
        Function<String, String> path = c -> listPath(id, c) + query;
        String feedCode = feedLanguage(code, site);
        return new HeadMeta(path.apply(code), alternates(site, any -> true, path), WEBSITE,
                null, null, null, null, null, feedPath(feedCode),
                category ? categoryFeedPath(id, feedCode) : null);
    }

    /**
     * A standing page, filed under the language it was served in ({@code PageService.Rendered.language()}),
     * with alternates for the languages it has a body in: those are the ones that render as
     * themselves.
     */
    public static HeadMeta page(Page page, String served, SiteChrome site) {
        String slug = page.getSlug();
        List<String> written = page.writtenIn();
        return new HeadMeta(pagePath(slug, code(served)),
                alternates(site, written::contains, code -> pagePath(slug, code)), WEBSITE,
                null, null, null, null, null, feedPath(feedLanguage(code(served), site)), null);
    }

    /**
     * No canonical and no alternates: search results. robots.txt keeps crawlers off {@code /search}
     * already, a canonical to the bare {@code /search} would file every result page under one that
     * shows none of them, and one naming the query would invite results into an index.
     */
    public static HeadMeta minimal(String lang, SiteChrome site) {
        return new HeadMeta(null, List.of(), WEBSITE, null, null, null, null, null,
                feedPath(feedLanguage(code(lang), site)), null);
    }

    /* ------------------------------------------------------------------ addresses */

    /** {@code /article/5} in the original language, {@code /de/article/5} in any other. */
    public static String articlePath(Integer id, String lang) {
        return prefix(lang) + "/article/" + id;
    }

    public static String homePath(String lang) {
        return withLang("/", lang);
    }

    public static String listPath(String id, String lang) {
        return prefix(lang) + "/list/" + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8);
    }

    public static String pagePath(String slug, String lang) {
        return prefix(lang) + "/p/" + UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
    }

    /** {@code /feed.xml}, {@code /feed.xml?lang=de} ({@code FeedController}). */
    public static String feedPath(String lang) {
        return withLang("/feed.xml", lang);
    }

    public static String categoryFeedPath(String id, String lang) {
        return withLang("/list/" + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8) + "/feed.xml", lang);
    }

    /** The website's zone ({@link Tenant#timezone()}), UTC where none is bound. */
    public static ZoneId zone() {
        return TenantContext.find().map(Tenant::timezone).orElse(ZoneOffset.UTC);
    }

    /** ISO-8601 with an offset, to the second: {@code 2026-03-04T12:00:00+02:00}. */
    public static String format(OffsetDateTime time) {
        return time != null ? ISO.format(time) : null;
    }

    private static String prefix(String lang) {
        String code = code(lang);
        return code == null || code.equals(LanguageChain.original()) ? "" : "/" + code;
    }

    private static String withLang(String path, String lang) {
        String code = code(lang);
        return code == null || code.equals(LanguageChain.original()) ? path : path + "?lang=" + code;
    }

    /** A bare code that a route's prefix accepts, or null. */
    private static String code(String lang) {
        String code = LanguageChain.normalise(lang);
        return code != null && code.matches(CODE) ? code : null;
    }

    /** The page's language, when the website offers it; else the original's feed. */
    private static String feedLanguage(String code, SiteChrome site) {
        return code != null && (site == null || site.offers(code)) ? code : null;
    }

    /**
     * The original, then every other language the website offers, in its order, kept when
     * {@code written}; nothing below two, and {@code x-default} -- the original's address -- last.
     */
    private static List<Alternate> alternates(SiteChrome site, Predicate<String> written,
                                              Function<String, String> path) {
        if (site == null) {
            return List.of();
        }
        String original = LanguageChain.original();
        List<String> codes = new ArrayList<>();
        codes.add(original);
        for (String code : site.languageCodes()) {
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        List<Alternate> links = new ArrayList<>(codes.size() + 1);
        for (String code : codes) {
            if (code.matches(CODE) && written.test(code)) {
                links.add(new Alternate(code, path.apply(code)));
            }
        }
        if (links.size() < 2) {
            return List.of();
        }
        links.add(new Alternate(X_DEFAULT, path.apply(original)));
        return links;
    }

    private static List<String> authorNames(ArticleModel article) {
        List<String> names = new ArrayList<>();
        if (article.getAuthors() != null) {
            article.getAuthors().stream().map(article::authorName)
                    .filter(name -> !name.isBlank()).forEach(names::add);
        }
        return names;
    }

    /* ------------------------------------------------------------------ structured data */

    /**
     * The article's schema.org {@code BlogPosting}, for the head's JSON-LD; null on any other page.
     * A map, so Thymeleaf's JavaScript inlining writes it as JSON -- escaped for a script element --
     * and a value the article lacks is left out rather than written as {@code null}.
     *
     * @param headline    the page's title; markup is dropped
     * @param description the description meta's text
     * @param imageUrl    the share image's absolute address, or null
     */
    public Map<String, Object> posting(SiteChrome site, String lang, String headline, String description,
                                       String imageUrl) {
        if (!isArticle() || site == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("@context", "https://schema.org");
        json.put("@type", "BlogPosting");
        put(json, "headline", plain(headline));
        put(json, "description", plain(description));
        put(json, "image", imageUrl);
        put(json, "datePublished", published);
        put(json, "dateModified", modified);
        if (!authors.isEmpty()) {
            json.put("author", authors.stream().map(name -> typed("Person", "name", name)).toList());
        }
        put(json, "inLanguage", code(lang));
        if (canonical != null) {
            json.put("mainEntityOfPage", typed("WebPage", "@id", site.url(canonical)));
        }
        String name = site.name(lang);
        if (!name.isBlank()) {
            Map<String, Object> publisher = typed("Organization", "name", name);
            String logo = site.logoLight();
            if (logo != null) {
                publisher.put("logo", typed("ImageObject", "url",
                        site.url(UriUtils.encodePath(logo, StandardCharsets.UTF_8))));
            }
            json.put("publisher", publisher);
        }
        return json;
    }

    /** A schema.org node, {@code @type} first as the vocabulary's examples write it. */
    private static Map<String, Object> typed(String type, String key, Object value) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@type", type);
        node.put(key, value);
        return node;
    }

    private static void put(Map<String, Object> json, String key, String value) {
        if (value != null && !value.isBlank()) {
            json.put(key, value);
        }
    }

    /** Text with its markup dropped and its entities decoded, whitespace collapsed. */
    private static String plain(String text) {
        if (text == null) {
            return null;
        }
        return HtmlUtils.htmlUnescape(text.replaceAll("<[^>]*>", " ")).replaceAll("\\s+", " ").strip();
    }
}
