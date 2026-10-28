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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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

    /**
     * The demos there are: an id the forms and commands name, and the seed it reads -- with every
     * file it names -- from the classpath.
     */
    public enum Demo implements SeedSource {
        JOURNAL("journal", SEED, null),
        SPACE("space", "demo/demo-space.json", "magazine");

        private final String id;
        private final String resource;
        private final String templateSet;

        Demo(String id, String resource, String templateSet) {
            this.id = id;
            this.resource = resource;
            this.templateSet = templateSet;
        }

        @Override
        public String id() {
            return id;
        }

        public String resource() {
            return resource;
        }

        @Override
        public String seedFile() {
            return resource;
        }

        /**
         * The template set the demo is designed for -- its layouts are written only into a website
         * on that set -- or null for whichever the website has. What a website created FOR the
         * demo is given when nobody names a set ({@code --create-site --demo}, the bootstrap).
         */
        @Override
        public String templateSet() {
            return templateSet;
        }

        @Override
        public boolean external() {
            return false;
        }

        @Override
        public byte[] read(String path) {
            return SeedSource.classpath(path);
        }

        @Override
        public List<String> files(String folder) {
            return SeedSource.classpathFiles(folder);
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

    /** Every demo picture's file name starts with this, unless the seed names its own prefix. */
    public static final String IMAGE_PREFIX = "demo-";

    /** Why demo content was not written. */
    public enum Refusal {
        /** The seed has no complete text in the website's content language. */
        LANGUAGE,
        /** The website already has articles: demo content goes only into an empty one. */
        HAS_ARTICLES,
        /** A seed from outside the jar breaks one of {@link SeedRules}: the message lists them. */
        INVALID
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

    private io.pacod.cms.core.repository.PageRepository pageRepository;
    private io.pacod.cms.core.repository.LanguageRepository languageRepository;
    private io.pacod.cms.core.repository.LayoutRepository layoutRepository;
    private io.pacod.cms.core.repository.JournalIssueRepository issueRepository;
    private io.pacod.cms.core.design.TemplateSetCatalog templateSets;

    /**
     * Where a seed's standing pages, languages, layouts and journal issues go; optional for the
     * same reason as {@link #useSiteAndTheme}: the journal's seed has none of them.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useStandingContent(io.pacod.cms.core.repository.PageRepository pageRepository,
                                   io.pacod.cms.core.repository.LanguageRepository languageRepository,
                                   io.pacod.cms.core.repository.LayoutRepository layoutRepository,
                                   io.pacod.cms.core.repository.JournalIssueRepository issueRepository,
                                   io.pacod.cms.core.design.TemplateSetCatalog templateSets) {
        this.pageRepository = pageRepository;
        this.languageRepository = languageRepository;
        this.layoutRepository = layoutRepository;
        this.issueRepository = issueRepository;
        this.templateSets = templateSets;
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

    /** By source: a demo's for the life of the process, a folder's for the one command that names it. */
    private final Map<SeedSource, Seed> seeds = new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * A seed, read once. A folder's is read strictly: a key the seed shape does not have -- a
     * misspelt {@code categries} -- is refused rather than dropped without a word.
     */
    public Seed seed(SeedSource source) {
        return seeds.computeIfAbsent(source, s -> {
            try {
                byte[] json = s.read(s.seedFile());
                return s.external()
                        ? objectMapper.readerFor(Seed.class)
                                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .readValue(json)
                        : objectMapper.readValue(json, Seed.class);
            } catch (RuntimeException e) {
                // Jackson 3's parse failures are unchecked: catching IOException alone would let a
                // malformed seed escape as something nobody reads as "the seed is broken".
                throw new IllegalStateException("Could not read " + s.seedFile() + " of " + s.id() + ": " + e.getMessage(), e);
            }
        });
    }

    /** The content languages demo content can be written in: those the seed has every text in. */
    public Set<String> languages() {
        return languages(Demo.JOURNAL);
    }

    public Set<String> languages(SeedSource source) {
        return completeLanguages(seed(source));
    }

    public boolean supports(String language) {
        return supports(Demo.JOURNAL, language);
    }

    public boolean supports(SeedSource source, String language) {
        return language != null && languages(source).contains(LanguageChain.normalise(language));
    }

    /**
     * What keeps the seed out of a website whose content language is {@code language}: every
     * {@link SeedRules} problem, or why it could not be read; empty when it may be written. Reads
     * nothing of any website.
     */
    public List<String> check(SeedSource source, String language) {
        Seed seed;
        try {
            seed = seed(source);
        } catch (IllegalStateException e) {
            return List.of(e.getMessage());
        }
        Set<String> standing = new LinkedHashSet<>();
        try {
            PageBootstrap.readSeed(objectMapper).forEach(p -> standing.add(p.getSlug()));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the standing pages; a menu naming one is reported: {}", e.getMessage());
        }
        io.pacod.cms.core.design.TemplateSetManifest set = seed.getLayoutSet() == null ? null
                : (templateSets != null ? templateSets : new io.pacod.cms.core.design.TemplateSetCatalog())
                        .find(seed.getLayoutSet()).orElse(null);
        return SeedRules.check(seed, source, language == null ? LanguageChain.original() : LanguageChain.normalise(language),
                set, standing, LocalDateTime.now());
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

    /**
     * {@link #install(SiteSeedStep.Website)}, of the seed named: a demo, or a folder's -- which is
     * first checked whole ({@link #check}) and refused ({@link Refusal#INVALID}) with every problem
     * it has, before anything is written.
     */
    public Result install(SeedSource source, SiteSeedStep.Website website) {
        String bound = TenantContext.current().siteId();
        if (!bound.equals(website.id())) {
            throw new IllegalStateException("Demo content for " + website.id() + " while " + bound + " is bound");
        }
        Seed seed = seed(source);
        String lang = website.defaultLanguage() == null || website.defaultLanguage().isBlank()
                ? LanguageChain.original() : LanguageChain.normalise(website.defaultLanguage());
        if (!completeLanguages(seed).contains(lang)) {
            throw new Refused(Refusal.LANGUAGE, "Demo content is written in " + completeLanguages(seed)
                    + ", not in " + lang);
        }
        if (source.external()) {
            List<String> problems = check(source, lang);
            if (!problems.isEmpty()) {
                throw new Refused(Refusal.INVALID, "The seed of " + source.id() + " has " + problems.size()
                        + " problem(s): " + String.join("; ", problems));
            }
        }
        if (articles.count() > 0) {
            throw new Refused(Refusal.HAS_ARTICLES, "Website " + website.id() + " already has articles");
        }

        Map<String, ImageMedia> pictures = new LinkedHashMap<>();
        int newImages = 0;
        for (ImageSeed image : seed.getImages()) {
            String filename = seed.getImagePrefix() + image.getKey() + ".jpg";
            ImageMedia existing = images.findImageMediaByFilename(filename).orElse(null);
            if (existing == null) {
                existing = storePicture(source, image, filename, lang);
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

        boolean menuGroup = seed.getMenus().isEmpty() ? addSectionsGroup(seed) : replaceMenus(seed);
        boolean socialPage = addSocialPage(seed, website, lang);
        addSiteTexts(seed, website, lang);
        addTheme(seed);
        addPages(seed, website, lang);
        addLanguages(seed);
        if (seed.isNarrowLanguages()) {
            narrowLanguages(seed);
        }
        addLayouts(seed);
        addIssues(seed, pictures, lang);

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
        log.info("Demo content ({}) in website {}: {}", source.id(), website.id(), result);
        return result;
    }

    private ImageMedia storePicture(SeedSource source, ImageSeed image, String filename, String lang) {
        long limit = imageService.isFileSizeCheckEnabled() ? imageService.getMaxImageBytes() : 0;
        int width = imageService.getRequiredWidth();
        int height = imageService.getRequiredHeight();
        boolean check = imageService.isDimensionCheckEnabled();
        byte[] jpeg;
        if (image.getPhoto() == null) {
            jpeg = DemoImages.jpeg(DemoImages.draw(image.getKey(), DemoImages.Motif.of(image.getMotif()),
                    image.getColours(), width, height), limit);
        } else if (ImageSeed.PAD.equals(image.getFit())) {
            jpeg = DemoPhotos.pad(source.read(image.getPhoto()), width, height, check, limit,
                    DemoPhotos.colour(image.getBackground()));
        } else {
            jpeg = DemoPhotos.fit(source.read(image.getPhoto()), width, height, check, limit,
                    image.getFocusX(), image.getFocusY());
        }
        ImageMedia stored;
        try {
            stored = imageService.save(filename, "image/jpeg", jpeg, caption(image, lang));
        } catch (MediaException e) {
            throw new IllegalStateException("Could not store the demo picture " + filename + ": " + e.getMessage(), e);
        }
        List<TextItem> alts = new ArrayList<>();
        image.getAlts().forEach((code, text) -> alts.addAll(TextItems.put(List.of(), code, text)));
        stored.setAlts(alts);
        stored.setCreatedOn(LocalDateTime.now());
        return images.save(stored);
    }

    /** A picture's caption, its credit after it: a photograph says whose it is wherever it is shown. */
    static String caption(ImageSeed image, String lang) {
        String caption = image.getCaptions().get(lang);
        if (image.getCredit() == null || image.getCredit().isBlank()) {
            return caption;
        }
        return caption == null || caption.isBlank() ? image.getCredit() : caption + " " + image.getCredit();
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
        article.setStatus(seed.getStatus() == null ? ArticleStatus.PUBLISHED : seed.getStatus());
        LocalDateTime date = dated(seed, now);
        article.setDate(date);
        article.setCreatedOn(date);
        article.setCounter(seed.getCounter());
        article.setCategories(seed.getCategories().stream().map(categories::get).toList());
        article.setAuthors(seed.getAuthors().stream().map(authors::get).toList());
        article.setImages(seed.getImages().stream().map(pictures::get).toList());
        if (!seed.getInline().isEmpty()) {
            // Pictures placed inside the body by address: attached, so they are counted as used and
            // never offered for deletion, and hidden, so the gallery does not show them twice.
            article.setHiddenImageIds(seed.getInline().stream().map(key -> pictures.get(key).getId()).toList());
        }
        article.setTags(seed.getTags());
        // Last: the setters above stamp lastModifiedOn with the clock.
        article.setLastModifiedOn(seed.getModified() != null ? seed.getModified() : date);
        return article;
    }

    /**
     * An article's date: the one the seed gives, or {@code daysAgo} at {@code hour} before
     * {@code now} -- a demo's articles are always recent.
     */
    static LocalDateTime dated(ArticleSeed seed, LocalDateTime now) {
        if (seed.getDate() != null) {
            return seed.getDate();
        }
        LocalDateTime date = now.minusDays(seed.getDaysAgo()).withHour(seed.getHour()).withMinute(0);
        if (date.isAfter(now)) {
            // Today's article whose hour has not come yet: yesterday, never in the future.
            date = date.minusDays(1);
        }
        return date;
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

    /**
     * The seed's taglines, descriptions, contact details and profiles, each only while the
     * website's is empty, and its footer note only while the website's is still the "© name"
     * {@code SiteBootstrap} wrote. {@value #NAME} in a text is the website's name.
     */
    private void addSiteTexts(Seed seed, SiteSeedStep.Website website, String lang) {
        if (seed.getSite() == null || siteSettings == null) {
            return;
        }
        SiteSeed texts = seed.getSite();
        io.pacod.cms.core.data.SiteSettings site = siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID)
                .orElseGet(io.pacod.cms.core.data.SiteSettings::new);
        boolean changed = false;
        if ((site.getTaglines() == null || site.getTaglines().isEmpty()) && !texts.getTaglines().isEmpty()) {
            site.setTaglines(texts(named(texts.getTaglines(), website)));
            changed = true;
        }
        if ((site.getDescriptions() == null || site.getDescriptions().isEmpty()) && !texts.getDescriptions().isEmpty()) {
            site.setDescriptions(texts(named(texts.getDescriptions(), website)));
            changed = true;
        }
        List<TextItem> generated = TextItems.put(List.of(), lang, "© " + website.name());
        if (!texts.getFooterNotes().isEmpty() && (site.getFooterNotes() == null || site.getFooterNotes().isEmpty()
                || site.getFooterNotes().equals(generated))) {
            site.setFooterNotes(texts(named(texts.getFooterNotes(), website)));
            changed = true;
        }
        if (isBlank(site.getContactAddress()) && isBlank(site.getContactPhone()) && isBlank(site.getContactEmail())
                && (texts.getContactAddress() != null || texts.getContactEmail() != null)) {
            site.setContactAddress(texts.getContactAddress());
            site.setContactPhone(texts.getContactPhone());
            site.setContactEmail(texts.getContactEmail());
            changed = true;
        }
        if ((site.getSocialProfiles() == null || site.getSocialProfiles().isEmpty()) && !texts.getSocialProfiles().isEmpty()) {
            site.setSocialProfiles(new ArrayList<>(texts.getSocialProfiles()));
            changed = true;
        }
        // Settings with no studio screen: a seed is the one place a website's own can give them.
        if (isBlank(site.getBaseUrl()) && !isBlank(texts.getBaseUrl())) {
            site.setBaseUrl(texts.getBaseUrl());
            changed = true;
        }
        if (isBlank(site.getGoogleSiteVerification()) && !isBlank(texts.getGoogleSiteVerification())) {
            site.setGoogleSiteVerification(texts.getGoogleSiteVerification());
            changed = true;
        }
        if (isBlank(site.getBingSiteVerification()) && !isBlank(texts.getBingSiteVerification())) {
            site.setBingSiteVerification(texts.getBingSiteVerification());
            changed = true;
        }
        if (isBlank(site.getGtmId()) && !isBlank(texts.getGtmId())) {
            site.setGtmId(texts.getGtmId());
            changed = true;
        }
        if (changed) {
            site.setLastModifiedOn(LocalDateTime.now());
            siteSettings.save(site);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The texts with the website's name where they say {@value #NAME}. */
    private static Map<String, String> named(Map<String, String> byLang, SiteSeedStep.Website website) {
        Map<String, String> named = new LinkedHashMap<>();
        byLang.forEach((code, text) -> named.put(code, text == null ? null : text.replace(NAME, website.name())));
        return named;
    }

    /**
     * The seed's menus in place of {@code SiteBootstrap}'s generic ones -- each only while it is
     * still exactly what that step wrote, or absent: a menu an operator has touched stays theirs.
     *
     * @return whether the main menu was written
     */
    private boolean replaceMenus(Seed seed) {
        SiteBootstrap.Seed generic;
        try {
            generic = SiteBootstrap.readSeed(objectMapper);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the generic menus to compare with; the demo's menus are not written: {}", e.getMessage());
            return false;
        }
        boolean main = false;
        for (Map.Entry<String, List<SiteBootstrap.ItemSeed>> entry : seed.getMenus().entrySet()) {
            String key = entry.getKey();
            Menu current = menus.findById(key).orElse(null);
            List<MenuItem> untouched = generic.getMenus().stream().filter(m -> key.equals(m.getKey())).findFirst()
                    .map(m -> SiteBootstrap.toMenu(m).getItems()).orElse(null);
            if (current != null && (untouched == null || !untouched.equals(current.getItems()))) {
                log.info("Menu '{}' has been edited; the demo leaves it", key);
                continue;
            }
            SiteBootstrap.MenuSeed menu = new SiteBootstrap.MenuSeed();
            menu.setKey(key);
            menu.setItems(entry.getValue());
            menus.save(SiteBootstrap.toMenu(menu));
            main |= Menu.MAIN.equals(key);
        }
        return main;
    }

    /**
     * The seed's pages. A new slug is written while absent; a standing page the website already
     * has ({@code seed/pages.json}: about, contact, advertise ...) is replaced only while it is
     * still exactly what {@code PageBootstrap} wrote -- never an edited one.
     */
    private void addPages(Seed seed, SiteSeedStep.Website website, String lang) {
        if (seed.getPages().isEmpty() || pageRepository == null) {
            return;
        }
        Map<String, PageBootstrap.Seed> standing = new LinkedHashMap<>();
        try {
            PageBootstrap.readSeed(objectMapper).forEach(p -> standing.put(p.getSlug(), p));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the standing pages to compare with; none is replaced: {}", e.getMessage());
        }
        for (PageSeed seedPage : seed.getPages()) {
            io.pacod.cms.core.data.Page current = pageRepository.findById(seedPage.getSlug()).orElse(null);
            if (current != null) {
                PageBootstrap.Seed original = standing.get(seedPage.getSlug());
                if (original == null || !sameText(current, PageBootstrap.toPage(original))) {
                    continue;
                }
            }
            io.pacod.cms.core.data.Page page = new io.pacod.cms.core.data.Page(seedPage.getSlug());
            page.setSortOrder(seedPage.getSortOrder());
            page.setPublished(seedPage.isPublished());
            page.setOfficialInDefaultLanguageOnly(seedPage.isOfficialInDefaultLanguageOnly());
            named(seedPage.getTitles(), website).forEach(page::putTitle);
            named(seedPage.getBodies(), website).forEach(page::putBody);
            named(seedPage.getDescriptions(), website).forEach(page::putDescription);
            LocalDateTime now = LocalDateTime.now();
            page.setCreatedOn(current != null && current.getCreatedOn() != null ? current.getCreatedOn() : now);
            page.setLastModifiedOn(now);
            pageRepository.save(page);
        }
    }

    /** Whether two pages hold the same titles, bodies and descriptions in every language. */
    private static boolean sameText(io.pacod.cms.core.data.Page a, io.pacod.cms.core.data.Page b) {
        Set<String> codes = new TreeSet<>();
        for (io.pacod.cms.core.data.Page page : List.of(a, b)) {
            for (List<TextItem> items : java.util.Arrays.asList(page.getTitles(), page.getBodies(), page.getDescriptions())) {
                if (items != null) {
                    items.forEach(item -> codes.add(item.getLang()));
                }
            }
        }
        for (String code : codes) {
            if (!java.util.Objects.equals(a.rawTitle(code), b.rawTitle(code))
                    || !java.util.Objects.equals(a.rawBody(code), b.rawBody(code))
                    || !java.util.Objects.equals(a.rawDescription(code), b.rawDescription(code))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The seed's own languages, each only while the website has no language of its code: what
     * gives a website one {@code seed/languages.json} does not have (Bulgarian, Russian).
     */
    private void addLanguages(Seed seed) {
        if (seed.getLanguages().isEmpty() || languageRepository == null) {
            return;
        }
        for (io.pacod.cms.core.data.Language language : seed.getLanguages()) {
            if (!languageRepository.existsById(language.getCode())) {
                languageRepository.save(language);
            }
        }
    }

    /**
     * The website's languages narrowed to the seed's -- those it is complete in, and those it
     * names ({@link Seed#getLanguages()}): a demo written in one language would otherwise offer a
     * switcher of five that all show the same text. A language goes only while it is exactly as
     * {@code LanguageBootstrap} wrote it, is neither the original nor {@link LanguageChain#FALLBACK},
     * and no page holds text in it -- the studio's own rules for deleting one.
     */
    private void narrowLanguages(Seed seed) {
        if (languageRepository == null || pageRepository == null) {
            return;
        }
        Map<String, io.pacod.cms.core.data.Language> generic = new LinkedHashMap<>();
        try {
            LanguageBootstrap.readSeed(objectMapper).forEach(l -> generic.put(l.getCode(), l));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the seeded languages to compare with; none is removed: {}", e.getMessage());
            return;
        }
        Set<String> keep = new java.util.HashSet<>(completeLanguages(seed));
        seed.getLanguages().forEach(language -> keep.add(language.getCode()));
        List<io.pacod.cms.core.data.Page> pages = pageRepository.findAll();
        for (io.pacod.cms.core.data.Language language : languageRepository.findAll()) {
            String code = language.getCode();
            if (keep.contains(code) || code.equals(LanguageChain.original()) || code.equals(LanguageChain.FALLBACK)
                    || !language.equals(generic.get(code))
                    || pages.stream().anyMatch(page -> page.hasAnyTextIn(code))) {
                continue;
            }
            languageRepository.deleteById(code);
        }
    }

    /**
     * The seed's layouts, published, each only while the website has none for that page type, and
     * only on the template set they were designed for ({@link Seed#getLayoutSet()}) -- where every
     * one must resolve without an issue, or it is not written: a section a set cannot render is
     * dropped from the page without a word.
     */
    private void addLayouts(Seed seed) {
        if (seed.getLayouts().isEmpty() || layoutRepository == null || templateSets == null) {
            return;
        }
        String set = TenantContext.current().templateSet();
        if (!set.equals(seed.getLayoutSet())) {
            log.info("The demo's layouts are for the {} set, not {}; the set's own defaults stay", seed.getLayoutSet(), set);
            return;
        }
        io.pacod.cms.core.design.TemplateSetManifest manifest = templateSets.orDefault(set);
        java.time.Instant now = java.time.Instant.now();
        for (Map.Entry<String, Map<String, List<SectionSeed>>> entry : seed.getLayouts().entrySet()) {
            io.pacod.cms.core.design.PageType pageType = io.pacod.cms.core.design.PageType.valueOf(entry.getKey());
            if (layoutRepository.existsById(pageType.name())) {
                continue;
            }
            Map<String, List<io.pacod.cms.core.design.Section>> regions = toRegions(entry.getValue());
            List<io.pacod.cms.core.design.LayoutResolver.Issue> issues =
                    io.pacod.cms.core.design.LayoutResolver.resolve(manifest, pageType, regions).issues();
            if (!issues.isEmpty()) {
                log.error("The demo's {} layout does not fit the {} set and is not written: {}", pageType, set, issues);
                continue;
            }
            io.pacod.cms.core.design.Layout layout = new io.pacod.cms.core.design.Layout(pageType);
            layout.setDraft(io.pacod.cms.core.design.Layout.copy(regions));
            layout.setPublished(io.pacod.cms.core.design.Layout.copy(regions));
            layout.setUpdatedOn(now);
            layout.setPublishedOn(now);
            layoutRepository.save(layout);
        }
    }

    /** A seed layout's regions as sections, each one's settings checked as the studio checks them. */
    static Map<String, List<io.pacod.cms.core.design.Section>> toRegions(Map<String, List<SectionSeed>> seed) {
        Map<String, List<io.pacod.cms.core.design.Section>> regions = new LinkedHashMap<>();
        seed.forEach((region, sections) -> {
            List<io.pacod.cms.core.design.Section> out = new ArrayList<>();
            for (SectionSeed section : sections) {
                io.pacod.cms.core.design.SectionType type = io.pacod.cms.core.design.SectionType.byId(section.getType())
                        .orElseThrow(() -> new IllegalStateException("Unknown section type " + section.getType()));
                io.pacod.cms.core.design.SectionSettings.Result checked =
                        io.pacod.cms.core.design.SectionSettings.check(type, section.getSettings());
                if (!checked.ok()) {
                    throw new IllegalStateException("Section " + section.getType() + " in " + region + ": " + checked.errors());
                }
                out.add(io.pacod.cms.core.design.Section.of(type, checked.settings()));
            }
            regions.put(region, out);
        });
        return regions;
    }

    /**
     * The seed's journal issues, published, while the website has none. No PDF is made: the
     * pages list an issue's articles and link a PDF only once one exists.
     */
    private void addIssues(Seed seed, Map<String, ImageMedia> pictures, String lang) {
        if (seed.getIssues().isEmpty() || issueRepository == null || issueRepository.count() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int id = 1;
        for (IssueSeed seedIssue : seed.getIssues()) {
            io.pacod.cms.core.data.JournalIssue issue = new io.pacod.cms.core.data.JournalIssue();
            issue.setId(id++);
            issue.setVolume(seedIssue.getVolume());
            issue.setNumber(seedIssue.getNumber());
            LocalDateTime month = now.minusMonths(seedIssue.getMonthsAgo());
            issue.setYear(month.getYear());
            issue.setMonth(month.getMonthValue());
            issue.setTitles(texts(seedIssue.getTitles()));
            issue.setEditorials(texts(seedIssue.getEditorials()));
            ImageMedia cover = pictures.get(seedIssue.getCover());
            issue.setCoverImageId(cover == null ? null : cover.getId());
            issue.setArticleIds(new ArrayList<>(seedIssue.getArticles()));
            issue.setLanguages(new ArrayList<>(List.of(lang)));
            issue.setStatus(io.pacod.cms.core.data.IssueStatus.PUBLISHED);
            issue.setCreatedOn(now);
            issue.setLastModifiedOn(now);
            issue.setPublishedOn(now);
            issueRepository.insert(issue);
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
        named(social.getBios(), website).forEach(page::putBio);
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
        if (!seed.getCategories().isEmpty() && seed.getMenus().isEmpty()) {
            required.add(seed.getMenu().getLabels());
        }
        seed.getPages().forEach(p -> {
            required.add(p.getTitles());
            required.add(p.getBodies());
        });
        seed.getIssues().forEach(i -> required.add(i.getTitles()));
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
        /** Menus by key, written in place of the generic ones while those are untouched; optional. */
        private Map<String, List<SiteBootstrap.ItemSeed>> menus = new LinkedHashMap<>();
        /** Standing pages and the advertisers' pages; optional. */
        private List<PageSeed> pages = new ArrayList<>();
        /** Whether the website's languages are narrowed to the ones the seed is written in. */
        private boolean narrowLanguages;
        /** The template set {@link #layouts} are designed for. */
        private String layoutSet;
        /** Page type to region to sections, published where the page type has no layout; optional. */
        private Map<String, Map<String, List<SectionSeed>>> layouts = new LinkedHashMap<>();
        /** Journal issues, published, where the website has none; optional. */
        private List<IssueSeed> issues = new ArrayList<>();
        /** The advertisers, with their pages' slugs, creatives and bookings: the studio writes them. */
        private List<PartnerSeed> partners = new ArrayList<>();
        /** The website's favicon, logos and share picture: the studio writes them. */
        private BrandSeed brand;
        /** What every stored picture's file name starts with; a demo's are {@value DemoContent#IMAGE_PREFIX}. */
        private String imagePrefix = IMAGE_PREFIX;
        /**
         * Languages the website is given while it has none of that code, in the shape
         * {@code seed/languages.json} has; optional. {@link #narrowLanguages} keeps them.
         */
        private List<io.pacod.cms.core.data.Language> languages = new ArrayList<>();
    }

    @Data
    public static class SiteSeed {
        private Map<String, String> taglines = new LinkedHashMap<>();
        private Map<String, String> descriptions = new LinkedHashMap<>();
        private Map<String, String> footerNotes = new LinkedHashMap<>();
        private String contactAddress;
        private String contactPhone;
        private String contactEmail;
        private List<io.pacod.cms.core.data.SiteSettings.SocialProfile> socialProfiles = new ArrayList<>();
        /**
         * The website's public address, its search consoles' ids and its Tag Manager container
         * ({@code GTM-…}, {@link io.pacod.cms.core.data.SiteSettings#GTM_ID}): each written while blank.
         */
        private String baseUrl;
        private String googleSiteVerification;
        private String bingSiteVerification;
        private String gtmId;
    }

    @Data
    public static class PageSeed {
        private String slug;
        private Integer sortOrder = 0;
        private boolean published = true;
        private boolean officialInDefaultLanguageOnly;
        private Map<String, String> titles = new LinkedHashMap<>();
        private Map<String, String> bodies = new LinkedHashMap<>();
        private Map<String, String> descriptions = new LinkedHashMap<>();
    }

    @Data
    public static class SectionSeed {
        private String type;
        private Map<String, Object> settings = new LinkedHashMap<>();
    }

    @Data
    public static class IssueSeed {
        private int volume;
        private int number;
        /** How many months before the install the issue is dated. */
        private int monthsAgo;
        private String cover;
        private List<Integer> articles = new ArrayList<>();
        private Map<String, String> titles = new LinkedHashMap<>();
        private Map<String, String> editorials = new LinkedHashMap<>();
    }

    /**
     * One advertiser. Its landing page is one of {@link Seed#getPages()} ({@link #page}); its
     * creatives are a folder on the studio's classpath, imported as a banner layout of id
     * {@link #id}, with the demo photographs it names copied in.
     */
    @Data
    public static class PartnerSeed {
        private String id;
        private String name;
        /** The slug of its landing page. */
        private String page;
        /** The classpath folder holding its creatives. */
        private String folder;
        /** Demo photographs copied into the folder: file name there, to image key. */
        private Map<String, String> photos = new LinkedHashMap<>();
        private List<BookingSeed> bookings = new ArrayList<>();
    }

    @Data
    public static class BookingSeed {
        private String name;
        /** HTML5: a file of the advertiser's folder. IMAGE: a classpath picture, uploaded. */
        private String type = "HTML5";
        private String file;
        private String image;
        private String alt;
        private String size;
        private String placement;
        private List<String> pages = new ArrayList<>();
        /** The sections whose articles and lists it runs on; empty for every page it books. */
        private List<Integer> categories = new ArrayList<>();
        private int sortOrder;
    }

    @Data
    public static class BrandSeed {
        /** An SVG (or PNG) of the source. */
        private String favicon;
        private String logoLight;
        private String logoDark;
        /** The logos are written only for a website of this name: they spell it. */
        private String logoName;
        /** The image key the share picture is cut from. */
        private String share;
        /** Or a picture of the source, cut to the share picture's 1200x630. */
        private String shareFile;
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
        /** {@link #fit}: cropped to the website's shape around the focus. */
        public static final String CROP = "crop";
        /** {@link #fit}: shown whole, on {@link #background} -- a screenshot loses nothing. */
        public static final String PAD = "pad";

        private String key;
        /** Drawn: one of {@link DemoImages.Motif}, with {@link #colours}. */
        private String motif;
        private List<String> colours = new ArrayList<>();
        /** Or a photograph: its path in the seed's source, its credit line, where it came from. */
        private String photo;
        private String credit;
        private String source;
        private String nasaId;
        /** The crop's centre in the photograph, 0..1. */
        private double focusX = 0.5;
        private double focusY = 0.5;
        /** How a photograph is fitted to the website's shape: {@value #CROP} or {@value #PAD}. */
        private String fit = CROP;
        /** What a padded photograph sits on, {@code #rrggbb}. */
        private String background = "#ffffff";
        private Map<String, String> captions = new LinkedHashMap<>();
        private Map<String, String> alts = new LinkedHashMap<>();
    }

    @Data
    public static class ArticleSeed {
        private Integer id;
        private int daysAgo;
        private int hour = 9;
        /** Or the date itself, in the website's time: {@code 2026-10-20T09:00}. Wins over {@link #daysAgo}. */
        private LocalDateTime date;
        /** When it was last changed; its date when absent. */
        private LocalDateTime modified;
        /** {@code PUBLISHED} when absent: the public site shows nothing else. */
        private ArticleStatus status;
        private int counter;
        private List<Integer> categories = new ArrayList<>();
        private List<Integer> authors = new ArrayList<>();
        private List<String> images = new ArrayList<>();
        /** Of {@link #images}, the ones placed inside the body by address: hidden from the gallery. */
        private List<String> inline = new ArrayList<>();
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
