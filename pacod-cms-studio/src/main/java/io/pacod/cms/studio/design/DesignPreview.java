package io.pacod.cms.studio.design;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.PreviewTokens;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.site.StudioSites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The open website's drafts, as its readers would see them: an address on the website's own
 * origin carrying a {@link PreviewTokens} token, which the studio frames.
 *
 * <p>Never the studio's origin: the page runs the website's scripts and banners. The token is
 * minted for the bound website on every render (it lives {@link PreviewTokens#TTL}); the site
 * checks it against the website its host names, renders the draft layouts and theme, counts
 * nothing and tells no third party.
 */
@Slf4j
@Service
public class DesignPreview {

    /** Why there is nothing to frame. */
    public enum Problem {
        /** {@code PACOD_PREVIEW_SECRET} is blank. */
        OFF,
        /** The website has no host to frame it from. */
        NO_ADDRESS,
        /** The page type needs content the website does not have yet (no article, category, page). */
        NOTHING_TO_SHOW
    }

    /**
     * @param set the template set rendered with, or null for the website's own
     * @param src the framed address, or null with a {@code problem}
     */
    public record View(PageType pageType, String set, String src, Problem problem) {
    }

    private final PreviewTokens tokens;
    private final StudioSites sites;
    private final TemplateSetCatalog catalog;
    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final PageRepository pages;

    public DesignPreview(PreviewTokens tokens, StudioSites sites, TemplateSetCatalog catalog,
                         ArticleRepository articles, CategoryRepository categories, PageRepository pages) {
        this.tokens = tokens;
        this.sites = sites;
        this.catalog = catalog;
        this.articles = articles;
        this.categories = categories;
        this.pages = pages;
    }

    /** @param set a template set to preview with; ignored unless this installation has it */
    public View view(PageType pageType, String set) {
        String chosen = set != null && catalog.find(set).isPresent() ? set : null;
        if (!tokens.enabled()) {
            return new View(pageType, chosen, null, Problem.OFF);
        }
        String origin = sites.current().map(StudioSites.SiteView::publicUrl).orElse(null);
        if (origin == null) {
            return new View(pageType, chosen, null, Problem.NO_ADDRESS);
        }
        String path = samplePath(pageType);
        if (path == null) {
            return new View(pageType, chosen, null, Problem.NOTHING_TO_SHOW);
        }
        String token = tokens.mint(TenantContext.current().siteId(), chosen);
        return new View(pageType, chosen, origin + path + (path.contains("?") ? "&" : "?") + "_preview=" + token, null);
    }

    /**
     * Where an article's "Preview" goes: the article on the website's own origin, with a token so
     * that a draft shows too -- the site serves an unpublished article to a valid preview only.
     * Minted now, when the editor clicks, because an editor page stays open longer than a token
     * lives. Without the shared secret it is the public address, where only a published article
     * answers; without a host, nothing.
     *
     * @param lang the language to show it in, kept only when it looks like a code
     */
    public Optional<String> articleUrl(int id, String lang) {
        String origin = sites.current().map(StudioSites.SiteView::publicUrl).orElse(null);
        if (origin == null) {
            return Optional.empty();
        }
        StringBuilder url = new StringBuilder(origin).append("/article/").append(id);
        String separator = "?";
        if (lang != null && lang.matches("[a-z]{2,3}")) {
            url.append(separator).append("lang=").append(lang);
            separator = "&";
        }
        if (tokens.enabled()) {
            url.append(separator).append("_preview=").append(tokens.mint(TenantContext.current().siteId(), null));
        }
        return Optional.of(url.toString());
    }

    /** A page of the type to look at: the newest article, the first category, the first page. */
    String samplePath(PageType pageType) {
        try {
            return switch (pageType) {
                case HOME -> "/";
                case LATEST -> "/latest";
                case SEARCH -> "/search";
                case ARTICLE -> articles.findPublished(PageRequest.of(0, 1)).stream().findFirst()
                        .map(Article::getId).map(id -> "/article/" + id).orElse(null);
                case CATEGORY -> categories.findAll(PageRequest.of(0, 1, Sort.by("id"))).stream().findFirst()
                        .map(Category::getId).map(id -> "/list/" + id).orElse(null);
                case PAGE -> pages.findByPublishedTrueOrderBySortOrderAscSlugAsc().stream().findFirst()
                        .map(p -> "/p/" + UriUtils.encodePathSegment(p.getSlug(), StandardCharsets.UTF_8)).orElse(null);
            };
        } catch (DataAccessException e) {
            log.error("Could not pick a {} page to preview: {}", pageType, e.getMessage());
            return null;
        }
    }

    /** The set's name, for the screen; the id when the catalog has no such set. */
    public String setName(String set) {
        return Optional.ofNullable(set).flatMap(catalog::find).map(m -> m.name()).orElse(set);
    }
}
