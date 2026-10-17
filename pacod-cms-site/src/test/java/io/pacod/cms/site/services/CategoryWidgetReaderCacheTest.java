package io.pacod.cms.site.services;

import io.pacod.cms.site.config.CacheConfig;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.ListItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the widget cache is actually wired, and keyed on everything it varies by.
 *
 * <p>A plain Mockito test cannot see either problem. Spring's caching is proxy-based, so a
 * {@code @Cacheable} method invoked directly on a hand-constructed instance simply runs -- the
 * annotation is inert. This test therefore builds a real (tiny) context so the proxy exists.
 *
 * <p>{@code CacheConfig} carries {@code @EnableCaching} itself, so registering that one class is
 * enough; no MongoDB is involved because {@code WebArticleService} is mocked.
 */
class CategoryWidgetReaderCacheTest {

    private AnnotationConfigApplicationContext context;
    private WebArticleService articleService;
    private CategoryWidgetReader reader;

    @BeforeEach
    void setUp() {
        articleService = mock(WebArticleService.class);
        when(articleService.getAllArticlesByCategoryPageable(anyInt(), any(Pageable.class), anyString()))
                .thenAnswer(invocation -> listOf(invocation.getArgument(0)));

        context = new AnnotationConfigApplicationContext();
        context.register(CacheConfig.class, CategoryWidgetReader.class);
        context.registerBean(WebArticleService.class, () -> articleService);
        context.refresh();

        reader = context.getBean(CategoryWidgetReader.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static ArticleListPageable listOf(Integer categoryId) {
        Page<ListItem> page = new PageImpl<>(List.of(), PageRequest.of(0, 3), 0);
        ArticleListPageable list =
                ArticleListPageable.builder().id(categoryId).name("Category " + categoryId).build();
        list.setPage(page);
        return list;
    }

    @Test
    @DisplayName("A repeated read is served from the cache")
    void secondReadIsCached() {
        reader.read(1000, 4, "bg");
        reader.read(1000, 4, "bg");

        verify(articleService, times(1))
                .getAllArticlesByCategoryPageable(anyInt(), any(Pageable.class), anyString());
    }

    @Test
    @DisplayName("The cache key includes the language")
    void languageIsPartOfTheKey() {
        reader.read(1000, 4, "bg");
        reader.read(1000, 4, "en");

        // Without lang in the key the first visitor's language would be served to everyone.
        verify(articleService, times(2))
                .getAllArticlesByCategoryPageable(anyInt(), any(Pageable.class), anyString());
    }

    @Test
    @DisplayName("The cache key includes the item limit")
    void limitIsPartOfTheKey() {
        // The same category is read at two depths -- 1000 feeds both the hero and the news feed.
        reader.read(1000, 4, "bg");
        reader.read(1000, 3, "bg");

        verify(articleService, times(2))
                .getAllArticlesByCategoryPageable(anyInt(), any(Pageable.class), anyString());
    }

    @Test
    @DisplayName("A category with no articles reads back as an empty slot, not null")
    void emptyCategoryYieldsEmptySlot() {
        assertThat(reader.read(7, 3, "bg").isEmpty()).isTrue();
    }
}
