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
 * <p>Then the per-host {@code sitemap.xml} and {@code robots.txt}: each website lists its own
 * published articles and nobody else's.
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
}
