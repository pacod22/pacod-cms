package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.repository.LanguageRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Caches the whole {@code language} collection under one key.
 *
 * <p>A separate bean from {@link PageService} for the reason {@code ArticleReader},
 * {@code BannerReader} and {@link PageReader} are separate: a {@code @Cacheable} method called on
 * {@code this} goes straight to the implementation and never through the proxy, so the annotation
 * would be inert and nothing would say so.
 *
 * <p>Half a dozen documents read on every standing page, and edited from another JVM — the TTL is
 * the only staleness bound there is, the topology fact {@code CacheConfig} documents at length. A
 * language added in the studio appears on the site within 60 s.
 *
 * <p><b>Degrading to an empty list is safe here, and that is by design.</b> It costs the reader
 * the language switcher and the localised notice text — not the page: {@code LanguageChain}
 * resolves the body against bare codes and never consults this. Keep it that way.
 */
@Slf4j
@Service
public class LanguageReader {

    private final LanguageRepository languageRepository;

    public LanguageReader(LanguageRepository languageRepository) {
        this.languageRepository = languageRepository;
    }

    @Cacheable(cacheNames = CacheConfig.LANGUAGES, key = "'all'")
    public List<Language> findAll() {
        try {
            return languageRepository.findAllByOrderBySortOrderAscCodeAsc();
        } catch (DataAccessException e) {
            log.error("Could not read the languages: {}", e.getMessage());
            return List.of();
        }
    }
}
