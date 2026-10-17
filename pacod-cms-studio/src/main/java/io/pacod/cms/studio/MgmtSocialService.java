package io.pacod.cms.studio;

import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.core.util.SocialTargets;
import io.pacod.cms.studio.form.SocialLinkForm;
import io.pacod.cms.studio.form.SocialPageForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Writes {@code /social}'s one document. Every operation loads it, changes one thing and saves it
 * -- the read-modify-write {@code MgmtBannerService} does, never a replace -- so a profile save
 * cannot touch a link and a link save cannot touch another link.
 *
 * <h2>The document is created on the first write</h2>
 * There is no bootstrap seed: the page is useless until someone writes its bio, so until then
 * {@code /social} answers 404 and {@link #load()} hands the screen an unsaved default. That
 * default is <b>unpublished</b> and carries an icon for each profile in the website's settings,
 * so the operator starts from something rather than from nothing.
 *
 * <h2>Link ids</h2>
 * Generated here ({@link SocialPage#newLinkId}) and never taken from a form; see
 * {@code SocialLink} for why the shape and the permanence both matter.
 *
 * <h2>No {@code @CacheEvict}</h2>
 * The public page is read by another JVM; its 60 s TTL is the only invalidation there is, the
 * arrangement {@code MgmtBannerService}'s javadoc describes. The screen says so after a save.
 */
@Slf4j
@Service
public class MgmtSocialService {

    /**
     * The id of the {@code n}-th starting icon (from 1) of a page that has never been saved -- one
     * per profile in the website's settings whose network names a {@link SocialIcon}.
     *
     * <p>The ids are <b>fixed</b>, not drawn from {@link SocialPage#newLinkId}, and that is
     * load-bearing. Until the first save {@link #load()} builds this default afresh on every
     * request, so a random id rendered on the screen would no longer exist by the time the
     * operator pressed ↑ on that row -- the move would 404. They have the generated shape
     * ({@code [a-z][a-z0-9]{7}}), so they are safe keys like any other.
     */
    static String starterLinkId(int n) {
        return "profile" + n;
    }

    /** At most this many starting icons: {@link #starterLinkId} keeps the id at eight characters. */
    static final int MAX_STARTER_LINKS = 9;

    private final SocialPageRepository repository;
    private final ImageMediaRepository imageRepository;
    private final ArticleRepository articleRepository;
    private final SiteSettingsRepository siteSettingsRepository;
    private final RandomGenerator random;

    /**
     * The article a link points at, as the console's link list shows it: its title, and the public
     * path of its cover -- the first <em>visible</em> image, the one the site itself uses as the
     * article's share card and list thumbnail -- or {@code null} when it has none.
     */
    public record ArticleThumb(int articleId, String title, String imagePath) {
    }

    @Autowired
    public MgmtSocialService(SocialPageRepository repository, ImageMediaRepository imageRepository,
                             ArticleRepository articleRepository, SiteSettingsRepository siteSettingsRepository) {
        this(repository, imageRepository, articleRepository, siteSettingsRepository, new SecureRandom());
    }

    MgmtSocialService(SocialPageRepository repository, ImageMediaRepository imageRepository,
                      ArticleRepository articleRepository, SiteSettingsRepository siteSettingsRepository,
                      RandomGenerator random) {
        this.repository = repository;
        this.imageRepository = imageRepository;
        this.articleRepository = articleRepository;
        this.siteSettingsRepository = siteSettingsRepository;
        this.random = random;
    }

    /**
     * The website's settings, or empty ones when there are none or they cannot be read -- the
     * default page and the printed address degrade, they do not fail the screen.
     */
    public SiteSettings siteSettings() {
        try {
            return siteSettingsRepository.findById(SiteSettings.ID).orElseGet(SiteSettings::new);
        } catch (RuntimeException e) {
            log.error("Could not read the site settings for /social: {}", e.getMessage());
            return new SiteSettings();
        }
    }

    /** The public address of {@code /social}, absolute when the website has a base URL. */
    public String publicUrl() {
        return siteSettings().baseUrlOrEmpty() + SocialPage.PUBLIC_PATH;
    }

    /** The stored page, or the unsaved default -- see the class javadoc. */
    public SocialPage load() {
        return repository.findById(SocialPage.ID).orElseGet(this::defaults);
    }

    /** Whether the page has ever been saved; the screen says "not created yet" until it has. */
    public boolean exists() {
        return repository.existsById(SocialPage.ID);
    }

    /** The library image chosen as avatar, for the preview; empty for the logo or a deleted image. */
    public Optional<ImageMedia> avatar(SocialPage page) {
        return page.getAvatarImageId() == null ? Optional.empty() : imageRepository.findById(page.getAvatarImageId());
    }

    /**
     * The articles behind the links that point at one, keyed by link id -- the thumbnails beside
     * those links in the console's lists. Read on every render rather than stored on the link, so
     * a new cover chosen in the article editor shows here without anyone touching the link.
     *
     * <p>One query for every article link on the page, through a projection that skips the
     * authors and categories every {@code Article} would otherwise eager-load. A link whose
     * article has since been deleted simply has no entry, and a failed read degrades to none at
     * all: the list without pictures is still the list.
     */
    public Map<String, ArticleThumb> articleThumbs(Collection<SocialLink> links) {
        Map<String, Integer> articleOf = new LinkedHashMap<>();
        Set<String> ownHosts = siteSettings().ownHosts();
        for (SocialLink link : links == null ? List.<SocialLink>of() : links) {
            Integer articleId = SocialTargets.articleIdOf(link.getUrl(), ownHosts);
            if (articleId != null && link.getId() != null) {
                articleOf.put(link.getId(), articleId);
            }
        }
        if (articleOf.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Article> articles = new HashMap<>();
        try {
            for (Article article : articleRepository.findCoversByIdIn(new HashSet<>(articleOf.values()))) {
                articles.put(article.getId(), article);
            }
        } catch (DataAccessException e) {
            log.error("Could not read the articles behind the /social links: {}", e.getMessage());
            return Map.of();
        }

        Map<String, ArticleThumb> thumbs = new LinkedHashMap<>();
        articleOf.forEach((linkId, articleId) -> {
            Article article = articles.get(articleId);
            if (article != null) {
                String filename = article.coverFilename();
                String cover = filename == null ? null : MgmtImageService.PUBLIC_PREFIX + filename;
                thumbs.put(linkId, new ArticleThumb(articleId, plain(article.titleIn(LanguageChain.original())), cover));
            }
        });
        return thumbs;
    }

    /* ---------------------------------------------------------------- the profile */

    public SocialPage saveProfile(SocialPageForm form) {
        return saveProfile(form, true);
    }

    /** {@link #saveProfile(SocialPageForm)}; without {@code content.publish} whether /social is live stays as it was. */
    public SocialPage saveProfile(SocialPageForm form, boolean mayPublish) {
        String lang = language(form.getLang());

        if (LanguageChain.original().equals(lang) && isBlank(form.getName())) {
            throw new MgmtOperationException("pages.social.error.nameRequired");
        }
        if (form.getAvatarImageId() != null && !imageRepository.existsById(form.getAvatarImageId())) {
            throw new MgmtOperationException("pages.social.error.noImage", form.getAvatarImageId());
        }

        SocialPage page = load();
        page.putName(lang, form.getName());
        page.putBio(lang, form.getBio());
        page.putLatestHeading(lang, form.getLatestHeading());
        if (mayPublish) {
            page.setPublished(form.isPublished());
        }
        page.setAvatarImageId(form.getAvatarImageId());
        page.setShowLatestArticles(form.isShowLatestArticles());
        page.setLatestArticlesCount(Math.clamp(form.getLatestArticlesCount(), SocialPage.MIN_LATEST, SocialPage.MAX_LATEST));
        return save(page);
    }

    /* ---------------------------------------------------------------- the links */

    public SocialLink createLink(SocialLinkForm form) {
        SocialPage page = load();
        SocialLink link = new SocialLink(page.newLinkId(random));
        apply(link, form);
        page.getLinks().add(link);
        save(page);
        return link;
    }

    public void saveLink(String id, SocialLinkForm form) {
        SocialPage page = load();
        apply(require(page, id), form);
        save(page);
    }

    /**
     * Swaps the link with the nearest one of the <b>same placement</b> in {@code delta}'s
     * direction. Icons and buttons share one list but are two rows on the page, so moving a
     * button past an icon would change nothing a reader can see and make the next press look
     * broken. A move at either end is a no-op, not an error.
     */
    public void moveLink(String id, int delta) {
        SocialPage page = load();
        List<SocialLink> links = page.getLinks();
        SocialLink link = require(page, id);
        int from = links.indexOf(link);
        int step = delta < 0 ? -1 : 1;

        for (int to = from + step; to >= 0 && to < links.size(); to += step) {
            if (links.get(to).getPlacement() == link.getPlacement()) {
                links.set(from, links.get(to));
                links.set(to, link);
                save(page);
                return;
            }
        }
    }

    public boolean toggleLink(String id) {
        SocialPage page = load();
        SocialLink link = require(page, id);
        link.setActive(!link.isActive());
        save(page);
        return link.isActive();
    }

    /**
     * Removes the link. Its clicks stay in the traffic buckets -- they happened -- and the
     * statistics screen lists them under «изтрита връзка» rather than dropping them, so a period's
     * total does not shrink because someone tidied the page.
     */
    public void deleteLink(String id) {
        SocialPage page = load();
        page.getLinks().remove(require(page, id));
        save(page);
    }

    public Optional<SocialLink> findLink(String id) {
        return load().link(id);
    }

    /* ---------------------------------------------------------------- internals */

    private void apply(SocialLink link, SocialLinkForm form) {
        validate(form);
        link.setPlacement(form.getPlacement());
        link.setIcon(form.getIcon());
        link.putTitle(language(form.getLang()), form.getTitle());
        link.setUrl(form.getUrl().strip());
        link.setActive(form.isActive());
        link.setActiveFrom(form.getActiveFrom());
        link.setActiveTo(form.getActiveTo());

        // A link to one of the website's articles must name an article that exists -- and when
        // its text is left blank, the article's title is its text. Pasting the article's address
        // is then the whole of adding it.
        Integer articleId = SocialTargets.articleIdOf(link.getUrl(), siteSettings().ownHosts());
        if (articleId != null) {
            String articleTitle = articleTitle(articleId);
            if (link.getPlacement() == SocialLinkPlacement.BUTTON && link.rawTitle(LanguageChain.original()) == null
                    && articleTitle != null && !articleTitle.isBlank()) {
                link.putTitle(LanguageChain.original(), articleTitle);
            }
        }

        // A link is read by its text. Checked after the write, against the link, because the
        // original-language title may have been saved from another tab: a German save must not be
        // refused for a field it never showed.
        if (link.getPlacement() == SocialLinkPlacement.BUTTON && link.rawTitle(LanguageChain.original()) == null) {
            throw new MgmtOperationException("pages.social.error.linkTextRequired");
        }
    }

    private static void validate(SocialLinkForm form) {
        if (!SocialTargets.isValidTarget(form.getUrl())) {
            throw new MgmtOperationException("pages.social.error.badUrl");
        }
        if (form.getPlacement() == SocialLinkPlacement.ICON && form.getIcon() == null) {
            throw new MgmtOperationException("pages.social.error.iconRequired");
        }
        if (form.getActiveFrom() != null && form.getActiveTo() != null
                && form.getActiveTo().isBefore(form.getActiveFrom())) {
            throw new MgmtOperationException("pages.social.error.windowReversed");
        }
    }

    /**
     * The title of article {@code id}, as plain text; refuses an id with no article behind it.
     * {@code null} when the database cannot be read -- the save then goes ahead on what the
     * operator typed rather than being refused for a fault that is not theirs.
     */
    private String articleTitle(int id) {
        List<Article> found;
        try {
            found = articleRepository.findTitlesByIdIn(List.of(id));
        } catch (DataAccessException e) {
            log.error("Could not read article {} for a /social link: {}", id, e.getMessage());
            return null;
        }
        if (found.isEmpty()) {
            throw new MgmtOperationException("pages.social.error.noArticle", id);
        }
        return plain(found.getFirst().titleIn(LanguageChain.original()));
    }

    /** Article titles can carry markup; a link's text and the list show plain text. */
    static String plain(String html) {
        if (html == null) {
            return null;
        }
        // The class includes U+00A0 on purpose: &nbsp; decodes to a no-break space, and \s alone misses it.
        String text = HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", " "))
                .replaceAll("[\\s\\u00A0]+", " ").strip();
        return text.isEmpty() ? null : text;
    }

    private SocialPage save(SocialPage page) {
        page.setLastModifiedOn(LocalDateTime.now());
        return repository.save(page);
    }

    private static SocialLink require(SocialPage page, String id) {
        return page.link(id).orElseThrow(() -> new MgmtNotFoundException("pages.social.error.linkNotFound", id));
    }

    /** A bare lowercase code; a form that lost its language writes the original, never nothing. */
    private static String language(String lang) {
        String code = LanguageChain.normalise(lang);
        return code != null && code.matches("[a-z]{2,3}") ? code : LanguageChain.original();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The page a website starts from: named after the website, its tagline as the bio, and one
     * icon per profile in its settings -- unpublished, so nothing reaches readers until an
     * operator has looked at it.
     */
    private SocialPage defaults() {
        SiteSettings settings = siteSettings();
        List<String> chain = List.of(LanguageChain.original());
        SocialPage page = new SocialPage();
        page.setPublished(false);
        page.putName(LanguageChain.original(), settings.name(chain));
        page.putBio(LanguageChain.original(), settings.tagline(chain));
        int n = 0;
        for (SiteSettings.SocialProfile profile : settings.getSocialProfiles()) {
            SocialIcon icon = iconFor(profile.getNetwork());
            if (icon == null || !SocialTargets.isValidTarget(profile.getUrl()) || n >= MAX_STARTER_LINKS) {
                continue;
            }
            SocialLink link = new SocialLink(starterLinkId(++n));
            link.setPlacement(SocialLinkPlacement.ICON);
            link.setIcon(icon);
            link.setUrl(profile.getUrl().strip());
            page.getLinks().add(link);
        }
        return page;
    }

    /** The icon a profile's network label names, ignoring case; {@code null} for none. */
    static SocialIcon iconFor(String network) {
        if (network == null || network.isBlank()) {
            return null;
        }
        try {
            return SocialIcon.valueOf(network.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
