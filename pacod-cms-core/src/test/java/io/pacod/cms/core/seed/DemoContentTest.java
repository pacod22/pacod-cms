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
