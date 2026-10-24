package io.pacod.cms.core.seed;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.data.TextItems;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.core.repository.TagRepository;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.core.tenant.TenantContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The demo content: one of the installation's {@link Demo}s -- "Demo Journal" ({@value #SEED}) or
 * "Space travels" ({@code demo/demo-space.json}) -- sections, authors, about twenty published
 * articles with pictures and some translations, tags, a "Sections" menu group and a link page, and
 * where the seed has them the website's taglines and a theme -- written into a website <b>only when
 * someone asks for it</b>, never on its own. The methods without a {@link Demo} mean the journal.
 *
 * <h2>Not a {@link SiteSeedStep}</h2>
 * The seed steps run on every re-provisioning ({@code SiteProvisioner.reconcile()}); a step that
 * wrote sample articles "while the collection is empty" would fill a website whose operator had
 * just deleted them. This is called once, by whoever created the website with demo content: the
 * new-website form's checkbox, {@code --create-site --demo}, the platform's demo website, or
 * {@code make seed}. In {@code core.seed}, which only the studio scans: the site never writes it.
 *
 * <h2>The rules it keeps</h2>
 * <ul>
 *   <li><b>Only into a website with no articles</b> ({@link Refusal#HAS_ARTICLES}). Everything else
 *       is written only while absent, by its fixed id (or, for a picture, its file name), and the
 *       articles go last -- so a run that died halfway is finished by running it again.</li>
 *   <li><b>In the website's own language.</b> An article's scalar title, description and body are
 *       in {@code original()}, its translations in the lists beside them. The seed is complete in
 *       the languages {@link #languages()} names; any other content language is refused
 *       ({@link Refusal#LANGUAGE}) rather than given English text under another language's code.</li>
 *   <li><b>Every article is {@code PUBLISHED}</b> -- the public site shows nothing else -- dated
 *       over the last weeks so the most-read list has something to rank, a newer article with a
 *       higher id.</li>
 *   <li><b>The pictures are drawn now</b> ({@link DemoImages}) at the website's required size and
 *       stored through {@link ImageService}, under its rules and in its folder, as an upload is.</li>
 *   <li><b>Link ids are generated</b> ({@link SocialPage#newLinkId}), never written in the seed.</li>
 * </ul>
 */
@Slf4j
@Component
public class DemoContent {

    public static final String SEED = "demo/demo-journal.json";

    /** The demos there are: an id the forms and commands name, and the seed it reads. */
    public enum Demo {
        JOURNAL("journal", SEED),
        SPACE("space", "demo/demo-space.json");

        private final String id;
        private final String resource;

        Demo(String id, String resource) {
            this.id = id;
            this.resource = resource;
        }

        public String id() {
            return id;
        }

        public String resource() {
            return resource;
        }

        /** The demo named {@code id}, case ignored; blank, {@code true} or {@code 1} mean the journal. */
        public static java.util.Optional<Demo> of(String id) {
            String v = id == null ? "" : id.strip().toLowerCase(java.util.Locale.ROOT);
            if (v.isEmpty() || v.equals("true") || v.equals("1")) {
                return java.util.Optional.of(JOURNAL);
            }
            for (Demo demo : values()) {
                if (demo.id.equals(v)) {
                    return java.util.Optional.of(demo);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** In a promotion's text, the website's name. */
    public static final String NAME = "{name}";

    /** Every demo picture's file name starts with this. */
    public static final String IMAGE_PREFIX = "demo-";

    /** Why demo content was not written. */
    public enum Refusal {
        /** The seed has no complete text in the website's content language. */
        LANGUAGE,
        /** The website already has articles: demo content goes only into an empty one. */
        HAS_ARTICLES
    }

    /** Thrown before anything is written. */
    public static final class Refused extends RuntimeException {

        private final Refusal reason;

        public Refused(Refusal reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    /** What was written; zero for what was already there. */
    public record Result(int articles, int images, int categories, int authors, int tags,
                         boolean menuGroup, boolean socialPage) {
    }

    private io.pacod.cms.core.repository.SiteSettingsRepository siteSettings;
    private io.pacod.cms.core.repository.ThemeRepository themes;

    /**
     * Where a seed's taglines and theme go; optional, so the hand-built instances of the tests and
     * of a seed that has neither need none.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useSiteAndTheme(io.pacod.cms.core.repository.SiteSettingsRepository siteSettings,
                                io.pacod.cms.core.repository.ThemeRepository themes) {
        this.siteSettings = siteSettings;
        this.themes = themes;
    }

    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final AuthorRepository authors;
    private final ImageMediaRepository images;
    private final TagRepository tags;
    private final MenuRepository menus;
    private final SocialPageRepository socialPages;
    private final ImageService imageService;
    private final ObjectMapper objectMapper;

    private final Map<Demo, Seed> seeds = new java.util.concurrent.ConcurrentHashMap<>();

    public DemoContent(ArticleRepository articles, CategoryRepository categories, AuthorRepository authors,
                       ImageMediaRepository images, TagRepository tags, MenuRepository menus,
                       SocialPageRepository socialPages, ImageService imageService, ObjectMapper objectMapper) {
        this.articles = articles;
        this.categories = categories;
        this.authors = authors;
        this.images = images;
        this.tags = tags;
        this.menus = menus;
        this.socialPages = socialPages;
        this.imageService = imageService;
        this.objectMapper = objectMapper;
    }

    /** The journal's seed, read once. */
    public Seed seed() {
        return seed(Demo.JOURNAL);
    }

    /** A demo's seed, read once. */
    public Seed seed(Demo demo) {
        return seeds.computeIfAbsent(demo, d -> {
            try (InputStream in = new ClassPathResource(d.resource()).getInputStream()) {
                return objectMapper.readValue(in, Seed.class);
            } catch (IOException | RuntimeException e) {
                // Jackson 3's parse failures are unchecked: catching IOException alone would let a
                // malformed seed escape as something nobody reads as "the seed is broken".
                throw new IllegalStateException("Could not read " + d.resource() + ": " + e.getMessage(), e);
            }
        });
    }

    /** The content languages demo content can be written in: those the seed has every text in. */
    public Set<String> languages() {
        return languages(Demo.JOURNAL);
    }

    public Set<String> languages(Demo demo) {
        return completeLanguages(seed(demo));
    }

    public boolean supports(String language) {
        return supports(Demo.JOURNAL, language);
    }

    public boolean supports(Demo demo, String language) {
        return language != null && languages(demo).contains(LanguageChain.normalise(language));
    }

    /**
     * Writes the demo content into the website bound in {@link TenantContext}, which must be
     * {@code website}.
     *
     * @throws Refused               before anything is written, when the website cannot take it
     * @throws IllegalStateException when a picture cannot be stored; what was written stays, and
     *                               running it again finishes the job
     */
    public Result install(SiteSeedStep.Website website) {
        return install(Demo.JOURNAL, website);
    }

    /** {@link #install(SiteSeedStep.Website)}, of the demo named. */
    public Result install(Demo demo, SiteSeedStep.Website website) {
        String bound = TenantContext.current().siteId();
        if (!bound.equals(website.id())) {
            throw new IllegalStateException("Demo content for " + website.id() + " while " + bound + " is bound");
        }
        Seed seed = seed(demo);
        String lang = website.defaultLanguage() == null || website.defaultLanguage().isBlank()
                ? LanguageChain.original() : LanguageChain.normalise(website.defaultLanguage());
        if (!completeLanguages(seed).contains(lang)) {
            throw new Refused(Refusal.LANGUAGE, "Demo content is written in " + completeLanguages(seed)
                    + ", not in " + lang);
        }
        if (articles.count() > 0) {
            throw new Refused(Refusal.HAS_ARTICLES, "Website " + website.id() + " already has articles");
        }

        Map<String, ImageMedia> pictures = new LinkedHashMap<>();
        int newImages = 0;
        for (ImageSeed image : seed.getImages()) {
            String filename = IMAGE_PREFIX + image.getKey() + ".jpg";
            ImageMedia existing = images.findImageMediaByFilename(filename).orElse(null);
            if (existing == null) {
                existing = storePicture(image, filename, lang);
                newImages++;
            }
            pictures.put(image.getKey(), existing);
        }

        int newCategories = 0;
        for (CategorySeed category : seed.getCategories()) {
            if (!categories.existsById(category.getId())) {
                categories.save(new Category(category.getId(), category.getNames().get(lang),
                        translations(category.getNames(), lang), lang));
                newCategories++;
            }
        }

        int newAuthors = 0;
        for (AuthorSeed seedAuthor : seed.getAuthors()) {
            if (!authors.existsById(seedAuthor.getId())) {
                Author author = new Author(seedAuthor.getId());
                author.setFirstname(seedAuthor.getFirstname());
                author.setLastname(seedAuthor.getLastname());
                author.setTitle(seedAuthor.getTitle());
                author.setDescription(seedAuthor.getDescriptions().get(lang));
                author.setDescriptions(translations(seedAuthor.getDescriptions(), lang));
                authors.save(author);
                newAuthors++;
            }
        }

        int newTags = 0;
        for (String name : seed.getArticles().stream().flatMap(a -> a.getTags().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new))) {
            if (!tags.existsById(name)) {
                Map<String, String> byLang = seed.getTags().getOrDefault(name, Map.of());
                tags.save(new Tag(name, new ArrayList<>(translations(byLang, lang))));
                newTags++;
            }
        }

        boolean menuGroup = addSectionsGroup(seed);
        boolean socialPage = addSocialPage(seed, website, lang);
        addSiteTexts(seed);
        addTheme(seed);

        Map<Integer, Category> categoryById = byId(categories.findAllById(
                seed.getCategories().stream().map(CategorySeed::getId).toList()), Category::getId);
        Map<Integer, Author> authorById = byId(authors.findAllById(
                seed.getAuthors().stream().map(AuthorSeed::getId).toList()), Author::getId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        List<Article> written = new ArrayList<>();
        for (ArticleSeed seedArticle : seed.getArticles()) {
            written.add(toArticle(seedArticle, lang, now, categoryById, authorById, pictures));
        }
        articles.saveAll(written);

        Result result = new Result(written.size(), newImages, newCategories, newAuthors, newTags, menuGroup, socialPage);
        log.info("Demo content ({}) in website {}: {}", demo.id(), website.id(), result);
        return result;
    }

    private ImageMedia storePicture(ImageSeed image, String filename, String lang) {
        long limit = imageService.isFileSizeCheckEnabled() ? imageService.getMaxImageBytes() : 0;
        byte[] jpeg = DemoImages.jpeg(DemoImages.draw(image.getKey(), DemoImages.Motif.of(image.getMotif()),
                image.getColours(), imageService.getRequiredWidth(), imageService.getRequiredHeight()), limit);
        ImageMedia stored;
        try {
            stored = imageService.save(filename, "image/jpeg", jpeg, image.getCaptions().get(lang));
        } catch (MediaException e) {
            throw new IllegalStateException("Could not store the demo picture " + filename + ": " + e.getMessage(), e);
        }
        List<TextItem> alts = new ArrayList<>();
        image.getAlts().forEach((code, text) -> alts.addAll(TextItems.put(List.of(), code, text)));
        stored.setAlts(alts);
        stored.setCreatedOn(LocalDateTime.now());
        return images.save(stored);
    }

    private static Article toArticle(ArticleSeed seed, String lang, LocalDateTime now, Map<Integer, Category> categories,
                                     Map<Integer, Author> authors, Map<String, ImageMedia> pictures) {
        Article article = new Article(seed.getId());
        article.setTitle(seed.getTitles().get(lang));
        article.setTitles(translations(seed.getTitles(), lang));
        article.setDescription(seed.getDescriptions().get(lang));
        article.setDescriptions(translations(seed.getDescriptions(), lang));
        article.setText(seed.getBodies().get(lang));
        article.setTexts(translations(seed.getBodies(), lang));
        article.setLang(lang);
        article.setStatus(ArticleStatus.PUBLISHED);
        LocalDateTime date = now.minusDays(seed.getDaysAgo()).withHour(seed.getHour()).withMinute(0);
        if (date.isAfter(now)) {
            // Today's article whose hour has not come yet: yesterday, never in the future.
            date = date.minusDays(1);
        }
        article.setDate(date);
        article.setCreatedOn(date);
        article.setCounter(seed.getCounter());
        article.setCategories(seed.getCategories().stream().map(categories::get).toList());
        article.setAuthors(seed.getAuthors().stream().map(authors::get).toList());
        article.setImages(seed.getImages().stream().map(pictures::get).toList());
        article.setTags(seed.getTags());
        // Last: the setters above stamp lastModifiedOn with the clock.
        article.setLastModifiedOn(date);
        return article;
    }

    /**
     * A "Sections" group in the main menu, linking every demo category -- only when the menu links
     * no category at all, so an operator's own sections are never doubled. Placed after "Latest".
     */
    private boolean addSectionsGroup(Seed seed) {
        Menu main = menus.findById(Menu.MAIN).orElse(null);
        if (main == null || linksACategory(main.getItems())) {
            return false;
        }
        Map<Integer, CategorySeed> byId = byId(seed.getCategories(), CategorySeed::getId);
        MenuItem group = new MenuItem();
        group.setLabels(texts(seed.getMenu().getLabels()));
        group.setKind(MenuLinkKind.NONE);
        group.setColumns(seed.getMenu().getColumns());
        List<MenuItem> children = new ArrayList<>();
        for (Integer id : seed.getMenu().getCategories()) {
            MenuItem child = new MenuItem();
            child.setLabels(texts(byId.get(id).getNames()));
            child.setKind(MenuLinkKind.CATEGORY);
            child.setTarget(String.valueOf(id));
            children.add(child);
        }
        group.setChildren(children);

        List<MenuItem> items = new ArrayList<>(main.getItems() == null ? List.of() : main.getItems());
        int at = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getKind() == MenuLinkKind.LATEST || items.get(i).getKind() == MenuLinkKind.HOME) {
                at = i + 1;
            }
        }
        items.add(at, group);
        main.setItems(items);
        menus.save(main);
        return true;
    }

    private static boolean linksACategory(List<MenuItem> items) {
        for (MenuItem item : items == null ? List.<MenuItem>of() : items) {
            if (item.getKind() == MenuLinkKind.CATEGORY || linksACategory(item.getChildren())) {
                return true;
            }
        }
        return false;
    }

    /** The seed's taglines and descriptions, each list only while the website's is empty. */
    private void addSiteTexts(Seed seed) {
        if (seed.getSite() == null || siteSettings == null) {
            return;
        }
        io.pacod.cms.core.data.SiteSettings site = siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID)
                .orElseGet(io.pacod.cms.core.data.SiteSettings::new);
        boolean changed = false;
        if ((site.getTaglines() == null || site.getTaglines().isEmpty()) && !seed.getSite().getTaglines().isEmpty()) {
            site.setTaglines(texts(seed.getSite().getTaglines()));
            changed = true;
        }
        if ((site.getDescriptions() == null || site.getDescriptions().isEmpty()) && !seed.getSite().getDescriptions().isEmpty()) {
            site.setDescriptions(texts(seed.getSite().getDescriptions()));
            changed = true;
        }
        if (changed) {
            siteSettings.save(site);
        }
    }

    /**
     * The seed's theme, drafted and published, only while the website has no theme of its own;
     * every value checked as the theme editor checks it.
     */
    private void addTheme(Seed seed) {
        if (seed.getTheme() == null || seed.getTheme().isEmpty() || themes == null
                || themes.existsById(io.pacod.cms.core.design.Theme.ID)) {
            return;
        }
        Map<String, String> values = io.pacod.cms.core.design.ThemeCss.clean(seed.getTheme());
        io.pacod.cms.core.design.Theme theme = new io.pacod.cms.core.design.Theme();
        theme.setDraft(new LinkedHashMap<>(values));
        theme.setPublished(new LinkedHashMap<>(values));
        java.time.Instant now = java.time.Instant.now();
        theme.setUpdatedOn(now);
        theme.setPublishedOn(now);
        themes.save(theme);
    }

    /** The link page, published, when the website has none: named after the website. */
    private boolean addSocialPage(Seed seed, SiteSeedStep.Website website, String lang) {
        if (socialPages.existsById(SocialPage.ID)) {
            return false;
        }
        SocialSeed social = seed.getSocial();
        SocialPage page = new SocialPage();
        page.setPublished(true);
        page.putName(lang, website.name());
        social.getBios().forEach(page::putBio);
        social.getLatestHeadings().forEach(page::putLatestHeading);
        page.setShowLatestArticles(true);
        page.setLatestArticlesCount(social.getLatestCount());
        SecureRandom random = new SecureRandom();
        for (LinkSeed seedLink : social.getLinks()) {
            SocialLink link = new SocialLink(page.newLinkId(random));
            link.setPlacement(seedLink.getPlacement() != null ? seedLink.getPlacement() : SocialLinkPlacement.BUTTON);
            link.setIcon(seedLink.getIcon());
            link.setTitles(texts(seedLink.getTitles()));
            link.setUrl(seedLink.getUrl());
            page.getLinks().add(link);
        }
        page.setLastModifiedOn(LocalDateTime.now());
        socialPages.save(page);
        return true;
    }

    /** The languages every text of the seed is written in. */
    static Set<String> completeLanguages(Seed seed) {
        List<Map<String, String>> required = new ArrayList<>();
        seed.getCategories().forEach(c -> required.add(c.getNames()));
        seed.getImages().forEach(i -> {
            required.add(i.getCaptions());
            required.add(i.getAlts());
        });
        seed.getArticles().forEach(a -> {
            required.add(a.getTitles());
            required.add(a.getDescriptions());
            required.add(a.getBodies());
        });
        required.add(seed.getMenu().getLabels());
        required.add(seed.getSocial().getBios());
        required.add(seed.getSocial().getLatestHeadings());
        seed.getSocial().getLinks().forEach(l -> required.add(l.getTitles()));
        if (seed.getPromotions() != null) {
            if (seed.getPromotions().getImage() != null) {
                required.add(seed.getPromotions().getImage().getAlts());
            }
            if (seed.getPromotions().getRotator() != null) {
                seed.getPromotions().getRotator().getSlides().forEach(s -> {
                    required.add(s.getTitles());
                    required.add(s.getAlts());
                });
            }
        }
        Set<String> languages = null;
        for (Map<String, String> texts : required) {
            Set<String> written = texts.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                    .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
            if (languages == null) {
                languages = written;
            } else {
                languages.retainAll(written);
            }
        }
        return languages == null ? Set.of() : Set.copyOf(languages);
    }

    /** Every language but the original, which lives in the scalar field. */
    private static List<TextItem> translations(Map<String, String> byLang, String original) {
        List<TextItem> items = new ArrayList<>();
        byLang.forEach((code, text) -> {
            if (!code.equals(original)) {
                items.addAll(TextItems.put(List.of(), code, text));
            }
        });
        return items;
    }

    /** Every language, the original included: labels and titles have no scalar beside them. */
    private static List<TextItem> texts(Map<String, String> byLang) {
        List<TextItem> items = new ArrayList<>();
        byLang.forEach((code, text) -> items.addAll(TextItems.put(List.of(), code, text)));
        return items;
    }

    private static <T> Map<Integer, T> byId(Iterable<T> items, Function<T, Integer> id) {
        Map<Integer, T> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(id.apply(item), item));
        return map;
    }

    // ------------------------------------------------------------------------------- the JSON shape

    @Data
    public static class Seed {
        private List<CategorySeed> categories = new ArrayList<>();
        private List<AuthorSeed> authors = new ArrayList<>();
        /** Tag name to its translations; the name itself is the tag in every language. */
        private Map<String, Map<String, String>> tags = new LinkedHashMap<>();
        private List<ImageSeed> images = new ArrayList<>();
        private List<ArticleSeed> articles = new ArrayList<>();
        private MenuSeed menu = new MenuSeed();
        private SocialSeed social = new SocialSeed();
        private Promotions promotions;
        /** The website's taglines and descriptions, where it has none; optional. */
        private SiteSeed site;
        /** Theme token values ({@code ThemeToken} ids), drafted and published where there is no theme; optional. */
        private Map<String, String> theme;
    }

    @Data
    public static class SiteSeed {
        private Map<String, String> taglines = new LinkedHashMap<>();
        private Map<String, String> descriptions = new LinkedHashMap<>();
    }

    @Data
    public static class CategorySeed {
        private Integer id;
        private Map<String, String> names = new LinkedHashMap<>();
    }

    @Data
    public static class AuthorSeed {
        private Integer id;
        private String firstname;
        private String lastname;
        private String title;
        private Map<String, String> descriptions = new LinkedHashMap<>();
    }

    @Data
    public static class ImageSeed {
        private String key;
        private String motif;
        private List<String> colours = new ArrayList<>();
        private Map<String, String> captions = new LinkedHashMap<>();
        private Map<String, String> alts = new LinkedHashMap<>();
    }

    @Data
    public static class ArticleSeed {
        private Integer id;
        private int daysAgo;
        private int hour = 9;
        private int counter;
        private List<Integer> categories = new ArrayList<>();
        private List<Integer> authors = new ArrayList<>();
        private List<String> images = new ArrayList<>();
        private List<String> tags = new ArrayList<>();
        private Map<String, String> titles = new LinkedHashMap<>();
        private Map<String, String> descriptions = new LinkedHashMap<>();
        private Map<String, String> bodies = new LinkedHashMap<>();
    }

    @Data
    public static class MenuSeed {
        private Map<String, String> labels = new LinkedHashMap<>();
        private Integer columns;
        private List<Integer> categories = new ArrayList<>();
    }

    @Data
    public static class SocialSeed {
        private Map<String, String> bios = new LinkedHashMap<>();
        private Map<String, String> latestHeadings = new LinkedHashMap<>();
        private int latestCount = 3;
        private List<LinkSeed> links = new ArrayList<>();
    }

    @Data
    public static class LinkSeed {
        private SocialLinkPlacement placement;
        private SocialIcon icon;
        private Map<String, String> titles = new LinkedHashMap<>();
        private String url;
    }

    /**
     * The website's own advertising -- written by the studio, which owns bookings and layouts
     * ({@code StudioDemo}); here because it is part of the one seed. Its texts name the website as
     * {@value #NAME}, replaced by its name when written: a website called "Acme Weekly" advertises
     * itself, not "Demo Journal".
     */
    @Data
    public static class Promotions {
        private PromotionSeed image;
        private RotatorSeed rotator;
    }

    @Data
    public static class PromotionSeed {
        private String name;
        private String key;
        private String motif;
        private List<String> colours = new ArrayList<>();
        private int width;
        private int height;
        /** On the website's own origin; the booking needs an absolute address, made when written. */
        private String path;
        private Map<String, String> alts = new LinkedHashMap<>();
    }

    @Data
    public static class RotatorSeed {
        private String id;
        private String title;
        private String skeleton;
        private int rotationMs = 6000;
        private List<SlideSeed> slides = new ArrayList<>();
    }

    @Data
    public static class SlideSeed {
        private String key;
        private String motif;
        private List<String> colours = new ArrayList<>();
        private String path;
        private Map<String, String> titles = new LinkedHashMap<>();
        private Map<String, String> alts = new LinkedHashMap<>();
    }

}
