package io.pacod.cms.site.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import io.pacod.cms.core.tenant.TenantScopedCacheManager;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caching for the read-heavy public site.
 *
 * <h2>Why time-based expiry rather than explicit eviction</h2>
 * The public site and the studio are <em>two separate processes</em> sharing one MongoDB.
 * Content is written in the studio, so a {@code @CacheEvict} at the point of the write could
 * never invalidate this process's cache. A short TTL is therefore the honest mechanism: it
 * bounds how stale a page can be, without pretending to an invalidation guarantee this topology
 * cannot provide.
 *
 * <p>The studio carries no eviction annotations for that reason: they would evict nothing here
 * while reading like a guarantee. See {@code MgmtArticleService}'s javadoc.
 *
 * <p>If exact invalidation is ever needed, the options are a shared cache (Redis) or a
 * cache-invalidation endpoint on the site that the studio calls.
 *
 * <h2>What is deliberately NOT cached</h2>
 * The article view counter. It is incremented atomically on every request by
 * {@code ArticleViewCounter} and applied to the per-request view model, so the displayed
 * count stays live and exact even though the article body is served from cache.
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ARTICLES = "articles";
    public static final String TAGS = "tags";
    public static final String CATEGORIES = "categories";
    public static final String WIDGETS = "widgets";
    public static final String BANNERS = "banners";
    public static final String PAGES = "pages";
    public static final String LANGUAGES = "languages";
    public static final String SOCIAL = "social";

    /** {@code SiteChromeReader}: the settings and the menus, one key each. */
    public static final String SITE = "site";

    /** {@code LayoutReader}: a website's published layouts, one key per page type. */
    public static final String DESIGN = "design";

    /** Bounds staleness after an edit made in the studio. */
    private static final Duration ARTICLE_TTL = Duration.ofSeconds(60);
    /**
     * Tags are rebuilt by a nightly scheduled job, and categories change rarely -- which would
     * argue for a longer bound.
     *
     * <p>60s all the same: the TTL is the sole bound on a category renamed or a tag list rebuilt
     * by an operator, and ten minutes of a stale category name is not a trade anyone would
     * choose. 60s across the board is the whole staleness story for this application.
     */
    private static final Duration TAG_TTL = Duration.ofSeconds(60);
    private static final Duration CATEGORY_TTL = Duration.ofSeconds(60);
    /**
     * Landing-page widgets, keyed by {@code categoryId:limit:lang}.
     *
     * <p>Deliberately its own cache rather than a reuse of {@link #CATEGORIES}: that one means
     * "category documents". Keeping them separate keeps the option of giving them different
     * bounds -- the home page is the most-hit route on the site and is the last one that should
     * get a long bound.
     */
    private static final Duration WIDGET_TTL = Duration.ofSeconds(60);
    /**
     * The whole banner collection under a single key.
     *
     * <p>The TTL bounds staleness after an edit; it deliberately does <em>not</em> bound a
     * booking's run window, because {@code BannerService} checks {@code activeFrom}/{@code activeTo}
     * against the clock after reading the cached list. Caching an already-filtered list would make
     * a campaign start and end up to a minute late.
     */
    private static final Duration BANNER_TTL = Duration.ofSeconds(60);
    /**
     * The standing pages, under a single key.
     *
     * <p>60s like everything else. These change less often than any other content on the site --
     * the terms are edited perhaps yearly -- but they are also read on every route, because the
     * footer links to them, and a longer bound here would be the one place a studio edit
     * appeared to have done nothing.
     */
    private static final Duration PAGE_TTL = Duration.ofSeconds(60);

    /**
     * The languages a standing page can be written in, under a single key.
     *
     * <p>60s like the rest. These change less often than anything else here -- a language is added
     * perhaps once a year -- but the bound is what an operator experiences after adding one, and a
     * language that took ten minutes to appear on the site would be reported as broken.
     */
    private static final Duration LANGUAGE_TTL = Duration.ofSeconds(60);
    private static final Duration SOCIAL_TTL = Duration.ofSeconds(60);
    private static final Duration SITE_TTL = Duration.ofSeconds(60);

    /**
     * Caffeine underneath, behind {@link TenantScopedCacheManager}: every key is qualified by the
     * website being served, so no entry is ever shared between websites. The sizes are for many
     * websites -- an entry per website per key -- not for one.
     */
    @Bean
    public CacheManager cacheManager() {
        log.info("Initializing cache manager (articles={}s, tags={}s, categories={}s, widgets={}s, "
                        + "banners={}s, pages={}s, languages={}s, social={}s, site={}s)",
                ARTICLE_TTL.toSeconds(), TAG_TTL.toSeconds(), CATEGORY_TTL.toSeconds(),
                WIDGET_TTL.toSeconds(), BANNER_TTL.toSeconds(), PAGE_TTL.toSeconds(),
                LANGUAGE_TTL.toSeconds(), SOCIAL_TTL.toSeconds(), SITE_TTL.toSeconds());

        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Any cache not explicitly registered below falls back to these settings.
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(ARTICLE_TTL));

        manager.registerCustomCache(ARTICLES, Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(ARTICLE_TTL)
                .build());

        manager.registerCustomCache(TAGS, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(TAG_TTL)
                .build());

        manager.registerCustomCache(CATEGORIES, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(CATEGORY_TTL)
                .build());

        // One entry per article list, depth and language, for many websites.
        manager.registerCustomCache(WIDGETS, Caffeine.newBuilder()
                .maximumSize(3000)
                .expireAfterWrite(WIDGET_TTL)
                .build());

        // One entry -- the whole collection. Sized for headroom, not for growth.
        manager.registerCustomCache(BANNERS, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(BANNER_TTL)
                .build());

        // Also one entry, for the same reason: a handful of standing pages, filtered per request
        // by PageService. Keyed by slug and language instead, this would hold an entry per page
        // per language and still only ever be read one at a time.
        manager.registerCustomCache(PAGES, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(PAGE_TTL)
                .build());

        // One entry again -- the whole language list, which every standing page render reads.
        manager.registerCustomCache(LANGUAGES, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(LANGUAGE_TTL)
                .build());

        // /social: the page document under one key, and its latest-articles block under one key
        // per count. The TTL is the only invalidation, as everywhere here -- the studio that
        // edits the page is another JVM.
        manager.registerCustomCache(SOCIAL, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(SOCIAL_TTL)
                .build());
        manager.registerCustomCache(SITE, Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(SITE_TTL)
                .build());

        return new TenantScopedCacheManager(manager);
    }
}
