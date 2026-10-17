package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.services.ArticleService;
import io.pacod.cms.core.services.ArticleViewCounter;
import io.pacod.cms.core.util.CrawlerClassifier;
import io.pacod.cms.site.data.ArticleListPageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebArticleServiceTest {

    private static final Integer MOST_READ_ID = 100001;

    private ArticleRepository articleRepository;
    private ArticleService articleService;
    private CategoryRepository categoryRepository;
    private AuthorRepository authorRepository;
    private ArticleReader articleReader;
    private ArticleViewCounter viewCounter;

    private WebArticleService service;

    @BeforeEach
    void setUp() {
        articleRepository = mock(ArticleRepository.class);
        articleService = mock(ArticleService.class);
        categoryRepository = mock(CategoryRepository.class);
        authorRepository = mock(AuthorRepository.class);
        articleReader = mock(ArticleReader.class);
        viewCounter = mock(ArticleViewCounter.class);

        service = new WebArticleService(articleRepository, articleService, categoryRepository,
                authorRepository, articleReader, viewCounter);
    }

    // ---------------------------------------------------------------- view counter

    @Test
    @DisplayName("the live counter is applied to the returned article, not the cached instance")
    void liveCounterDoesNotMutateTheCachedArticle() {
        Article cached = new Article(1);
        cached.setStatus(io.pacod.cms.core.data.ArticleStatus.PUBLISHED);
        cached.setCounter(10);
        when(articleReader.findById(1)).thenReturn(cached);
        when(viewCounter.recordView(1)).thenReturn(11);

        Optional<Article> result = service.getArticleById(1);

        assertThat(result).isPresent();
        assertThat(result.get().getCounter()).isEqualTo(11);

        // The cached object must be untouched, or concurrent readers would see each other's
        // writes and the cache would drift from the database.
        assertThat(cached.getCounter()).isEqualTo(10);
        assertThat(result.get()).isNotSameAs(cached);
    }

    @Test
    @DisplayName("a failed increment falls back to the stored count instead of blanking it")
    void failedIncrementFallsBackToStoredCount() {
        Article cached = new Article(1);
        cached.setStatus(io.pacod.cms.core.data.ArticleStatus.PUBLISHED);
        cached.setCounter(10);
        when(articleReader.findById(1)).thenReturn(cached);
        when(viewCounter.recordView(1)).thenReturn(null);

        assertThat(service.getArticleById(1)).isPresent()
                .get().extracting(Article::getCounter).isEqualTo(10);
    }

    @Test
    @DisplayName("a crawler reads the article without counting it, and sees the cached count")
    void crawlersDoNotCountViews() {
        Article cached = new Article(1);
        cached.setStatus(io.pacod.cms.core.data.ArticleStatus.PUBLISHED);
        cached.setCounter(10);
        when(articleReader.findById(1)).thenReturn(cached);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE,
                CrawlerClassifier.classify("Mozilla/5.0 (compatible; GPTBot/1.1)"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(service.getArticleById(1)).isPresent()
                .get().extracting(Article::getCounter).isEqualTo(10);
        verify(viewCounter, never()).recordView(anyInt());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("an unknown article records no view and returns empty")
    void unknownArticleIsEmpty() {
        when(articleReader.findById(404)).thenReturn(null);

        assertThat(service.getArticleById(404)).isEmpty();
        verify(viewCounter, never()).recordView(anyInt());
    }

    @Test
    @DisplayName("an unpublished article is not there for a reader: empty, and no view recorded")
    void unpublishedArticleIsEmpty() {
        for (io.pacod.cms.core.data.ArticleStatus status : new io.pacod.cms.core.data.ArticleStatus[]{
                null, io.pacod.cms.core.data.ArticleStatus.NEW, io.pacod.cms.core.data.ArticleStatus.DRAFT,
                io.pacod.cms.core.data.ArticleStatus.PRIVATE}) {
            Article cached = new Article(7);
            cached.setStatus(status);
            when(articleReader.findById(7)).thenReturn(cached);
            assertThat(service.getArticleById(7)).as(String.valueOf(status)).isEmpty();
        }
        verify(viewCounter, never()).recordView(anyInt());
    }

    @Test
    @DisplayName("the studio's preview sees an unpublished article; a reader does not")
    void previewSeesUnpublished() {
        Article draft = new Article(8);
        draft.setStatus(io.pacod.cms.core.data.ArticleStatus.DRAFT);
        when(articleReader.findById(8)).thenReturn(draft);
        assertThat(service.getArticleById(8)).isEmpty();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(io.pacod.cms.site.render.SitePreview.ATTRIBUTE,
                new io.pacod.cms.site.render.SitePreview.Preview("p1.test.-.1.mac", null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(service.getArticleById(8)).isPresent();
    }

    @Test
    @DisplayName("invalid ids never reach the database")
    void invalidIdsShortCircuit() {
        assertThat(service.getArticleById(null)).isEmpty();
        assertThat(service.getArticleById(0)).isEmpty();
        assertThat(service.getArticleById(-1)).isEmpty();

        verify(articleReader, never()).findById(any());
        verify(viewCounter, never()).recordView(any());
    }

    // ---------------------------------------------------------------- pagination totals

    @Test
    @DisplayName("most-read page reports its real size, not a hardcoded 3")
    void mostReadTotalIsNotHardcoded() {
        List<Article> mostRead = List.of(new Article(1), new Article(2), new Article(3),
                new Article(4), new Article(5));
        when(articleService.getMostReadArticles()).thenReturn(mostRead);

        // The page size matters. PageImpl overrides the supplied total with
        // offset + content.size() whenever offset + pageSize > total, so a large page size
        // masks the hardcoded value and this assertion would pass either way. A page size
        // below the element count keeps the supplied total intact and exposes the bug.
        Pageable pageable = PageRequest.of(0, 2);
        ArticleListPageable result = service.getAllArticlesByCategoryPageable(MOST_READ_ID, pageable, "en");

        assertThat(result.getPage().getTotalElements()).isEqualTo(5);
    }

    @Test
    @DisplayName("the synthetic most-read pseudo-category skips the wasted category lookup")
    void mostReadSkipsCategoryLookup() {
        when(articleService.getMostReadArticles()).thenReturn(List.of(new Article(1)));

        service.getAllArticlesByCategoryPageable(MOST_READ_ID, PageRequest.of(0, 20), "en");

        // MOST_READ_ID is not a real category document, so looking it up could only ever miss.
        verify(categoryRepository, never()).findById(any());
    }

    // ---------------------------------------------------------------- search escaping

    @Test
    @DisplayName("regex metacharacters in a search term are escaped")
    void searchInputIsEscaped() {
        assertThat(WebArticleService.escapeForRegex("(a+)+$"))
                .isEqualTo("\\(a\\+\\)\\+\\$");
        assertThat(WebArticleService.escapeForRegex("a.b*c"))
                .isEqualTo("a\\.b\\*c");
        assertThat(WebArticleService.escapeForRegex("[x]{2}"))
                .isEqualTo("\\[x\\]\\{2\\}");
    }

    @Test
    @DisplayName("ordinary search terms are left alone, so substring matching still works")
    void ordinaryInputIsUnchanged() {
        assertThat(WebArticleService.escapeForRegex("rotect")).isEqualTo("rotect");
        assertThat(WebArticleService.escapeForRegex("solar panels")).isEqualTo("solar panels");
        assertThat(WebArticleService.escapeForRegex("")).isEmpty();
        assertThat(WebArticleService.escapeForRegex(null)).isNull();
    }

    @Test
    @DisplayName("escaping does not use Pattern.quote, which MongoDB would not honour")
    void doesNotUseBlockQuoting() {
        // \Q...\E is a Java construct; MongoDB's regex engine treats it literally.
        assertThat(WebArticleService.escapeForRegex("(a+)+$")).doesNotContain("\\Q", "\\E");
    }
}
