package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cached read path for article documents.
 *
 * <p>This is a separate bean on purpose. Spring's caching is proxy-based, so annotating a
 * method that {@code WebArticleService} then calls on {@code this} would silently bypass
 * the cache entirely.
 *
 * <p>Loading an {@code Article} is not just one lookup: its {@code authors}, {@code categories}
 * and {@code images} are eager {@code @DBRef}s, so each miss also costs additional reference
 * queries. That is what the cache is really saving.
 */
@Slf4j
@Service
public class ArticleReader {

    private final ArticleRepository articleRepository;

    public ArticleReader(ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    /**
     * @return the stored article, or {@code null} when absent. Nulls are not cached, so a
     *         later-created article is picked up immediately rather than being masked by a
     *         cached miss.
     *
     * <p>Deliberately not {@code sync = true}: Spring rejects combining it with {@code unless},
     * and not caching misses matters more here than collapsing a cold-start stampede. A newly
     * published article must not 404 for a further TTL window.
     */
    @Cacheable(value = CacheConfig.ARTICLES, key = "#id", unless = "#result == null")
    public Article findById(Integer id) {
        log.debug("Loading article id={} from MongoDB (cache miss)", id);
        return articleRepository.findById(id).orElse(null);
    }

    @CacheEvict(value = CacheConfig.ARTICLES, key = "#id")
    public void evict(Integer id) {
        log.debug("Evicting article id={} from cache", id);
    }
}
