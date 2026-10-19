package io.pacod.cms.core.data;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleTest {

    @Test
    @DisplayName("copy constructor carries priority")
    void copyConstructorCopiesPriority() {
        Article original = new Article(7);
        original.setPriority(42);

        Article copy = new Article(original);

        assertThat(copy.getPriority()).isEqualTo(42);
    }

    @Test
    @DisplayName("copy constructor round-trips every field the view layer reads")
    void copyConstructorRoundTrips() {
        LocalDateTime now = LocalDateTime.now();
        Article original = new Article(1);
        original.setTitle("title");
        original.setText("text");
        original.setDescription("description");
        original.setPriority(3);
        original.setCounter(99);
        original.setLang("en");
        original.setDate(now);
        original.setStatus(ArticleStatus.PUBLISHED);
        original.setTags(List.of("alpha", "beta"));
        original.setTitleClasses("tc");
        original.setTitleOverlayClasses("toc");
        original.setSuppressArticleInfo(true);

        Article copy = new Article(original);

        assertThat(copy.getId()).isEqualTo(1);
        assertThat(copy.getTitle()).isEqualTo("title");
        assertThat(copy.getText()).isEqualTo("text");
        assertThat(copy.getDescription()).isEqualTo("description");
        assertThat(copy.getPriority()).isEqualTo(3);
        assertThat(copy.getCounter()).isEqualTo(99);
        assertThat(copy.getLang()).isEqualTo("en");
        assertThat(copy.getDate()).isEqualTo(now);
        assertThat(copy.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(copy.getTags()).containsExactly("alpha", "beta");
        assertThat(copy.getTitleClasses()).isEqualTo("tc");
        assertThat(copy.getTitleOverlayClasses()).isEqualTo("toc");
        assertThat(copy.getSuppressArticleInfo()).isTrue();
    }

    @Test
    @DisplayName("setting the counter does not stamp lastModifiedOn")
    void setCounterDoesNotTouchLastModified() {
        Article article = new Article(1);
        article.setTitle("seed");
        LocalDateTime stampedByTitle = article.getLastModifiedOn();

        article.setCounter(500);

        // The view-counter path relies on this: recording a view must not look like an edit.
        assertThat(article.getLastModifiedOn()).isEqualTo(stampedByTitle);
    }

    @Test
    @DisplayName("tags can be cleared: an empty list clears them")
    void tagsCanBeCleared() {
        Article article = new Article(1);
        article.setTags(List.of("alpha", "beta"));
        assertThat(article.getTags()).hasSize(2);

        article.setTags(new ArrayList<>());

        assertThat(article.getTags()).isEmpty();
    }

    @Test
    @DisplayName("null tags clear rather than being ignored")
    void nullTagsClear() {
        Article article = new Article(1);
        article.setTags(List.of("alpha"));

        article.setTags(null);

        assertThat(article.getTags()).isEmpty();
    }

    @Test
    @DisplayName("blank tags are dropped and surrounding whitespace stripped")
    void tagsAreNormalized() {
        Article article = new Article(1);

        article.setTags(List.of("  alpha  ", "   ", "beta"));

        assertThat(article.getTags()).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("translatedIn lists the languages the body exists in, sorted and deduplicated")
    void translatedInListsBodyLanguages() {
        Article article = new Article(1);
        article.setText("<p>български</p>");
        article.setTexts(List.of(
                new TextItem("<p>deutsch</p>", "de"),
                new TextItem("<p>english</p>", "en"),
                new TextItem("<p>english again</p>", "EN")));

        assertThat(article.translatedIn()).containsExactly("de", "en");
    }

    @Test
    @DisplayName("translatedIn excludes bg: the source is the scalar text, and a bg entry is damage")
    void translatedInExcludesTheSourceLanguage() {
        Article article = new Article(1);
        article.setText("<p>български</p>");
        // A batch tool that copied the original into texts would leave exactly this. Listed
        // verbatim it would put a BG chip on the article and read as "translated into Bulgarian".
        article.setTexts(List.of(new TextItem("<p>български</p>", "bg")));

        assertThat(article.translatedIn()).isEmpty();
    }

    @Test
    @DisplayName("translatedIn skips blank texts and entries with no language")
    void translatedInSkipsIncompleteEntries() {
        Article article = new Article(1);
        article.setTexts(new ArrayList<>(List.of(
                new TextItem("   ", "fr"),
                new TextItem(null, "es"),
                new TextItem("<p>italiano</p>", null),
                new TextItem("<p>rom\u00e2n\u0103</p>", "ro"))));

        // A translated title with no body behind it is not a translation anybody can read -- the
        // rule MgmtLanguageService.pageCounts() states for pages.
        assertThat(article.translatedIn()).containsExactly("ro");
    }

    @Test
    @DisplayName("translatedIn returns an empty list rather than null for an untranslated article")
    void translatedInIsEmptyWithoutTexts() {
        Article article = new Article(1);
        article.setText("<p>\u0431\u044a\u043b\u0433\u0430\u0440\u0441\u043a\u0438</p>");

        assertThat(article.translatedIn()).isEmpty();
    }

    @Test
    @DisplayName("the language list never leaves the server as a field")
    void translatedInIsNotSerialized() throws Exception {
        Article article = new Article(1);
        article.setText("<p>current</p>");
        article.setTexts(List.of(new TextItem("<p>english</p>", "en")));

        String json = JsonMapper.builder()
                .disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES)
                .build()
                .writeValueAsString(article);

        // Spelled translatedIn() rather than getTranslatedIn() for exactly this: Article is
        // serialized straight out of the public /web/v1 controllers, and a getter would add a
        // language array to every article response.
        assertThat(json).doesNotContain("translatedIn");
    }

    @Test
    @DisplayName("the kept previous body never leaves the server")
    void previousTextIsNotSerialized() throws Exception {
        Article article = new Article(1);
        article.setText("<p>current</p>");
        article.setPreviousText("<p>a superseded draft</p>");

        // The applications' mapper is Spring Boot's, which registers jsr310 via the web starter;
        // this module has no web starter, so the date handling is switched off rather than
        // dragging that dependency in. What is under test is which fields appear, not how a
        // LocalDateTime is formatted.
        String json = JsonMapper.builder()
                .disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES)
                .build()
                .writeValueAsString(article);

        // Article is serialized straight out of GET /web/v1/article/{id}, which is public and
        // unauthenticated. Without @JsonIgnore every edited article would ship its previous body
        // to the site alongside the current one -- double the payload, and a draft nobody chose
        // to publish.
        assertThat(json).contains("<p>current</p>");
        assertThat(json).doesNotContain("previousText");
        assertThat(json).doesNotContain("a superseded draft");
    }

    @Test
    @DisplayName("the cover is the first visible image with a filename, and is not serialized")
    void coverIsTheFirstVisibleImage() throws Exception {
        Article article = new Article(1);
        assertThat(article.coverFilename()).isNull();

        ImageMedia hidden = new ImageMedia(5);
        hidden.setFilename("hidden.jpg");
        ImageMedia unnamed = new ImageMedia(6);
        ImageMedia shown = new ImageMedia(7);
        shown.setFilename("shown.jpg");
        article.setImages(new ArrayList<>(List.of(hidden, unnamed, shown)));
        article.setHiddenImageIds(List.of(5));

        // Hidden from the public site, so not the cover anywhere -- ArticleModel.getImage()'s rule.
        assertThat(article.coverFilename()).isEqualTo("shown.jpg");

        // Spelled coverFilename(), not getCoverFilename(), for visibleImages()' reason.
        String json = JsonMapper.builder()
                .disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES)
                .build()
                .writeValueAsString(article);
        assertThat(json).doesNotContain("coverFilename");
    }

    @Test
    @DisplayName("titleIn answers one language exactly; Bulgarian falls back to the source title")
    void titleInIsExact() {
        Article article = new Article(1);
        article.setTitle("Есенният фестивал");
        assertThat(article.titleIn("bg")).isEqualTo("Есенният фестивал");
        assertThat(article.titleIn("en")).isNull();

        article.setTitles(List.of(TextItem.builder().lang("en").text("The autumn festival").build(),
                TextItem.builder().lang("de").text("  ").build(),
                TextItem.builder().lang("bg").text("Фестивал").build()));
        assertThat(article.titleIn("EN")).isEqualTo("The autumn festival");
        // A titles entry for Bulgarian is what the public listings show, so it wins over the scalar.
        assertThat(article.titleIn("bg")).isEqualTo("Фестивал");
        // Blank is absent, and there is no fallback to another language here.
        assertThat(article.titleIn("de")).isNull();
        assertThat(article.titleIn(null)).isNull();
    }

    @Test
    @DisplayName("textIn and descriptionIn follow titleIn's rule: exact language, the scalar for the original")
    void textAndDescriptionInAreExact() {
        Article article = new Article(1);
        article.setText("<p>Тяло</p>");
        article.setDescription("Описание");
        article.setTexts(List.of(TextItem.builder().lang("de").text("<p>Text</p>").build()));
        article.setDescriptions(List.of(TextItem.builder().lang("de").text(" ").build()));

        assertThat(article.textIn("bg")).isEqualTo("<p>Тяло</p>");
        assertThat(article.textIn("de")).isEqualTo("<p>Text</p>");
        assertThat(article.textIn("en")).isNull();
        assertThat(article.descriptionIn("bg")).isEqualTo("Описание");
        assertThat(article.descriptionIn("de")).isNull();
    }

    @Test
    @DisplayName("textIn and descriptionIn add nothing to the public JSON")
    void textInIsNotSerialized() throws Exception {
        Article article = new Article(3);
        article.setText("<p>Тяло</p>");
        String json = JsonMapper.builder()
                .disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES)
                .build()
                .writeValueAsString(article);
        assertThat(json).doesNotContain("textIn").doesNotContain("descriptionIn");
    }
}
