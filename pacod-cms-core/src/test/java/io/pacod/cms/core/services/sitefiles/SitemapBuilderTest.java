package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SitemapBuilderTest {

    private static final String ORIGIN = "https://demo.example.com";
    private static final Set<String> HOSTS = Set.of("demo.example.com");

    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final PageRepository pages = mock(PageRepository.class);
    private final JournalIssueRepository issues = mock(JournalIssueRepository.class);
    private final SitemapBuilder builder = new SitemapBuilder(articles, categories, pages, issues);

    @BeforeEach
    void content() {
        Page about = new Page("about");
        about.setLastModifiedOn(LocalDateTime.of(2026, 5, 1, 10, 0));
        when(pages.findByPublishedTrueOrderBySortOrderAscSlugAsc()).thenReturn(List.of(about, new Page("terms")));
        Category news = new Category();
        news.setId(3);
        Category empty = new Category();
        empty.setId(4);
        when(categories.findAll(any(Sort.class))).thenReturn(List.of(news, empty));
        when(articles.existsByCategoriesAndStatus(3, ArticleRepository.PUBLIC)).thenReturn(true);
        when(articles.existsByCategoriesAndStatus(eq(4), any())).thenReturn(false);
        Article article = new Article(7);
        article.setDate(LocalDateTime.of(2026, 6, 2, 9, 0));
        article.setLastModifiedOn(LocalDateTime.of(2026, 6, 3, 9, 0));
        when(articles.findPublishedForSitemap(any(Pageable.class))).thenReturn(List.of(article));
        when(issues.findPublishedForSitemap()).thenReturn(List.of());
    }

    @Test
    void listsWhatAReaderMaySee() {
        String xml = builder.build(ORIGIN, new SiteFiles(), HOSTS);
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset");
        assertThat(xml).contains("<loc>https://demo.example.com/</loc>",
                "<loc>https://demo.example.com/p/about</loc><lastmod>2026-05-01</lastmod>",
                "<loc>https://demo.example.com/list/3</loc>",
                "<loc>https://demo.example.com/article/7</loc><lastmod>2026-06-03</lastmod>");
        assertThat(xml).doesNotContain("/list/4", "/issues");
        assertThat(xml).endsWith("</urlset>\n");
    }

    @Test
    void additionsAndExclusionsApplyOnlyWithAdditions() {
        SiteFiles files = new SiteFiles();
        files.getSitemap().setAdditions("/landing?x=1&y=2\nhttps://demo.example.com/extra\nhttps://other.test/no\n/landing?x=1&y=2");
        files.getSitemap().setExclusions("/p/terms\n/list/*");
        assertThat(builder.build(ORIGIN, files, HOSTS)).contains("/p/terms", "/list/3").doesNotContain("/landing");

        files.getSitemap().setMode(SiteFileMode.AUTO_PLUS);
        String xml = builder.build(ORIGIN, files, HOSTS);
        assertThat(xml).doesNotContain("/p/terms", "/list/3", "other.test");
        assertThat(xml).contains("<loc>https://demo.example.com/landing?x=1&amp;y=2</loc>",
                "<loc>https://demo.example.com/extra</loc>", "/p/about");
        assertThat(xml.split("/landing", -1)).hasSize(2);
    }

    @Test
    void neverMoreThanTheProtocolAllows() {
        SiteFiles files = new SiteFiles();
        files.getSitemap().setMode(SiteFileMode.AUTO_PLUS);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < SiteFileRules.MAX_URLS + 10; i++) {
            many.append("/x").append(i).append('\n');
        }
        files.getSitemap().setAdditions(many.toString());
        assertThat(builder.build(ORIGIN, files, HOSTS).split("<url>", -1)).hasSize(SiteFileRules.MAX_URLS + 1);
    }
}
