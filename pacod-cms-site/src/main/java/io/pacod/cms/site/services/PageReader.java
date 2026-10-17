package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Caches the whole {@code page} collection under one key.
 *
 * <p>A separate bean from {@link PageService} for the reason {@code ArticleReader} and
 * {@code BannerReader} are separate: a {@code @Cacheable} method called on {@code this} goes
 * straight to the implementation and never through the proxy, so the annotation would be inert and
 * nothing would say so.
 *
 * <p>A handful of documents, so the whole collection is the unit and {@link PageService} filters
 * per request. Caching an already-resolved <em>page</em> instead would key on slug × language and
 * give an entry per pair where one does, for no gain.
 *
 * <p>The TTL is the only staleness bound there is: the studio writes these in another JVM, so a
 * {@code @CacheEvict} at the point of the edit could never reach this process. Same topology fact
 * {@code CacheConfig} documents at length.
 */
@Slf4j
@Service
public class PageReader {

    private final PageRepository pageRepository;

    public PageReader(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    /**
     * Every published page, ordered.
     *
     * <p>Degrades to an empty list rather than propagating: a database blip should cost the reader
     * the About link, not a 500 on the page they were actually reading -- the footer that links
     * here is rendered on every route on the site.
     */
    @Cacheable(cacheNames = CacheConfig.PAGES, key = "'all'")
    public List<Page> findAll() {
        try {
            return pageRepository.findByPublishedTrueOrderBySortOrderAscSlugAsc();
        } catch (DataAccessException e) {
            log.error("Could not read the standing pages: {}", e.getMessage());
            return List.of();
        }
    }
}
