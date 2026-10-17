package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cached read path for banner documents.
 *
 * <p>A separate bean for the same reason {@link ArticleReader} is: Spring's caching is
 * proxy-based, so a {@code @Cacheable} method that {@code BannerService} then called on
 * {@code this} would silently bypass the cache.
 *
 * <p>Deliberately caches the <em>whole collection</em> under one key rather than one entry per
 * placement or per page. Two reasons:
 *
 * <ul>
 *   <li>There are on the order of ten banners. Reading them all costs one query with no
 *       {@code @DBRef} fan-out, and filtering ten objects in the JVM is free.</li>
 *   <li>{@code activeFrom}/{@code activeTo} are checked against the clock <em>after</em> the
 *       cache, in {@link BannerService}. Caching a filtered list instead would make a booking's
 *       expiry lag by up to the TTL -- an advertiser would keep running for a minute after the
 *       campaign ended, and start up to a minute late.</li>
 * </ul>
 *
 * <p>The TTL is the honest bound described in {@link CacheConfig}, and it is the <em>only</em>
 * bound: the studio writes bookings in another process, where an eviction would do nothing
 * here. A booking created, edited or expired at {@code /mgmt/banners} reaches the site within
 * 60s.
 */
@Slf4j
@Service
public class BannerReader {

    /** Ascending sort order, ties broken by id so the ordering is total and stable. */
    private static final Comparator<Banner> BY_ORDER =
            Comparator.comparing((Banner b) -> b.getSortOrder() == null ? Integer.MAX_VALUE : b.getSortOrder())
                    .thenComparing(b -> b.getId() == null ? Integer.MAX_VALUE : b.getId());

    private final BannerRepository bannerRepository;

    public BannerReader(BannerRepository bannerRepository) {
        this.bannerRepository = bannerRepository;
    }

    /**
     * @return every banner, sorted for display. Never {@code null}; an empty list simply renders
     *         no ad boxes.
     */
    @Cacheable(CacheConfig.BANNERS)
    public List<Banner> findAll() {
        log.debug("Loading banners from MongoDB (cache miss)");
        List<Banner> banners = new ArrayList<>(bannerRepository.findAll());
        banners.sort(BY_ORDER);
        return banners;
    }
}
