package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reader may see is a published article, on every public surface, against a real MongoDB:
 * a draft, a new article and one without a status are 404 or absent, a published one is there.
 * Mocked repositories would pass for the wrong reason -- the predicate lives in the queries.
 *
 * <p>Then the per-host {@code sitemap.xml}, {@code robots.txt} and Atom feeds: each website lists
 * its own published articles and nobody else's, by its own address.
 */
class PublicArticlesIT extends TwoWebsitesIT {

    private static final String PUBLISHED = "Zephyr published headline";
    private static final List<String> HIDDEN = List.of("Zephyr draft headline", "Zephyr new headline", "Zephyr unstated headline");

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    AuthorRepository authorRepository;

    @Autowired
    org.springframework.cache.CacheManager cacheManager;

    @BeforeAll
    void seed() {
        TenantContext.runAs(alpha, () -> {
            Category field = categoryRepository.save(new Category(40, "Field notes", new ArrayList<>(), "en"));
            Category draftsOnly = categoryRepository.save(new Category(41, "Drafts only", new ArrayList<>(), "en"));
            Author author = new Author(40);
            author.setFirstname("Ada");
            author.setLastname("Zephyr");
            authorRepository.save(author);
            articles.save(article(502, PUBLISHED, ArticleStatus.PUBLISHED, field, author));
            articles.save(article(501, HIDDEN.get(0), ArticleStatus.DRAFT, field, author, draftsOnly));
            articles.save(article(503, HIDDEN.get(1), ArticleStatus.NEW, field, author, draftsOnly));
            articles.save(article(504, HIDDEN.get(2), null, field, author, draftsOnly));
            // Below 502, so /latest and the home page's lead stay the published headline.
            Article translated = article(500, "Zephyr original headline", ArticleStatus.PUBLISHED, field, author);
            translated.setTitles(List.of(new io.pacod.cms.core.data.TextItem("Zephyr deutsche Schlagzeile", "de")));
            translated.setTexts(List.of(new io.pacod.cms.core.data.TextItem("<p>Zephyr Text.</p>", "de")));
            translated.setCounter(0);
            articles.save(translated);
        });
        TenantContext.runAs(beta, () -> {
            Category beta = categoryRepository.save(new Category(40, "Beta notes", new ArrayList<>(), "en"));
            articles.save(article(505, "Beta only headline", ArticleStatus.PUBLISHED, beta, null));
        });
        // Another class in this context may have cached alpha's home-page lists before these existed.
        TenantContext.runAs(alpha, () -> cacheManager.getCacheNames()
                .forEach(name -> cacheManager.getCache(name).clear()));
    }

    private static Article article(int id, String title, ArticleStatus status, Category category, Author author,
                                   Category... more) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setText("<p>" + title + " body, zephyrword.</p>");
        article.setStatus(status);
        // Very recent and much read, so the most-read list would pick the drafts first.
        article.setDate(LocalDateTime.now().minusHours(1));
        article.setCreatedOn(LocalDateTime.now().minusHours(1));
        article.setCounter(status == ArticleStatus.PUBLISHED ? 5 : 900);
        List<Category> categories = new ArrayList<>(List.of(category));
        categories.addAll(List.of(more));
        article.setCategories(categories);
        if (author != null) {
            article.setAuthors(List.of(author));
        }
        article.setTags(List.of("zephyrtag"));
        return article;
    }

    private void showsOnlyThePublished(String path) {
        HttpResponse<String> page = get(ALPHA_HOST, path);
        assertThat(page.statusCode()).as(path).isEqualTo(200);
        assertThat(page.body()).as(path).contains(PUBLISHED);
        for (String hidden : HIDDEN) {
            assertThat(page.body()).as(path).doesNotContain(hidden);
        }
    }

    @Test
    @DisplayName("an unpublished article is a 404 to a reader, on the page, the print view and the JSON API; a published one is not")
    void unpublishedIsNotFound() {
        assertThat(get(ALPHA_HOST, "/article/502").statusCode()).isEqualTo(200);
        for (int id : List.of(501, 503, 504)) {
            assertThat(get(ALPHA_HOST, "/article/" + id).statusCode()).as("/article/" + id).isEqualTo(404);
            assertThat(get(ALPHA_HOST, "/de/article/" + id).statusCode()).as("/de/article/" + id).isEqualTo(404);
            assertThat(get(ALPHA_HOST, "/article-print/" + id).statusCode()).as("/article-print/" + id).isEqualTo(404);
            assertThat(get(ALPHA_HOST, "/web/v1/article/" + id, "application/json").body()).doesNotContain("Zephyr");
        }
    }

    @Test
    @DisplayName("every list a reader can reach holds the published article and no other")
    void listsHoldOnlyThePublished() {
        // The home page's default layout: the latest as hero and cards, and the most read.
        showsOnlyThePublished("/");
        showsOnlyThePublished("/latest");
        showsOnlyThePublished("/list/40");
        showsOnlyThePublished("/list/zephyrtag");
        showsOnlyThePublished("/list/100001");
        showsOnlyThePublished("/search?q=zephyrword");
        for (String json : List.of("/web/v1/article", "/web/v1/bycategory/40", "/web/v1/byauthor/40",
                "/web/v1/byauthor/paging/40", "/web/v1/search/zephyrword", "/web/v1/article/paging/")) {
            String body = get(ALPHA_HOST, json, "application/json").body();
            assertThat(body).as(json).contains(PUBLISHED);
            HIDDEN.forEach(hidden -> assertThat(body).as(json).doesNotContain(hidden));
        }
        // A category that holds only drafts lists nothing.
        assertThat(get(ALPHA_HOST, "/list/41").body()).doesNotContain("Zephyr");
    }

    @Test
    @DisplayName("each website's sitemap lists its own published articles, pages and categories, and nothing else")
    void sitemapPerWebsite() {
        HttpResponse<String> alphaMap = get(ALPHA_HOST, "/sitemap.xml", "application/xml");
        assertThat(alphaMap.statusCode()).isEqualTo(200);
        assertThat(alphaMap.headers().firstValue("Content-Type").orElse("")).startsWith("application/xml");
        String xml = alphaMap.body();
        // The origin is the Host the reader sent, which names no port here.
        String origin = "http://" + ALPHA_HOST;
        assertThat(xml).startsWith("<?xml").contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">",
                "<loc>" + origin + "/</loc>", "<loc>" + origin + "/p/about</loc>",
                "<loc>" + origin + "/list/40</loc>", "<loc>" + origin + "/article/502</loc>", "<loc>" + origin + "/article/1</loc>");
        assertThat(xml).doesNotContain("/article/501<", "/article/503<", "/article/504<", "/list/41<", "/article/505<");

        String betaMap = get(BETA_HOST, "/sitemap.xml", "application/xml").body();
        assertThat(betaMap).contains("<loc>http://" + BETA_HOST + "/article/505</loc>")
                .doesNotContain("/article/502<", ALPHA_HOST);
    }

    @Test
    @DisplayName("robots.txt keeps crawlers off what is not a page and names this website's sitemap")
    void robots() {
        HttpResponse<String> robots = get(ALPHA_HOST, "/robots.txt", "text/plain");
        assertThat(robots.statusCode()).isEqualTo(200);
        assertThat(robots.body()).contains("User-agent: *", "Disallow: /article-print/", "Disallow: /b/",
                "Disallow: /social/go/", "Sitemap: http://" + ALPHA_HOST + "/sitemap.xml");
        assertThat(get(BETA_HOST, "/robots.txt", "text/plain").body()).contains("Sitemap: http://" + BETA_HOST + "/sitemap.xml");
        assertThat(get("nobody.invalid", "/robots.txt", "text/plain").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("each website's feeds hold its own published articles, by the Host's address, and set no cookie")
    void feedPerWebsite() {
        HttpResponse<String> alphaFeed = get(ALPHA_HOST, "/feed.xml", "application/atom+xml");
        assertThat(alphaFeed.statusCode()).isEqualTo(200);
        assertThat(alphaFeed.headers().firstValue("Content-Type").orElse("")).startsWith("application/atom+xml")
                .containsIgnoringCase("charset=UTF-8");
        assertThat(alphaFeed.headers().firstValue("Cache-Control").orElse("")).contains("max-age=3600");
        String origin = "http://" + ALPHA_HOST;
        assertThat(alphaFeed.body()).startsWith("<?xml").contains("<feed xmlns=\"http://www.w3.org/2005/Atom\"",
                "xml:base=\"" + origin + "/\"", "<id>" + origin + "/feed.xml</id>", "<id>" + origin + "/article/502</id>",
                PUBLISHED, "<id>" + origin + "/article/1</id>");
        assertThat(alphaFeed.body()).doesNotContain("/article/501<", "/article/503<", "/article/504<", "/article/505<", BETA_HOST);
        HIDDEN.forEach(hidden -> assertThat(alphaFeed.body()).doesNotContain(hidden));

        String betaFeed = get(BETA_HOST, "/feed.xml", "application/atom+xml").body();
        assertThat(betaFeed).contains("<id>http://" + BETA_HOST + "/article/505</id>").doesNotContain(PUBLISHED, ALPHA_HOST);

        // Category 40 is a category on both websites: each lists its own.
        String alphaField = get(ALPHA_HOST, "/list/40/feed.xml", "application/atom+xml").body();
        assertThat(alphaField).contains("<title type=\"text\">Field notes | Alpha Daily</title>", PUBLISHED)
                .doesNotContain("Beta only headline");
        HIDDEN.forEach(hidden -> assertThat(alphaField).doesNotContain(hidden));
        assertThat(get(BETA_HOST, "/list/40/feed.xml", "application/atom+xml").body())
                .contains("Beta notes | Beta Weekly", "Beta only headline").doesNotContain("Zephyr");
        // Drafts only: a valid feed with nothing in it. No such category: nothing at all.
        assertThat(get(ALPHA_HOST, "/list/41/feed.xml", "application/atom+xml").body()).doesNotContain("<entry>");
        assertThat(get(ALPHA_HOST, "/list/49/feed.xml", "application/atom+xml").statusCode()).isEqualTo(404);
        // /list/{tag} never takes the feed's path for a tag, and the tag list still answers.
        assertThat(get(ALPHA_HOST, "/list/zephyrtag/feed.xml", "application/atom+xml").statusCode()).isEqualTo(404);
        assertThat(get(ALPHA_HOST, "/list/zephyrtag").statusCode()).isEqualTo(200);

        // ?lang= lists what is translated into it, translated -- and sets no cookie: the response is
        // publicly cached.
        HttpResponse<String> german = get(ALPHA_HOST, "/feed.xml?lang=de", "application/atom+xml");
        assertThat(german.statusCode()).isEqualTo(200);
        assertThat(german.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(german.body()).contains("xml:lang=\"de\"", "<id>" + origin + "/de/article/500</id>",
                "Zephyr deutsche Schlagzeile", "&lt;p&gt;Zephyr Text.&lt;/p&gt;")
                .doesNotContain("/article/502<", "Zephyr original headline");
        assertThat(alphaFeed.body()).contains("<id>" + origin + "/article/500</id>", "Zephyr original headline");
        // The pages' ?lang still remembers the reader's choice.
        assertThat(get(ALPHA_HOST, "/article/502?lang=de").headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("lang="));
        assertThat(get("nobody.invalid", "/feed.xml", "application/atom+xml").statusCode()).isEqualTo(404);
    }

    /**
     * A website's base URL is the address everything absolute names -- the canonical link, the
     * sitemap, the feed -- whatever Host the request came in by; its neighbour keeps its own. The
     * settings are cached and the context is shared by every class here, so the change is undone
     * and the cache emptied whatever happens.
     */
    @Test
    @DisplayName("with a base URL set, the head, the sitemap and the feed name it rather than the Host, on that website alone")
    void baseUrlNamesEveryAddress() {
        String base = "https://alpha.example.org";
        setBaseUrl(base);
        try {
            String feed = get(ALPHA_HOST, "/feed.xml", "application/atom+xml").body();
            assertThat(feed).contains("xml:base=\"" + base + "/\"", "<id>" + base + "/feed.xml</id>",
                    "<id>" + base + "/article/502</id>", "href=\"" + base + "/article/502\"").doesNotContain("http://" + ALPHA_HOST);

            String page = get(ALPHA_HOST, "/article/502").body();
            assertThat(page).contains("<link rel=\"canonical\" href=\"" + base + "/article/502\"/>",
                    "<meta property=\"og:url\" content=\"" + base + "/article/502\"/>",
                    "<meta property=\"og:type\" content=\"article\"/>",
                    "type=\"application/atom+xml\" title=\"Alpha Daily\" href=\"" + base + "/feed.xml\"/>");
            assertThat(get(ALPHA_HOST, "/list/40").body())
                    .contains("<link rel=\"canonical\" href=\"" + base + "/list/40\"/>", "href=\"" + base + "/list/40/feed.xml\"/>");
            assertThat(get(ALPHA_HOST, "/sitemap.xml", "application/xml").body()).contains("<loc>" + base + "/article/502</loc>");

            assertThat(get(BETA_HOST, "/feed.xml", "application/atom+xml").body())
                    .contains("<id>http://" + BETA_HOST + "/article/505</id>").doesNotContain(base);
            assertThat(get(BETA_HOST, "/article/505").body())
                    .contains("<link rel=\"canonical\" href=\"http://" + BETA_HOST + "/article/505\"/>");
        } finally {
            setBaseUrl(null);
        }
    }

    private void setBaseUrl(String base) {
        TenantContext.runAs(alpha, () -> {
            io.pacod.cms.core.data.SiteSettings site = settings.findById(io.pacod.cms.core.data.SiteSettings.ID)
                    .orElseGet(io.pacod.cms.core.data.SiteSettings::new);
            site.setBaseUrl(base);
            settings.save(site);
            cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        });
    }
}
