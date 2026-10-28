package io.pacod.cms.core.seed;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.core.repository.TagRepository;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.core.util.SocialTargets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The installer against in-memory repositories, in the test website {@code test} that
 * {@code TestTenantExtension} binds. The original language is given explicitly: the suite's is
 * Bulgarian, which the seed is not written in.
 */
class DemoContentTest {

    private static final SiteSeedStep.Website TEST = new SiteSeedStep.Website("test", "Demo Journal", "en");

    private ArticleRepository articles;
    private CategoryRepository categories;
    private AuthorRepository authors;
    private ImageMediaRepository images;
    private TagRepository tags;
    private MenuRepository menus;
    private SocialPageRepository socialPages;
    private ImageService imageService;
    private DemoContent demo;

    private final Map<Integer, Category> storedCategories = new LinkedHashMap<>();
    private final Map<Integer, Author> storedAuthors = new LinkedHashMap<>();
    private final Map<String, ImageMedia> storedImages = new LinkedHashMap<>();
    private final List<byte[]> drawn = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        articles = mock(ArticleRepository.class);
        categories = mock(CategoryRepository.class);
        authors = mock(AuthorRepository.class);
        images = mock(ImageMediaRepository.class);
        tags = mock(TagRepository.class);
        menus = mock(MenuRepository.class);
        socialPages = mock(SocialPageRepository.class);
        imageService = mock(ImageService.class);

        when(imageService.getRequiredWidth()).thenReturn(600);
        when(imageService.getRequiredHeight()).thenReturn(400);
        when(imageService.isFileSizeCheckEnabled()).thenReturn(true);
        when(imageService.getMaxImageBytes()).thenReturn(128L * 1024);
        when(imageService.save(anyString(), eq("image/jpeg"), any(byte[].class), any())).thenAnswer(call -> {
            drawn.add(call.getArgument(2));
            ImageMedia media = new ImageMedia(storedImages.size() + 1);
            media.setFilename(call.getArgument(0));
            media.setCaption(call.getArgument(3));
            storedImages.put(media.getFilename(), media);
            return media;
        });
        when(images.findImageMediaByFilename(anyString()))
                .thenAnswer(call -> Optional.ofNullable(storedImages.get(call.<String>getArgument(0))));
        when(images.save(any(ImageMedia.class))).thenAnswer(call -> call.getArgument(0));

        when(categories.existsById(any())).thenAnswer(call -> storedCategories.containsKey(call.<Integer>getArgument(0)));
        when(categories.save(any(Category.class))).thenAnswer(call -> {
            Category category = call.getArgument(0);
            storedCategories.put(category.getId(), category);
            return category;
        });
        when(categories.findAllById(anyIterable())).thenAnswer(call -> new ArrayList<>(storedCategories.values()));
        when(authors.existsById(any())).thenAnswer(call -> storedAuthors.containsKey(call.<Integer>getArgument(0)));
        when(authors.save(any(Author.class))).thenAnswer(call -> {
            Author author = call.getArgument(0);
            storedAuthors.put(author.getId(), author);
            return author;
        });
        when(authors.findAllById(anyIterable())).thenAnswer(call -> new ArrayList<>(storedAuthors.values()));

        demo = new DemoContent(articles, categories, authors, images, tags, menus, socialPages, imageService,
                JsonMapper.builder().build());
    }

    private static Menu seededMain() {
        Menu main = new Menu(Menu.MAIN);
        main.setItems(new ArrayList<>(List.of(item(MenuLinkKind.HOME, null), item(MenuLinkKind.LATEST, null),
                item(MenuLinkKind.PAGE, "about"))));
        return main;
    }

    private static MenuItem item(MenuLinkKind kind, String target) {
        MenuItem item = new MenuItem();
        item.setLabels(new ArrayList<>(List.of(new TextItem("x", "en"))));
        item.setKind(kind);
        item.setTarget(target);
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Article> savedArticles() {
        ArgumentCaptor<List<Article>> saved = ArgumentCaptor.forClass(List.class);
        verify(articles).saveAll(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("an empty English website gets published articles in English, their translations, pictures, sections and authors")
    void installsIntoAnEmptyWebsite() throws Exception {
        when(menus.findById(Menu.MAIN)).thenReturn(Optional.of(seededMain()));

        DemoContent.Result result = demo.install(TEST);

        List<Article> written = savedArticles();
        DemoContent.Seed seed = demo.seed();
        assertThat(result.articles()).isEqualTo(seed.getArticles().size()).isEqualTo(written.size());
        assertThat(result.images()).isEqualTo(seed.getImages().size());
        assertThat(result.categories()).isEqualTo(seed.getCategories().size());
        assertThat(result.authors()).isEqualTo(seed.getAuthors().size());
        LocalDateTime now = LocalDateTime.now();
        assertThat(written).allSatisfy(article -> {
            assertThat(article.getStatus()).as("article %d", article.getId()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(article.getId()).isPositive();
            assertThat(article.getLang()).isEqualTo("en");
            assertThat(article.getTitle()).isNotBlank();
            assertThat(article.getText()).startsWith("<p>");
            assertThat(article.getDate()).isBeforeOrEqualTo(now).isAfter(now.minusDays(30));
            assertThat(article.getLastModifiedOn()).isEqualTo(article.getDate());
            assertThat(article.getCategories()).isNotEmpty().doesNotContainNull();
            assertThat(article.getAuthors()).isNotEmpty().doesNotContainNull();
            assertThat(article.getImages()).isNotEmpty().doesNotContainNull();
            // The original lives in the scalars, never in the lists beside them.
            assertThat(article.translatedIn()).doesNotContain("en");
        });
        Article port = written.stream().filter(a -> a.getId() == 19).findFirst().orElseThrow();
        assertThat(port.getTitle()).isEqualTo("Forty-eight hours in Port Alder");
        assertThat(port.translatedIn()).containsExactlyInAnyOrder("de", "es");
        assertThat(port.getImages()).hasSize(3);

        // Pictures were drawn at the website's size, under its byte limit, and carry alt text.
        assertThat(drawn).hasSize(seed.getImages().size()).allSatisfy(jpeg -> {
            assertThat(jpeg.length).isLessThanOrEqualTo(128 * 1024);
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertThat(read.getWidth()).isEqualTo(600);
            assertThat(read.getHeight()).isEqualTo(400);
        });
        assertThat(storedImages.values()).allSatisfy(image -> {
            assertThat(image.getFilename()).startsWith(DemoContent.IMAGE_PREFIX).endsWith(".jpg");
            assertThat(image.getCaption()).isNotBlank();
            assertThat(image.altFor("en")).isNotBlank();
        });

        Category technology = storedCategories.get(1);
        assertThat(technology.getName()).isEqualTo("Technology");
        assertThat(technology.getLang()).isEqualTo("en");
        assertThat(technology.getNames()).extracting(TextItem::getLang).containsExactly("de", "es");

        ArgumentCaptor<Tag> tag = ArgumentCaptor.forClass(Tag.class);
        verify(tags, org.mockito.Mockito.atLeastOnce()).save(tag.capture());
        assertThat(tag.getAllValues()).extracting(Tag::getName).contains("explainer", "recipes");
    }

    @Test
    @DisplayName("the main menu gets a Sections group after Latest, linking every demo section")
    void addsTheSectionsGroup() {
        when(menus.findById(Menu.MAIN)).thenReturn(Optional.of(seededMain()));

        assertThat(demo.install(TEST).menuGroup()).isTrue();

        ArgumentCaptor<Menu> menu = ArgumentCaptor.forClass(Menu.class);
        verify(menus).save(menu.capture());
        List<MenuItem> items = menu.getValue().getItems();
        assertThat(items).extracting(MenuItem::getKind)
                .containsExactly(MenuLinkKind.HOME, MenuLinkKind.LATEST, MenuLinkKind.NONE, MenuLinkKind.PAGE);
        MenuItem group = items.get(2);
        assertThat(group.label(List.of("en"))).isEqualTo("Sections");
        assertThat(group.getChildren()).extracting(MenuItem::getTarget).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(group.getChildren()).allSatisfy(child -> {
            assertThat(child.getKind()).isEqualTo(MenuLinkKind.CATEGORY);
            assertThat(child.label(List.of("de"))).isNotBlank();
        });
    }

    @Test
    @DisplayName("a menu that already links a section is left alone")
    void keepsAnOperatorsSections() {
        Menu main = seededMain();
        MenuItem own = item(MenuLinkKind.NONE, null);
        own.setChildren(new ArrayList<>(List.of(item(MenuLinkKind.CATEGORY, "7"))));
        main.getItems().add(own);
        when(menus.findById(Menu.MAIN)).thenReturn(Optional.of(main));

        assertThat(demo.install(TEST).menuGroup()).isFalse();
        verify(menus, never()).save(any());
    }

    @Test
    @DisplayName("the link page is published under the website's name, with generated link ids and addresses the site accepts")
    void writesThePublishedLinkPage() {
        when(socialPages.existsById(SocialPage.ID)).thenReturn(false);

        assertThat(demo.install(new SiteSeedStep.Website("test", "Acme Weekly", "en")).socialPage()).isTrue();

        ArgumentCaptor<SocialPage> page = ArgumentCaptor.forClass(SocialPage.class);
        verify(socialPages).save(page.capture());
        SocialPage social = page.getValue();
        assertThat(social.isPublished()).isTrue();
        assertThat(social.rawName("en")).isEqualTo("Acme Weekly");
        assertThat(social.rawBio("de")).isNotBlank();
        assertThat(social.getLinks()).hasSize(demo.seed().getSocial().getLinks().size())
                .extracting(SocialLink::getId).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(SocialPage.isLinkId(id)).as(id).isTrue());
        assertThat(social.getLinks()).allSatisfy(link ->
                assertThat(SocialTargets.isValidTarget(link.getUrl())).as(link.getUrl()).isTrue());
    }

    @Test
    @DisplayName("a website in a language the seed is not written in is refused before anything is written")
    void refusesAnotherLanguage() {
        assertThat(demo.supports("en")).isTrue();
        assertThat(demo.supports("bg")).isFalse();

        assertThatThrownBy(() -> demo.install(new SiteSeedStep.Website("test", "Демо", "bg")))
                .isInstanceOfSatisfying(DemoContent.Refused.class,
                        e -> assertThat(e.reason()).isEqualTo(DemoContent.Refusal.LANGUAGE));
        nothingWritten();
    }

    @Test
    @DisplayName("a website that has articles is refused before anything is written")
    void refusesAWebsiteWithArticles() {
        when(articles.count()).thenReturn(1L);

        assertThatThrownBy(() -> demo.install(TEST))
                .isInstanceOfSatisfying(DemoContent.Refused.class,
                        e -> assertThat(e.reason()).isEqualTo(DemoContent.Refusal.HAS_ARTICLES));
        nothingWritten();
    }

    @Test
    @DisplayName("another website's id while one is bound is refused: content never lands in the wrong database")
    void refusesAnotherWebsitesId() {
        assertThatThrownBy(() -> demo.install(new SiteSeedStep.Website("beta", "Beta", "en")))
                .isInstanceOf(IllegalStateException.class);
        nothingWritten();
    }

    @Test
    @DisplayName("a run that stopped after the pictures is finished by running it again, without drawing them twice")
    void rerunReusesWhatIsThere() throws Exception {
        for (DemoContent.ImageSeed image : demo.seed().getImages()) {
            ImageMedia existing = new ImageMedia(storedImages.size() + 1);
            existing.setFilename(DemoContent.IMAGE_PREFIX + image.getKey() + ".jpg");
            storedImages.put(existing.getFilename(), existing);
        }
        when(categories.existsById(1)).thenReturn(true);
        storedCategories.put(1, new Category(1, "Technology", new ArrayList<>(), "en"));

        DemoContent.Result result = demo.install(TEST);

        assertThat(result.images()).isZero();
        assertThat(result.categories()).isEqualTo(demo.seed().getCategories().size() - 1);
        verify(imageService, never()).save(anyString(), anyString(), any(byte[].class), any());
        assertThat(savedArticles()).allSatisfy(article -> assertThat(article.getImages()).doesNotContainNull());
    }

    @Test
    @DisplayName("the space travels: its own articles and photographs, and its taglines and theme only where the website has none")
    void spaceTravels() {
        var settingsRepository = mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        var themeRepository = mock(io.pacod.cms.core.repository.ThemeRepository.class);
        io.pacod.cms.core.data.SiteSettings site = new io.pacod.cms.core.data.SiteSettings();
        site.setDescriptions(new ArrayList<>(List.of(new TextItem("The operator's own description", "en"))));
        // What SiteBootstrap wrote for a website of this name: the demo's own note replaces it.
        site.setFooterNotes(new ArrayList<>(List.of(new TextItem("© Demo Journal", "en"))));
        when(settingsRepository.findById(io.pacod.cms.core.data.SiteSettings.ID)).thenReturn(Optional.of(site));
        demo.useSiteAndTheme(settingsRepository, themeRepository);

        // The shipped default limit; fitting a photograph to a stricter one is spacePhotographsFitTheRules.
        when(imageService.getMaxImageBytes()).thenReturn(400L * 1024);
        when(imageService.isDimensionCheckEnabled()).thenReturn(true);
        DemoContent.Result result = demo.install(DemoContent.Demo.SPACE, TEST);

        assertThat(result.articles()).isEqualTo(24);
        List<Article> saved = savedArticles();
        assertThat(saved).extracting(Article::getTitle).contains("Why the stars do not twinkle in space");
        // A photograph, stored as an upload is, its caption ending with its credit.
        assertThat(storedImages).containsKey("demo-tessaly-eclipse.jpg");
        assertThat(storedImages.get("demo-tessaly-eclipse.jpg").getCaption())
                .endsWith("Image: NASA/JPL-Caltech/Space Science Institute.");
        // Every one fits the website's byte limit, whatever size it was shipped at.
        assertThat(drawn).hasSize(37).allSatisfy(jpeg -> assertThat(jpeg.length).isLessThanOrEqualTo(400 * 1024));
        // A picture placed inside a body is attached, and hidden from the gallery.
        Article trail = saved.stream().filter(a -> a.getId() == 6).findFirst().orElseThrow();
        assertThat(trail.getHiddenImageIds()).containsExactly(storedImages.get("demo-orrin-mount.jpg").getId());
        assertThat(trail.getText()).contains("src=\"/assets/img/articles/demo-orrin-mount.jpg\"");

        ArgumentCaptor<io.pacod.cms.core.data.SiteSettings> settings = ArgumentCaptor.forClass(io.pacod.cms.core.data.SiteSettings.class);
        verify(settingsRepository).save(settings.capture());
        assertThat(settings.getValue().getTaglines()).extracting(TextItem::getText).contains("Travel writing from across the Reach");
        // The operator's description stays.
        assertThat(settings.getValue().getDescriptions()).extracting(TextItem::getText).containsExactly("The operator's own description");
        assertThat(settings.getValue().getFooterNotes()).extracting(TextItem::getText).singleElement()
                .asString().startsWith("© Demo Journal. A pacod-cms demonstration");
        assertThat(settings.getValue().getContactEmail()).isEqualTo("editors@example.com");
        assertThat(settings.getValue().getSocialProfiles()).isNotEmpty()
                .allSatisfy(profile -> assertThat(profile.getUrl()).startsWith("https://example.com/"));
        ArgumentCaptor<io.pacod.cms.core.design.Theme> theme = ArgumentCaptor.forClass(io.pacod.cms.core.design.Theme.class);
        verify(themeRepository).save(theme.capture());
        assertThat(theme.getValue().getPublished()).isEqualTo(theme.getValue().getDraft())
                .containsEntry("panel", "#0a0e1a").containsEntry("font-display", "fraunces");
    }

    @Test
    @DisplayName("a photograph is fitted to a stricter website: larger minimum, small byte limit")
    void spacePhotographsFitTheRules() {
        when(imageService.getRequiredWidth()).thenReturn(900);
        when(imageService.getRequiredHeight()).thenReturn(600);
        when(imageService.isDimensionCheckEnabled()).thenReturn(true);
        when(imageService.getMaxImageBytes()).thenReturn(90L * 1024);

        demo.install(DemoContent.Demo.SPACE, TEST);

        assertThat(drawn).hasSize(37).allSatisfy(jpeg -> {
            assertThat(jpeg.length).isLessThanOrEqualTo(90 * 1024);
            BufferedImage image = read(jpeg);
            assertThat(io.pacod.cms.core.services.ImageService.fitsDimensions(image.getWidth(), image.getHeight(), 900, 600))
                    .as("%dx%d", image.getWidth(), image.getHeight()).isTrue();
        });
    }

    private static BufferedImage read(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The repositories of a website's standing content, in memory. */
    private final class Standing {
        final Map<String, io.pacod.cms.core.data.Page> pages = new LinkedHashMap<>();
        final Map<String, io.pacod.cms.core.data.Language> languages = new LinkedHashMap<>();
        final Map<String, io.pacod.cms.core.design.Layout> layouts = new LinkedHashMap<>();
        final List<io.pacod.cms.core.data.JournalIssue> issues = new ArrayList<>();
        final Map<String, Menu> menusByKey = new LinkedHashMap<>();

        Standing() throws Exception {
            // These installs are about what is written, not about fitting photographs: the default limit.
            when(imageService.getMaxImageBytes()).thenReturn(400L * 1024);
            when(imageService.isDimensionCheckEnabled()).thenReturn(true);
            var pageRepository = mock(io.pacod.cms.core.repository.PageRepository.class);
            when(pageRepository.findById(anyString())).thenAnswer(c -> Optional.ofNullable(pages.get(c.<String>getArgument(0))));
            when(pageRepository.findAll()).thenAnswer(c -> new ArrayList<>(pages.values()));
            when(pageRepository.save(any(io.pacod.cms.core.data.Page.class))).thenAnswer(c -> {
                io.pacod.cms.core.data.Page page = c.getArgument(0);
                pages.put(page.getSlug(), page);
                return page;
            });
            var languageRepository = mock(io.pacod.cms.core.repository.LanguageRepository.class);
            when(languageRepository.findAll()).thenAnswer(c -> new ArrayList<>(languages.values()));
            when(languageRepository.existsById(anyString())).thenAnswer(c -> languages.containsKey(c.<String>getArgument(0)));
            when(languageRepository.save(any(io.pacod.cms.core.data.Language.class))).thenAnswer(c -> {
                io.pacod.cms.core.data.Language language = c.getArgument(0);
                languages.put(language.getCode(), language);
                return language;
            });
            org.mockito.Mockito.doAnswer(c -> languages.remove(c.<String>getArgument(0)))
                    .when(languageRepository).deleteById(anyString());
            var layoutRepository = mock(io.pacod.cms.core.repository.LayoutRepository.class);
            when(layoutRepository.existsById(anyString())).thenAnswer(c -> layouts.containsKey(c.<String>getArgument(0)));
            when(layoutRepository.save(any(io.pacod.cms.core.design.Layout.class))).thenAnswer(c -> {
                io.pacod.cms.core.design.Layout layout = c.getArgument(0);
                layouts.put(layout.getId(), layout);
                return layout;
            });
            var issueRepository = mock(io.pacod.cms.core.repository.JournalIssueRepository.class);
            when(issueRepository.count()).thenAnswer(c -> (long) issues.size());
            when(issueRepository.insert(any(io.pacod.cms.core.data.JournalIssue.class))).thenAnswer(c -> {
                issues.add(c.getArgument(0));
                return c.getArgument(0);
            });
            when(menus.findById(anyString())).thenAnswer(c -> Optional.ofNullable(menusByKey.get(c.<String>getArgument(0))));
            when(menus.save(any(Menu.class))).thenAnswer(c -> {
                Menu menu = c.getArgument(0);
                menusByKey.put(menu.getKey(), menu);
                return menu;
            });
            demo.useStandingContent(pageRepository, languageRepository, layoutRepository, issueRepository,
                    new io.pacod.cms.core.design.TemplateSetCatalog());

            // What a new website holds: the seed steps' pages, languages and menus, untouched.
            var mapper = JsonMapper.builder().build();
            PageBootstrap.readSeed(mapper).forEach(seed -> pages.put(seed.getSlug(), PageBootstrap.toPage(seed)));
            LanguageBootstrap.readSeed(mapper).forEach(language -> languages.put(language.getCode(), language));
            SiteBootstrap.readSeed(mapper).getMenus().forEach(menu -> menusByKey.put(menu.getKey(), SiteBootstrap.toMenu(menu)));
        }
    }

    private static io.pacod.cms.core.tenant.Tenant tenant(String templateSet) {
        return new io.pacod.cms.core.tenant.Tenant("test", "org", "site_test", "en", java.time.ZoneOffset.UTC, templateSet);
    }

    @Test
    @DisplayName("the standing pages, menus and languages are replaced only while they are still the seed steps' own")
    void spaceStandingContentOnlyWhereUntouched() throws Exception {
        Standing standing = new Standing();
        // An operator has written their own contact page and footer menu, and German text on a page.
        standing.pages.get("contact").putBody("en", "<p>Our own contact page.</p>");
        standing.menusByKey.get(Menu.FOOTER).getItems().remove(0);
        standing.pages.get("terms").putBody("de", "<p>Unsere Bedingungen.</p>");

        io.pacod.cms.core.tenant.TenantContext.runAs(tenant("magazine"), () -> demo.install(DemoContent.Demo.SPACE, TEST));

        assertThat(standing.pages.get("about").rawTitle("en")).isEqualTo("About Demo Journal");
        assertThat(standing.pages.get("about").rawBody("de")).as("the generic German text goes with it").isNull();
        assertThat(standing.pages.get("contact").rawBody("en")).isEqualTo("<p>Our own contact page.</p>");
        assertThat(standing.pages).containsKeys("aurelian-lines", "kestrel-vane", "rimstone-lodge",
                "lumen-assurance", "nightglass-tours");
        // The main menu was untouched and is the magazine's sections now; the edited footer stays.
        assertThat(standing.menusByKey.get(Menu.MAIN).getItems()).extracting(MenuItem::getKind)
                .contains(MenuLinkKind.CATEGORY).doesNotContain(MenuLinkKind.HOME);
        assertThat(standing.menusByKey.get(Menu.FOOTER).getItems()).noneMatch(item -> item.getKind() == MenuLinkKind.SOCIAL);
        // English stays, German stays because a page holds German, the others go.
        assertThat(standing.languages).containsOnlyKeys("en", "de");
        // On the magazine set, a published layout for every page type the seed names.
        assertThat(standing.layouts).containsKeys("HOME", "ARTICLE", "CATEGORY", "LATEST", "PAGE", "SEARCH");
        assertThat(standing.layouts.get("HOME").getPublished()).containsKeys("top", "main", "aside", "wide")
                .isEqualTo(standing.layouts.get("HOME").getDraft());
        assertThat(standing.issues).hasSize(2).allSatisfy(issue -> {
            assertThat(issue.published()).isTrue();
            assertThat(issue.getCoverImageId()).isNotNull();
        });
    }

    @Test
    @DisplayName("on another set the layouts are not written, and a layout of the website's own is kept")
    void spaceLayoutsOnlyOnTheirSet() throws Exception {
        Standing standing = new Standing();
        io.pacod.cms.core.tenant.TenantContext.runAs(tenant("editorial"), () -> demo.install(DemoContent.Demo.SPACE, TEST));
        assertThat(standing.layouts).isEmpty();

        Standing again = new Standing();
        io.pacod.cms.core.design.Layout own = new io.pacod.cms.core.design.Layout(io.pacod.cms.core.design.PageType.HOME);
        again.layouts.put("HOME", own);
        when(articles.count()).thenReturn(0L);
        io.pacod.cms.core.tenant.TenantContext.runAs(tenant("magazine"), () -> demo.install(DemoContent.Demo.SPACE, TEST));
        assertThat(again.layouts.get("HOME")).isSameAs(own);
        assertThat(again.layouts).containsKey("ARTICLE");
    }

    @Test
    @DisplayName("a website with a theme of its own keeps it")
    void spaceKeepsAnOwnTheme() {
        var themeRepository = mock(io.pacod.cms.core.repository.ThemeRepository.class);
        when(themeRepository.existsById(io.pacod.cms.core.design.Theme.ID)).thenReturn(true);
        demo.useSiteAndTheme(mock(io.pacod.cms.core.repository.SiteSettingsRepository.class), themeRepository);
        when(imageService.getMaxImageBytes()).thenReturn(400L * 1024);
        when(imageService.isDimensionCheckEnabled()).thenReturn(true);
        demo.install(DemoContent.Demo.SPACE, TEST);
        verify(themeRepository, never()).save(any());
    }

    @Test
    @DisplayName("a demo is named by its id; blank, true or 1 mean the journal; anything else is none")
    void demoNames() {
        assertThat(DemoContent.Demo.of("space")).contains(DemoContent.Demo.SPACE);
        assertThat(DemoContent.Demo.of(" SPACE ")).contains(DemoContent.Demo.SPACE);
        assertThat(DemoContent.Demo.of(null)).contains(DemoContent.Demo.JOURNAL);
        assertThat(DemoContent.Demo.of("1")).contains(DemoContent.Demo.JOURNAL);
        assertThat(DemoContent.Demo.of("moon")).isEmpty();
    }

    @Test
    @DisplayName("a folder's seed: its own dates, status, picture names, languages and address; a screenshot padded, not cut")
    void folderSeed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Standing standing = new Standing();
        var siteSettings = mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        when(siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID)).thenReturn(Optional.empty());
        demo.useSiteAndTheme(siteSettings, mock(io.pacod.cms.core.repository.ThemeRepository.class));
        DirectorySeed source = FolderSeed.write(dir);

        DemoContent.Result result = demo.install(source, TEST);

        assertThat(result.articles()).isEqualTo(2);
        List<Article> written = savedArticles();
        Article gateway = written.stream().filter(a -> a.getId() == 2).findFirst().orElseThrow();
        assertThat(gateway.getDate()).isEqualTo(LocalDateTime.of(2025, 2, 3, 10, 30));
        assertThat(gateway.getLastModifiedOn()).isEqualTo(LocalDateTime.of(2025, 2, 4, 8, 0));
        assertThat(gateway.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(gateway.getTexts()).extracting(TextItem::getLang).containsExactly("bg");
        assertThat(gateway.getText()).contains("<pre class=\"language-sh\"><code>make up</code></pre>");
        assertThat(storedImages).containsOnlyKeys("pacod-screen.jpg", "pacod-mark.jpg");

        // Padded to the rule's 3:2 on its background: the red frame is all there, the sides are green.
        BufferedImage screen = read(drawn.get(0));
        assertThat(screen.getWidth()).isEqualTo(FolderSeed.SHOT_HEIGHT * 3 / 2);
        assertThat(screen.getHeight()).isEqualTo(FolderSeed.SHOT_HEIGHT);
        java.awt.Color side = new java.awt.Color(screen.getRGB(10, screen.getHeight() / 2));
        assertThat(side.getGreen()).isGreaterThan(side.getRed());
        java.awt.Color frame = new java.awt.Color(screen.getRGB(screen.getWidth() / 2, 3));
        assertThat(frame.getRed()).isGreaterThan(200);

        // Bulgarian added, the seed's; German kept, the untouched standing pages hold German; the rest removed.
        assertThat(standing.languages).containsOnlyKeys("en", "bg", "de");
        assertThat(standing.pages).containsKey("pacod-gw");
        ArgumentCaptor<io.pacod.cms.core.data.SiteSettings> site = ArgumentCaptor.forClass(io.pacod.cms.core.data.SiteSettings.class);
        verify(siteSettings).save(site.capture());
        assertThat(site.getValue().getBaseUrl()).isEqualTo("https://ai.pacod.io");
    }

    @Test
    @DisplayName("a folder's seed with a problem is refused whole, every problem named, and nothing is written")
    void folderSeedWithProblems(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        new Standing();
        DirectorySeed source = FolderSeed.write(dir, FolderSeed.JSON
                .replace("\"categories\": [2], \"authors\"", "\"categories\": [9], \"authors\"")
                .replace("\"file\": \"tall.html\"", "\"file\": \"wide.html\""));

        assertThatThrownBy(() -> demo.install(source, TEST))
                .isInstanceOfSatisfying(DemoContent.Refused.class, e -> assertThat(e.reason()).isEqualTo(DemoContent.Refusal.INVALID))
                .hasMessageContaining("2 problem(s)").hasMessageContaining("[9]").hasMessageContaining("wide.html");
        nothingWritten();
        assertThat(demo.check(source, "en")).hasSize(2);
    }

    @Test
    @DisplayName("a folder's seed is read strictly: a key the seed does not have is refused, not dropped")
    void folderSeedIsReadStrictly(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        DirectorySeed source = FolderSeed.write(dir, FolderSeed.JSON.replace("\"categories\": [{", "\"categries\": [{"));
        assertThat(demo.check(source, "en")).singleElement().asString().contains("categries");
        assertThatThrownBy(() -> demo.install(source, TEST)).isInstanceOf(IllegalStateException.class);
        nothingWritten();
    }

    @Test
    @DisplayName("the shipped demos keep their own, lenient reading and pass the rules every seed keeps")
    void shippedSeedsAreNotExternal() {
        for (DemoContent.Demo shipped : DemoContent.Demo.values()) {
            assertThat(shipped.external()).isFalse();
            assertThat(shipped.seedFile()).isEqualTo(shipped.resource());
        }
        assertThat(DemoContent.Demo.SPACE.files("demo/space/photos")).contains("lantern-reef.jpg", "VENDORED.md");
    }

    private void nothingWritten() {
        verify(articles, never()).saveAll(anyIterable());
        verify(categories, never()).save(any());
        verify(authors, never()).save(any());
        verify(images, never()).save(any());
        verify(tags, never()).save(any());
        verify(menus, never()).save(any());
        verify(socialPages, never()).save(any());
    }
}
