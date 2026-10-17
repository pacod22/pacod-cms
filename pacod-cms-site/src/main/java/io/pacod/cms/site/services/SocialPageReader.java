package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.core.util.SocialTargets;
import io.pacod.cms.site.config.CacheConfig;
import io.pacod.cms.site.data.LinkTarget;
import io.pacod.cms.site.data.SocialView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code /social}'s reads, cached. A separate bean from {@code SocialController} for the reason
 * {@code ArticleReader} is one: {@code @Cacheable} works through the proxy, and a cached method
 * called on {@code this} would read MongoDB on every render.
 *
 * <p>Two entries in {@link CacheConfig#SOCIAL}, both on the usual 60 s TTL and both invalidated by
 * nothing else -- the studio that edits the page runs in another JVM:
 * <ul>
 *   <li>the page document with its avatar already resolved to a path, because that is one more
 *       query a render would otherwise make;</li>
 *   <li>the newest articles, as documents. Every {@code Article} eager-loads three
 *       {@code @DBRef} collections, so five tiles uncached would be some twenty queries per render
 *       -- on the one page whose traffic arrives all at once, from a post.</li>
 * </ul>
 * Scheduling ({@code SocialLink.isLive}) is applied by the caller, after the cache, so a link
 * appears and disappears on time rather than up to a TTL late.
 */
@Slf4j
@Service
public class SocialPageReader {

    /** Where an {@code ImageMedia} is served from -- the same prefix {@code ArticleModel} uses. */
    static final String IMAGE_PREFIX = "/assets/img/articles/";

    /**
     * The page, plus its avatar as a public path ({@code null} for the website's logo), plus what
     * every link points at where that is the site's own content -- article, standing page or
     * category: titles, and an article's cover -- keyed by link id. A link with no entry points
     * somewhere else, or at something since deleted.
     */
    public record Snapshot(SocialPage page, String avatarPath, Map<String, LinkTarget> targets) {
        public Snapshot {
            targets = targets == null ? Map.of() : Map.copyOf(targets);
        }
    }

    private final SocialPageRepository socialPageRepository;
    private final ImageMediaRepository imageMediaRepository;
    private final ArticleRepository articleRepository;
    private final PageRepository pageRepository;
    private final CategoryRepository categoryRepository;
    private final SiteChromeReader siteChromeReader;

    public SocialPageReader(SocialPageRepository socialPageRepository,
                            ImageMediaRepository imageMediaRepository,
                            ArticleRepository articleRepository,
                            PageRepository pageRepository,
                            CategoryRepository categoryRepository,
                            SiteChromeReader siteChromeReader) {
        this.socialPageRepository = socialPageRepository;
        this.imageMediaRepository = imageMediaRepository;
        this.articleRepository = articleRepository;
        this.pageRepository = pageRepository;
        this.categoryRepository = categoryRepository;
        this.siteChromeReader = siteChromeReader;
    }

    /**
     * Empty when the studio has never written the page -- and when MongoDB cannot be read, which
     * the caller cannot tell apart and should not try to: both render the 404.
     */
    @Cacheable(cacheNames = CacheConfig.SOCIAL, key = "'page'")
    public Optional<Snapshot> page() {
        try {
            return socialPageRepository.findById(SocialPage.ID)
                    .map(page -> new Snapshot(page, avatarPath(page.getAvatarImageId()), linkTargets(page)));
        } catch (DataAccessException e) {
            log.error("Could not read the social page: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The {@code count} newest published articles, as every public listing orders them. Documents rather than per-language tiles, so one cache entry serves every language.
     */
    @Cacheable(cacheNames = CacheConfig.SOCIAL, key = "'latest-' + #count")
    public List<Article> latestArticles(int count) {
        try {
            return articleRepository.findPublished(PageRequest.of(0, count)).getContent();
        } catch (DataAccessException e) {
            log.error("Could not read the latest articles for /social: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * What the page's links point at, where that is the site's own translated content -- articles
     * (title in every language they have, and the cover), standing pages and category listings
     * (title in every language) -- read with the page and cached with it, so a render costs nothing
     * extra and a title or a cover changed in the studio shows within the TTL.
     *
     * <p>At most one query per kind, and only for kinds some link points at: articles through a
     * projection that skips authors and categories ({@code findCoversByIdIn}), the published
     * standing pages -- a handful of documents, matched in memory by slug -- and the
     * categories by id.
     *
     * <p>Each kind's failure is contained here, deliberately: {@link #page()} answers an unreadable
     * database with an empty result, which is a 404 -- and a page whose links work but whose
     * translated titles could not be read is still a page, in the language the links were written in.
     */
    private Map<String, LinkTarget> linkTargets(SocialPage page) {
        Map<String, Integer> articleOf = new LinkedHashMap<>();
        Map<String, String> pageOf = new LinkedHashMap<>();
        Map<String, Integer> categoryOf = new LinkedHashMap<>();
        // The hosts that make an absolute address this website's own, from its settings.
        Set<String> ownHosts = siteChromeReader.settings().ownHosts();
        for (SocialLink link : page.getLinks()) {
            String url = link.getUrl();
            if (link.getId() == null) {
                continue;
            }
            Integer articleId = SocialTargets.articleIdOf(url, ownHosts);
            String slug = SocialTargets.pageSlugOf(url, ownHosts);
            Integer categoryId = SocialTargets.categoryIdOf(url, ownHosts);
            if (articleId != null) {
                articleOf.put(link.getId(), articleId);
            } else if (slug != null) {
                pageOf.put(link.getId(), slug);
            } else if (categoryId != null) {
                categoryOf.put(link.getId(), categoryId);
            }
        }

        Map<String, LinkTarget> targets = new LinkedHashMap<>();
        resolve(articleOf, targets, "articles", ids -> {
            Map<Integer, LinkTarget> found = new HashMap<>();
            for (Article article : articleRepository.findPublishedCoversByIdIn(ids)) {
                String filename = article.coverFilename();
                found.put(article.getId(), new LinkTarget(filename == null ? null : IMAGE_PREFIX + filename,
                        titles(article.getTitles(), article::titleIn)));
            }
            return found;
        });
        if (!pageOf.isEmpty()) {
            Map<String, LinkTarget> bySlug = new HashMap<>();
            try {
                for (Page standing : pageRepository.findByPublishedTrueOrderBySortOrderAscSlugAsc()) {
                    LinkTarget target = new LinkTarget(null, titles(standing.getTitles(), standing::rawTitle));
                    if (standing.getSlug() != null) {
                        bySlug.put(standing.getSlug().toLowerCase(Locale.ROOT), target);
                    }
                }
            } catch (DataAccessException e) {
                log.error("Could not read the standing pages for /social; their links show untranslated: {}",
                        e.getMessage());
            }
            pageOf.forEach((linkId, slug) -> putIfFound(targets, linkId, bySlug.get(slug)));
        }
        resolve(categoryOf, targets, "categories", ids -> {
            Map<Integer, LinkTarget> found = new HashMap<>();
            for (Category category : categoryRepository.findAllById(ids)) {
                found.put(category.getId(), new LinkTarget(null,
                        titles(category.getNames(), lang -> categoryName(category, lang))));
            }
            return found;
        });
        return targets;
    }

    /** One id-keyed lookup, its failure contained: see {@link #linkTargets}. */
    private void resolve(Map<String, Integer> idOf, Map<String, LinkTarget> into, String kind,
                         Function<Set<Integer>, Map<Integer, LinkTarget>> lookup) {
        if (idOf.isEmpty()) {
            return;
        }
        Map<Integer, LinkTarget> found;
        try {
            found = lookup.apply(new HashSet<>(idOf.values()));
        } catch (DataAccessException e) {
            log.error("Could not read the linked {} for /social; their links show untranslated: {}",
                    kind, e.getMessage());
            return;
        }
        idOf.forEach((linkId, id) -> putIfFound(into, linkId, found.get(id)));
    }

    private static void putIfFound(Map<String, LinkTarget> into, String linkId, LinkTarget target) {
        if (target != null) {
            into.put(linkId, target);
        }
    }

    /**
     * A category's name in exactly {@code lang}: the {@code names} entry, and the scalar {@code name}
     * for Bulgarian -- what {@code WebArticleService} heads the {@code /list/{id}} page with, so the
     * link reads like the page it opens.
     */
    private static String categoryName(Category category, String lang) {
        for (TextItem item : category.getNames() == null ? List.<TextItem>of() : category.getNames()) {
            if (item != null && lang.equalsIgnoreCase(item.getLang())
                    && item.getText() != null && !item.getText().isBlank()) {
                return item.getText();
            }
        }
        return LanguageChain.original().equals(lang) ? category.getName() : null;
    }

    /**
     * Every language a target has a title in -- Bulgarian, plus each language of its stored
     * translations -- through its own "title in exactly this language" rule, as plain text.
     */
    private static Map<String, String> titles(List<TextItem> stored, Function<String, String> titleIn) {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(LanguageChain.original());
        for (TextItem item : stored == null ? List.<TextItem>of() : stored) {
            String code = item == null ? null : LanguageChain.normalise(item.getLang());
            if (code != null) {
                codes.add(code);
            }
        }
        Map<String, String> titles = new LinkedHashMap<>();
        for (String code : codes) {
            String text = SocialView.plain(titleIn.apply(code));
            if (text != null && !text.isEmpty()) {
                titles.put(code, text);
            }
        }
        return titles;
    }

    /**
     * An image that has since been deleted from the library resolves to {@code null}, and the page
     * falls back to the logo rather than rendering a broken {@code <img>}.
     */
    private String avatarPath(Integer imageId) {
        if (imageId == null) {
            return null;
        }
        return imageMediaRepository.findById(imageId)
                .map(image -> image.getFilename())
                .filter(name -> name != null && !name.isBlank())
                .map(name -> IMAGE_PREFIX + name)
                .orElse(null);
    }
}
