package io.pacod.cms.site.data;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleModelTest {

    private static TextItem item(String lang, String text) {
        return TextItem.builder().lang(lang).text(text).build();
    }

    // ---------------------------------------------------------------- localization fallback

    @Test
    @DisplayName("returns the requested language when present")
    void requestedLanguageWins() {
        ArticleModel model = new ArticleModel(new Article(1), "de");

        String result = model.getLocalizedText(
                List.of(item("en", "english"), item("de", "deutsch")), "fallback", "de");

        assertThat(result).isEqualTo("deutsch");
    }

    @Test
    @DisplayName("falls back to English when the requested language is missing")
    void fallsBackToEnglish() {
        ArticleModel model = new ArticleModel(new Article(1), "fr");

        String result = model.getLocalizedText(
                List.of(item("en", "english"), item("de", "deutsch")), "fallback", "fr");

        assertThat(result).isEqualTo("english");
    }

    @Test
    @DisplayName("falls back to the default text when neither the language nor English is present")
    void fallsBackToDefault() {
        ArticleModel model = new ArticleModel(new Article(1), "fr");

        String result = model.getLocalizedText(List.of(item("de", "deutsch")), "fallback", "fr");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("a null list yields the default rather than throwing")
    void nullListYieldsDefault() {
        ArticleModel model = new ArticleModel(new Article(1), "en");

        assertThat(model.getLocalizedText(null, "fallback", "en")).isEqualTo("fallback");
    }

    // ---------------------------------------------------------------- memoized description

    @Test
    @DisplayName("description is the article's own, in the reader's language")
    void descriptionIsTheArticlesOwn() {
        Article article = new Article(1);
        article.setTitle("Заглавие");
        article.setDescription("Описание");
        article.setDescriptions(List.of(TextItem.builder().lang("de").text("Beschreibung").build()));

        // The scalar field is the website's own language -- Bulgarian under this module's tests.
        assertThat(new ArticleModel(article, "bg").getDescription()).isEqualTo("Описание");
        assertThat(new ArticleModel(article, "de").getDescription()).isEqualTo("Beschreibung");
    }

    @Test
    @DisplayName("an article with no description is described by its date, authors and title")
    void missingDescriptionFallsBackToTheByline() {
        Article article = new Article(1);
        article.setTitle("Title");

        assertThat(new ArticleModel(article, "xx").getDescription()).endsWith("; Title");
        assertThat(new ArticleModel(article, null).getDescription()).endsWith("; Title");
    }

    @Test
    @DisplayName("memoization returns a stable value across repeated reads")
    void descriptionIsStableAcrossCalls() {
        Article article = new Article(1);
        article.setTitle("Title");
        ArticleModel model = new ArticleModel(article, "en");

        // article.html reads ${article.description} four times per render.
        String first = model.getDescription();
        assertThat(model.getDescription()).isEqualTo(first);
        assertThat(model.getDescription()).isEqualTo(first);
        assertThat(model.getDescription()).isEqualTo(first);
    }

    // ---------------------------------------------------------------- null safety

    @Test
    @DisplayName("getTags never returns null")
    void tagsAreNeverNull() {
        ArticleModel model = new ArticleModel(new Article(1), "en");

        assertThat(model.getTags()).isNotNull().isEmpty();
        assertThat(model.hasTags()).isFalse();
    }

    // ---------------------------------------------------------------- keywords

    private static Article tagged(String... tags) {
        Article article = new Article(1);
        article.setTags(List.of(tags));
        return article;
    }

    @Test
    @DisplayName("a keyword with no tag document is still shown, under its own name")
    void unknownTagIsShown() {
        ArticleModel model = new ArticleModel(tagged("нов"), "en", List.of(
                Tag.builder().name("other").build()));

        assertThat(model.getTranslatedTags()).extracting(t -> t.translate("en")).containsExactly("нов");
    }

    @Test
    @DisplayName("a keyword with a tag document uses its translation, in the article's order")
    void knownTagIsTranslatedAndOrderKept() {
        Tag known = Tag.builder().name("фестивал")
                .translations(List.of(item("en", "festival"))).build();
        ArticleModel model = new ArticleModel(tagged("нов", "фестивал", "втори"), "en",
                List.of(known));

        assertThat(model.getTranslatedTags()).extracting(t -> t.translate("en"))
                .containsExactly("нов", "festival", "втори");
    }

    @Test
    @DisplayName("an empty or unreadable tag collection hides no keyword")
    void emptyTagCollectionHidesNothing() {
        ArticleModel empty = new ArticleModel(tagged("а", "б"), "bg", List.of());
        ArticleModel absent = new ArticleModel(tagged("а", "б"), "bg", null);

        assertThat(empty.hasTags()).isTrue();
        assertThat(empty.getTranslatedTags()).extracting(Tag::getName).containsExactly("а", "б");
        assertThat(absent.getTranslatedTags()).extracting(Tag::getName).containsExactly("а", "б");
    }

    @Test
    @DisplayName("an article with no images renders blank rather than throwing")
    void missingImagesAreSafe() {
        ArticleModel model = new ArticleModel(new Article(1), "en");

        assertThat(model.getImage()).isEmpty();
        assertThat(model.getContent()).isEmpty();
    }

    // -------------------------------------------------------------- the share card

    /**
     * The studio can attach an image purely so the operator can reference it from inside the body
     * by URL, and hide it from the public site. Such an image must not become the article's picture
     * on Facebook by virtue of having been uploaded first -- so the share card is the first
     * <em>visible</em> image, not {@code images[0]}.
     */
    @Test
    @DisplayName("the share card skips a hidden image and takes the first visible one")
    void shareCardSkipsHiddenImages() {
        Article article = new Article(1);
        article.setImages(List.of(image(3, "in-the-body.png", "image/png"),
                image(4, "cover.jpg", "image/jpeg")));
        article.setHiddenImageIds(List.of(3));

        ArticleModel model = new ArticleModel(article, "bg");

        assertThat(model.getImage()).isEqualTo("/assets/img/articles/cover.jpg");
        // The MIME type has to come from the same image, or og:image:type describes another file.
        assertThat(model.getContent()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("an article whose images are all hidden reads exactly like one with none")
    void allImagesHiddenReadsAsNoImages() {
        Article article = new Article(1);
        article.setImages(List.of(image(3, "in-the-body.png", "image/png")));
        article.setHiddenImageIds(List.of(3));

        ArticleModel model = new ArticleModel(article, "bg");

        // "" and not null: fragments/head.html tests with #strings.isEmpty precisely because a
        // null check would emit og:image pointing at the bare host.
        assertThat(model.getImage()).isEmpty();
        assertThat(model.getContent()).isEmpty();
    }

    @Test
    @DisplayName("an article with no hidden ids is unchanged: null means every image is visible")
    void absentHiddenIdsChangeNothing() {
        Article article = new Article(1);
        article.setImages(List.of(image(3, "cover.jpg", "image/jpeg")));

        assertThat(new ArticleModel(article, "bg").getImage())
                .isEqualTo("/assets/img/articles/cover.jpg");
    }

    private static ImageMedia image(int id, String filename, String contentType) {
        ImageMedia image = new ImageMedia(id);
        image.setFilename(filename);
        image.setContent(contentType);
        return image;
    }

    @Test
    @DisplayName("an entirely empty article still renders -- this backs the home page fallback")
    void emptyArticleIsRenderable() {
        ArticleModel model = new ArticleModel(new Article(), "en", List.of());

        // The home page constructs exactly this when the database has no articles.
        assertThat(model.getTags()).isEmpty();
        assertThat(model.getDateAsText()).isEmpty();
        assertThat(model.getIssue()).isEmpty();
        assertThat(model.getAuthorsAsText()).isEmpty();
        assertThat(model.getDescription()).isNotNull();
        assertThat(model.showArticleInfo()).isTrue();
        assertThat(model.getTranslatedTags()).isNotNull();
    }

    @Test
    @DisplayName("hasTranslation is false for Bulgarian and for a null language")
    void hasTranslationBaseline() {
        Article article = new Article(1);
        article.setTitles(List.of(item("de", "Titel")));
        ArticleModel model = new ArticleModel(article, "de");

        assertThat(model.hasTranslation("de")).isTrue();
        assertThat(model.hasTranslation("bg")).isFalse();
        assertThat(model.hasTranslation(null)).isFalse();
        assertThat(model.hasTranslation("fr")).isFalse();
    }
}
