package io.pacod.cms.site.services;

import io.pacod.cms.site.config.CacheConfig;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.LandingSlot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import io.pacod.cms.site.data.ListItem;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Cached read path for a single {@code article-list} section.
 *
 * <p>Like {@link ArticleReader}, this is a separate bean on purpose: Spring's caching is
 * proxy-based, so a {@code @Cacheable} method that {@code PageComposer} called on {@code this}
 * would silently do nothing.
 *
 * <p>A home page is built from one of these reads per article list, and every {@code Article}
 * eager-loads its {@code authors}, {@code categories} and {@code images} {@code @DBRef}s -- so an
 * uncached home page would be by far the most expensive route on the site.
 */
@Slf4j
@Service
public class CategoryWidgetReader {

    private final WebArticleService articleService;

    public CategoryWidgetReader(WebArticleService articleService) {
        this.articleService = articleService;
    }

    /**
     * Reads the top {@code limit} articles of one category.
     *
     * <p>The cache key includes {@code lang} -- without it the first visitor's language would
     * be served to everyone -- and {@code limit}, because the same category can be read at
     * different depths (two sections over one category, or one with an offset).
     *
     * <p>Ordering is the {@code {priority: -1, createdOn: -1}} declared on
     * {@code ArticleRepository.findArticleByCategories}. The {@code PageRequest} is deliberately
     * <em>unsorted</em>: {@code Query.with(Pageable)} appends the pageable's sort to the one
     * declared on the {@code @Query}, so adding a sort here would silently override the
     * repository's intent.
     *
     * @return the slot, or {@link LandingSlot#EMPTY} when the category has no articles.
     *         Never null, so the template needs no null guard.
     */
    @Cacheable(value = CacheConfig.WIDGETS, key = "#categoryId + ':' + #limit + ':' + #lang",
            unless = "#result == null")
    public LandingSlot read(Integer categoryId, int limit, String lang) {
        log.debug("Loading landing widget category={} limit={} lang={} from MongoDB (cache miss)",
                categoryId, limit, lang);

        ArticleListPageable list =
                articleService.getAllArticlesByCategoryPageable(categoryId, PageRequest.of(0, limit), lang);

        if (list == null || list.getPage() == null) {
            return LandingSlot.EMPTY;
        }

        return new LandingSlot(categoryId, list.getName(), list.getPage().getContent());
    }

    /** The newest {@code limit} articles, as a widget slot without a category. */
    @Cacheable(value = CacheConfig.WIDGETS, key = "'latest:' + #limit + ':' + #lang", unless = "#result == null")
    public LandingSlot readLatest(int limit, String lang) {
        log.debug("Loading the latest {} articles lang={} from MongoDB (cache miss)", limit, lang);
        Page<ListItem> page = articleService.getAllArticlesPageable(PageRequest.of(0, limit), lang);
        return page == null ? LandingSlot.EMPTY : new LandingSlot(null, "", page.getContent());
    }
}
