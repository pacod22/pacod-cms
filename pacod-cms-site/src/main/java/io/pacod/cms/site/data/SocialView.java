package io.pacod.cms.site.data;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.util.SocialTargets;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything {@code social.html} renders, resolved: no SpEL in the template chooses a language,
 * builds a URL or filters a schedule.
 *
 * <h2>Every outbound href carries the source</h2>
 * The network a visit came from is decided when {@code /social} is rendered, and only then: the
 * click that follows arrives with {@code /social} itself as its {@code Referer}, so it cannot be
 * classified on its own. So every link the page emits -- the tiles, the article tiles and the
 * language switcher -- carries {@code src=}, and that is the entire mechanism. No cookie, no
 * session: the public application is {@code STATELESS}. {@code PublicTemplateRenderTest} pins it,
 * because the server cannot see an href that lost it.
 *
 * <h2>Languages resolve per item</h2>
 * Unlike a standing page, where the body decides one served language for every field, a link page
 * is a list of independent short labels, and a link added yesterday in Bulgarian only must not
 * drag the whole page back to Bulgarian. Each label resolves down the chain on its own and carries
 * a {@code lang} attribute when it landed somewhere other than the page's own language.
 */
public record SocialView(
        String htmlLang,
        String name,
        String nameLang,
        String bio,
        String bioLang,
        String avatarPath,
        String ogImagePath,
        List<Tile> icons,
        List<Tile> buttons,
        String latestHeading,
        String latestHeadingLang,
        List<ArticleTile> articles,
        List<LanguageChoice> languages,
        String sourceKey) {

    public static final String PATH = "/social";

    /**
     * One link: a network icon or a full-width button. {@code labelLang} is null when it is the
     * page's. {@code thumbPath} is the cover of the article a button points at, shown in place of
     * its icon, or null -- always null in the icon row, where a photograph in a 44px circle beside
     * five brand marks would read as a broken icon.
     */
    public record Tile(String id, String href, String label, String labelLang, String symbol, String thumbPath) {
    }

    /** One of the latest articles. {@code imagePath} is null for an article with no visible image. */
    public record ArticleTile(int id, String href, String title, String titleLang, String imagePath) {
    }

    public record LanguageChoice(String code, String name, String href, boolean current) {
    }

    public boolean hasLanguageChoice() {
        return languages.size() > 1;
    }

    /**
     * @param requested    the language the reader asked for -- {@code ?lang}, then the cookie, then
     *                     Bulgarian -- already normalised
     * @param linkTargets  the site content behind each link that points at some, keyed by link id
     * @param latest       the newest articles, or an empty list when the block is off
     * @param siteLanguages the {@code language} collection, for native names and order
     */
    public static SocialView of(SocialPage page, String avatarPath, Map<String, LinkTarget> linkTargets,
                                String requested, LocalDateTime now, List<Article> latest,
                                List<Language> siteLanguages, SocialSource source,
                                Collection<String> ownHosts) {
        List<String> chain = LanguageChain.forRequest(requested);
        String key = (source == null ? SocialSource.DIRECT : source).key();

        String name = page.name(chain);
        String nameLang = page.nameLang(chain);
        String htmlLang = nameLang != null ? nameLang : requested;

        Map<String, LinkTarget> linked = linkTargets == null ? Map.of() : linkTargets;
        List<Tile> icons = tiles(page.liveLinks(SocialLinkPlacement.ICON, now), chain, requested, htmlLang, key,
                linked, false, ownHosts);
        List<Tile> buttons = tiles(page.liveLinks(SocialLinkPlacement.BUTTON, now), chain, requested, htmlLang, key,
                linked, true, ownHosts);

        String heading = page.latestHeading(chain);
        String headingLang = page.latestHeadingLang(chain);
        if (heading == null) {
            // No heading written for this block: the template supplies the chrome's own wording,
            // in the reader's language, from the message bundle.
            headingLang = null;
        }

        // The latest articles' titles resolve by the rule the article LINKS above them use --
        // Article.titleIn down the reader's chain -- so the two blocks cannot disagree about which
        // language an article is shown in. ListItem's rule, which the listings use, tries English
        // before the Bulgarian source, and would show a Bulgarian reader an English title.
        List<ArticleTile> articles = new ArrayList<>();
        for (Article article : latest) {
            if (article == null || article.getId() == null) {
                continue;
            }
            String title = null;
            String titleLang = null;
            for (String lang : chain) {
                String candidate = plain(article.titleIn(lang));
                if (candidate != null && !candidate.isEmpty()) {
                    title = candidate;
                    titleLang = lang;
                    break;
                }
            }
            String cover = article.coverFilename();
            articles.add(new ArticleTile(article.getId(),
                    href(PATH + "/read/" + article.getId(), key, requested),
                    title != null ? title : "№ " + article.getId(),
                    differs(titleLang, htmlLang),
                    cover == null ? null : "/assets/img/articles/" + cover));
        }

        return new SocialView(
                htmlLang,
                // Null when the profile has no name in any language: the template names the
                // page after the website instead, from the site settings.
                name,
                differs(nameLang, htmlLang),
                page.bio(chain),
                differs(page.bioLang(chain), htmlLang),
                avatarPath,
                // Null without an avatar: the template falls back to the website's share image.
                avatarPath,
                icons,
                buttons,
                heading,
                differs(headingLang, htmlLang),
                articles,
                languages(page.writtenIn(), siteLanguages, requested, key),
                key);
    }

    private static List<Tile> tiles(List<SocialLink> links, List<String> chain, String requested, String htmlLang,
                                    String key, Map<String, LinkTarget> articles, boolean withCovers, Collection<String> ownHosts) {
        List<Tile> tiles = new ArrayList<>();
        for (SocialLink link : links) {
            LinkTarget target = articles.get(link.getId());
            String[] label = label(link, target, chain);
            boolean toSiteContent = SocialTargets.isSiteContent(link.getUrl(), ownHosts);
            tiles.add(new Tile(link.getId(),
                    // A link to the site's own content carries the reader's language, so the
                    // redirect opens it in that language -- the /social/read/{id} tiles below have
                    // always done so.
                    href(PATH + "/go/" + link.getId(), key, toSiteContent ? requested : null),
                    label[0],
                    differs(label[1], htmlLang),
                    link.getIcon() == null ? null : link.getIcon().symbol(),
                    withCovers && target != null ? target.cover() : null));
        }
        return tiles;
    }

    /**
     * A link's text and the language it is in. Down the reader's chain, each language asks the
     * link first and then what it points at -- an article, a standing page, a category: text the
     * operator wrote for a language always wins, and where they wrote none the target's own title
     * in that language stands in. So a link added in Bulgarian reads in German for a German reader
     * wherever its article or page has a German title, and the Bulgarian stored on the link never
     * pre-empts a translation the site already has. A link to anywhere else -- another site, a PDF
     * -- has nothing to borrow, and falls down the chain to English, then Bulgarian.
     */
    private static String[] label(SocialLink link, LinkTarget target, List<String> chain) {
        for (String lang : chain) {
            String own = link.rawTitle(lang);
            if (own != null) {
                return new String[]{own, lang};
            }
            String targetTitle = target == null ? null : target.titleIn(lang);
            if (targetTitle != null) {
                return new String[]{targetTitle, lang};
            }
        }
        return new String[]{link.label(chain), null};
    }

    /**
     * Every language the site offers -- the language collection, in its order and named natively --
     * then any the page itself is written in that the collection no longer lists, under its code
     * (the text did not disappear because a row did: {@code Page.writtenIn}'s rule).
     *
     * <p>Every site language, not only the profile's: the links and the latest articles take their
     * titles from the articles and pages they open, which are translated whether or not anyone
     * wrote the bio in that language. Offering only the profile's languages would leave a reader
     * unable to pick the German titles the page could show them.
     *
     * <p>The one marked current is the language the reader <em>asked</em> for, which is not
     * always the page's {@code lang}: a German reader of a profile written in Bulgarian and English
     * is on an English page with German titles, and chose German.
     */
    private static List<LanguageChoice> languages(List<String> written, List<Language> siteLanguages,
                                                  String requested, String key) {
        List<LanguageChoice> choices = new ArrayList<>();
        List<String> listed = new ArrayList<>();
        for (Language language : siteLanguages == null ? List.<Language>of() : siteLanguages) {
            String code = language.getCode() == null ? null : language.getCode().toLowerCase(java.util.Locale.ROOT);
            if (code != null && !listed.contains(code)) {
                listed.add(code);
                String label = language.getNativeName() != null && !language.getNativeName().isBlank()
                        ? language.getNativeName() : code.toUpperCase(java.util.Locale.ROOT);
                choices.add(choice(code, label, requested, key));
            }
        }
        for (String code : written) {
            if (!listed.contains(code)) {
                listed.add(code);
                choices.add(choice(code, code.toUpperCase(java.util.Locale.ROOT), requested, key));
            }
        }
        return choices;
    }

    private static LanguageChoice choice(String code, String label, String requested, String key) {
        return new LanguageChoice(code, label, href(PATH, key, code), code.equals(requested));
    }

    private static String href(String path, String sourceKey, String lang) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path).queryParam("src", sourceKey);
        if (lang != null) {
            builder.queryParam("lang", lang);
        }
        return builder.build().encode().toUriString();
    }

    /** The resolved language, or {@code null} when it is the page's own -- no attribute needed. */
    private static String differs(String lang, String htmlLang) {
        return lang == null || Objects.equals(lang, htmlLang) ? null : lang;
    }

    /**
     * Imported titles can carry markup ({@code article.html} renders them with {@code th:utext});
     * a tile is plain text, so the tags go and the entities are decoded before Thymeleaf escapes
     * the result once.
     */
    public static String plain(String html) {
        if (html == null) {
            return "";
        }
        String text = HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", " "));
        // The class includes U+00A0 on purpose: &nbsp; decodes to a no-break space, and \s alone misses it.
        return text.replaceAll("[\\s\\u00A0]+", " ").strip();
    }
}
