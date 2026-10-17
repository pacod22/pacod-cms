package io.pacod.cms.studio;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.services.ArticleService;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.studio.form.ArticleForm;
import io.pacod.cms.studio.form.TranslationForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Form-to-document mapping and filter precedence.
 *
 * <p>The mapping test is the important one. The console edits a subset of an article's fields,
 * and applying that subset must not disturb the rest -- most of all the multi-language
 * {@code titles} / {@code descriptions} / {@code texts} lists, which this form does not carry
 * and which a save that replaced the whole document would silently lose.
 */
class MgmtArticleServiceTest {

    private ArticleRepository articleRepository;
    private ArticleService articleService;
    private AuthorRepository authorRepository;
    private CategoryRepository categoryRepository;
    private ImageMediaRepository imageMediaRepository;
    private ImageService imageService;
    private MgmtArticleService service;

    private Article stored;

    private static Author author(int id, String first, String last) {
        Author author = new Author(id);
        author.setFirstname(first);
        author.setLastname(last);
        return author;
    }

    private static Category category(int id, String name) {
        Category category = new Category(id);
        category.setName(name);
        return category;
    }

    @BeforeEach
    void setUp() {
        articleRepository = mock(ArticleRepository.class);
        articleService = mock(ArticleService.class);
        authorRepository = mock(AuthorRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        imageMediaRepository = mock(ImageMediaRepository.class);
        imageService = mock(ImageService.class);

        service = new MgmtArticleService(articleRepository, articleService, authorRepository,
                categoryRepository, imageMediaRepository, imageService);

        stored = new Article(42);
        stored.setTitle("Старо заглавие");
        stored.setText("<p>Старо тяло</p>");
        stored.setTitles(List.of(new TextItem("Old title", "en")));
        stored.setDescriptions(List.of(new TextItem("Old description", "en")));
        stored.setTexts(List.of(new TextItem("<p>Old body</p>", "en")));
        stored.setLang("bg");
        stored.setTitleClasses("custom-title-class");
        stored.setSuppressArticleInfo(Boolean.TRUE);
        stored.setCounter(9876);
        stored.setCreatedOn(LocalDateTime.of(2020, 1, 1, 0, 0));
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3))));

        when(articleRepository.findById(42)).thenReturn(Optional.of(stored));
        when(articleRepository.save(any(Article.class))).thenAnswer(i -> i.getArgument(0));
        when(authorRepository.findAll()).thenReturn(List.of(author(7, "Мария", "Влахова"),
                author(8, "Иван", "Иванов")));
        when(categoryRepository.findAll()).thenReturn(List.of(category(17, "Двор"),
                category(18, "Цветя")));
    }

    private static ArticleForm form() {
        ArticleForm form = new ArticleForm();
        form.setTitle("Ново заглавие");
        form.setDescription("Ново описание");
        form.setText("<p>Ново тяло</p>");
        form.setEmbedded("<iframe></iframe>");
        form.setDate(LocalDateTime.of(2026, 8, 15, 9, 30));
        form.setStatus(ArticleStatus.PUBLISHED);
        form.setPriority(5);
        form.setTitleOverlayClasses("title-overlay-dark");
        form.setTags(" градина , август , ");
        form.setAuthorIds(List.of(8, 7));
        form.setCategoryIds(List.of(18));
        return form;
    }

    @Test
    @DisplayName("saving applies the edited fields")
    void saveAppliesTheForm() {
        Article saved = service.save(42, form());

        assertThat(saved.getTitle()).isEqualTo("Ново заглавие");
        assertThat(saved.getDescription()).isEqualTo("Ново описание");
        assertThat(saved.getText()).isEqualTo("<p>Ново тяло</p>");
        assertThat(saved.getEmbedded()).isEqualTo("<iframe></iframe>");
        assertThat(saved.getDate()).isEqualTo(LocalDateTime.of(2026, 8, 15, 9, 30));
        assertThat(saved.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
        // Bound as an Integer, not a String.
        assertThat(saved.getPriority()).isEqualTo(5);
        assertThat(saved.getTitleOverlayClasses()).isEqualTo("title-overlay-dark");
    }

    @Test
    @DisplayName("without content.publish a save cannot put an article in front of readers, or take it down")
    void publishingNeedsThePermission() {
        org.mockito.ArgumentCaptor<Article> written = org.mockito.ArgumentCaptor.forClass(Article.class);
        Article stored = articleRepository.findById(42).orElseThrow();

        stored.setStatus(ArticleStatus.DRAFT);
        assertThat(service.save(42, form(), false).getStatus()).as("draft stays draft").isEqualTo(ArticleStatus.DRAFT);

        stored.setStatus(ArticleStatus.PUBLISHED);
        ArticleForm unpublish = form();
        unpublish.setStatus(ArticleStatus.DRAFT);
        assertThat(service.save(42, unpublish, false).getStatus()).as("published stays published").isEqualTo(ArticleStatus.PUBLISHED);
        ArticleForm disabledControl = form();
        disabledControl.setStatus(null);
        assertThat(service.save(42, disabledControl, false).getStatus()).isEqualTo(ArticleStatus.PUBLISHED);

        stored.setStatus(ArticleStatus.NEW);
        ArticleForm draft = form();
        draft.setStatus(ArticleStatus.DRAFT);
        assertThat(service.save(42, draft, false).getStatus()).as("between unpublished states is fine").isEqualTo(ArticleStatus.DRAFT);
        assertThat(service.save(42, form(), true).getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
    }

    @Test
    @DisplayName("saving preserves every field the console does not edit")
    void savePreservesTheUneditedFields() {
        Article saved = service.save(42, form());

        // The whole reason save() is a read-modify-write rather than a replace.
        assertThat(saved.getTitles()).extracting(TextItem::getText).containsExactly("Old title");
        assertThat(saved.getDescriptions()).extracting(TextItem::getText).containsExactly("Old description");
        assertThat(saved.getTexts()).extracting(TextItem::getText).containsExactly("<p>Old body</p>");
        assertThat(saved.getLang()).isEqualTo("bg");
        assertThat(saved.getTitleClasses()).isEqualTo("custom-title-class");
        assertThat(saved.getSuppressArticleInfo()).isTrue();
        // The view counter is maintained by ArticleViewCounter and must survive an edit.
        assertThat(saved.getCounter()).isEqualTo(9876);
        assertThat(saved.getCreatedOn()).isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
        // Images are owned by their own endpoints, not by this form.
        assertThat(saved.getImages()).hasSize(1);
    }

    @Test
    @DisplayName("tags are split on commas and stripped")
    void tagsAreSplitAndStripped() {
        assertThat(service.save(42, form()).getTags()).containsExactly("градина", "август");
    }

    @Test
    @DisplayName("authors and categories keep the order the operator chose")
    void referencesResolveInSubmittedOrder() {
        Article saved = service.save(42, form());

        assertThat(saved.getAuthors()).extracting(Author::getId).containsExactly(8, 7);
        assertThat(saved.getCategories()).extracting(Category::getId).containsExactly(18);
    }

    @Test
    @DisplayName("the picker lists the article's authors first, in stored order, then the rest")
    void authorsForPickerPutsTheSelectedOnesFirst() {
        // authorRepository.findAll() is [7 Мария Влахова, 8 Иван Иванов]; allAuthors() sorts it
        // alphabetically by full name, which puts 8 ("Иван Иванов") ahead of 7.
        assertThat(service.allAuthors()).extracting(Author::getId).containsExactly(8, 7);

        // A <select multiple> submits its options in tree order, so this order IS the byline's
        // order. Rendered alphabetically, the article below would save its authors as [8, 7] --
        // silently rewriting a byline the operator never touched.
        assertThat(service.authorsForPicker(List.of(7, 8)))
                .extracting(Author::getId).containsExactly(7, 8);

        // And the ones this article does not carry follow, still alphabetically.
        assertThat(service.authorsForPicker(List.of(7)))
                .extracting(Author::getId).containsExactly(7, 8);
    }

    @Test
    @DisplayName("the picker drops an author id that no longer exists, and lists each one once")
    void authorsForPickerIsDefensiveAboutIds() {
        // 999 was deleted in another tab; 7 arrives twice.
        assertThat(service.authorsForPicker(java.util.Arrays.asList(999, 7, 7)))
                .extracting(Author::getId).containsExactly(7, 8);

        // Nothing selected is the plain alphabetical list, not an empty one.
        assertThat(service.authorsForPicker(null)).extracting(Author::getId).containsExactly(8, 7);
        assertThat(service.authorsForPicker(List.of())).extracting(Author::getId).containsExactly(8, 7);
    }

    @Test
    @DisplayName("the order the picker renders is the order a save stores")
    void pickerOrderRoundTripsThroughASave() {
        // The whole chain, which neither half proves on its own: the option order the editor
        // renders is what the browser posts, and what save() then stores for the byline to walk.
        List<Integer> rendered = service.authorsForPicker(List.of(7, 8))
                .stream().map(Author::getId).toList();

        ArticleForm form = form();
        form.setAuthorIds(rendered);

        assertThat(service.save(42, form).getAuthors())
                .extracting(Author::getId).containsExactly(7, 8);
    }

    @Test
    @DisplayName("an id that no longer exists is dropped rather than saved as a broken reference")
    void unknownReferencesAreDropped() {
        ArticleForm form = form();
        form.setAuthorIds(List.of(7, 999));

        assertThat(service.save(42, form).getAuthors()).extracting(Author::getId).containsExactly(7);
    }

    @Test
    @DisplayName("an empty style selection clears the override instead of storing an empty string")
    void blankOverlayClassBecomesNull() {
        ArticleForm form = form();
        form.setTitleOverlayClasses("");

        assertThat(service.save(42, form).getTitleOverlayClasses()).isNull();
    }

    @Test
    @DisplayName("changing the body keeps the previous one")
    void saveKeepsThePreviousBody() {
        // The editor can rewrite markup just by opening an article, so a save is not always the
        // change the operator meant to make. This is what makes that undoable.
        Article saved = service.save(42, form());

        assertThat(saved.getText()).isEqualTo("<p>Ново тяло</p>");
        assertThat(saved.getPreviousText()).isEqualTo("<p>Старо тяло</p>");
    }

    @Test
    @DisplayName("a save that leaves the body alone does not overwrite the kept version")
    void saveDoesNotClobberHistoryWhenTheBodyIsUnchanged() {
        stored.setText("<p>Ново тяло</p>");
        stored.setPreviousText("<p>The one worth keeping</p>");

        // Only the title changes here; previousText must survive, or editing an unrelated field
        // would quietly discard the recovery point.
        service.save(42, form());

        assertThat(stored.getPreviousText()).isEqualTo("<p>The one worth keeping</p>");
    }

    @Test
    @DisplayName("restoring swaps the two bodies, so restoring again undoes the restore")
    void restoreTextSwaps() {
        stored.setText("<p>rewritten by the editor</p>");
        stored.setPreviousText("<p>the decade-old original</p>");

        service.restoreText(42);
        assertThat(stored.getText()).isEqualTo("<p>the decade-old original</p>");
        assertThat(stored.getPreviousText()).isEqualTo("<p>rewritten by the editor</p>");

        service.restoreText(42);
        assertThat(stored.getText()).isEqualTo("<p>rewritten by the editor</p>");
    }

    @Test
    @DisplayName("restoring an article that has no kept version is refused, not silently a no-op")
    void restoreWithoutHistoryIsRefused() {
        stored.setPreviousText(null);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.restoreText(42))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("an unknown article is a 404, not a silently created one")
    void savingAnUnknownArticleFails() {
        when(articleRepository.findById(99)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.save(99, form()))
                .isInstanceOf(MgmtNotFoundException.class);
    }

    @Test
    @DisplayName("search wins over category, which wins over author")
    void filterPrecedenceMatchesTheOldConsole() {
        Pageable pageable = PageRequest.of(0, 20);
        PageImpl<Article> empty = new PageImpl<>(List.of());

        when(articleRepository.fulltextSearch(eq("концерт"), any())).thenReturn(empty);
        when(articleRepository.findArticleByCategories(anyInt(), any())).thenReturn(empty);
        when(articleRepository.findArticleByAuthors(anyInt(), any())).thenReturn(empty);
        when(articleRepository.findAllArticle(any(Pageable.class))).thenReturn(empty);

        service.list("концерт", 17, 7, pageable);
        verify(articleRepository).fulltextSearch("концерт", pageable);
        verify(articleRepository, never()).findArticleByCategories(anyInt(), any());

        service.list("  ", 17, 7, pageable);
        verify(articleRepository).findArticleByCategories(17, pageable);

        service.list(null, null, 7, pageable);
        verify(articleRepository).findArticleByAuthors(7, pageable);

        service.list(null, null, null, pageable);
        verify(articleRepository).findAllArticle(pageable);
    }

    @Test
    @DisplayName("creating goes through the explicit create, not the read-that-creates")
    void createUsesTheExplicitPath() {
        service.create();

        // getArticleByIdWithCreate persists a row as a side effect of a GET; the console must
        // never depend on it again.
        verify(articleService).createNewArticle();
        verify(articleService, never()).getArticleByIdWithCreate(any());
    }

    @Test
    @DisplayName("an uploaded image is attached and persisted in the same call")
    void addImageAttachesAndSaves() throws Exception {
        ImageMedia uploaded = new ImageMedia(11);
        uploaded.setFilename("garden.png");
        when(imageService.save(any())).thenReturn(uploaded);

        ImageMedia result = service.addImage(42, null);

        assertThat(result.getId()).isEqualTo(11);
        // Attached at once, not held until the next save: uploading and then navigating away
        // must not orphan the file on disk.
        assertThat(stored.getImages()).extracting(ImageMedia::getId).containsExactly(3, 11);
        verify(articleRepository).save(stored);
    }

    @Test
    @DisplayName("an eleventh image is refused")
    void imageCapIsEnforced() {
        List<ImageMedia> ten = new ArrayList<>();
        for (int i = 0; i < MgmtArticleService.MAX_IMAGES_PER_ARTICLE; i++) {
            ten.add(new ImageMedia(i + 1));
        }
        stored.setImages(ten);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.addImage(42, null))
                .isInstanceOf(io.pacod.cms.core.exceptions.MediaException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("removing an image detaches it before deleting the file")
    void removeImageDetachesFirst() {
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3), new ImageMedia(4))));

        service.removeImage(42, 3);

        assertThat(stored.getImages()).extracting(ImageMedia::getId).containsExactly(4);
    }

    /* ------------------------------------------------------- order and visibility */

    /*
     * Order is the cover. The public site takes the first VISIBLE image as the share card and as
     * every list thumbnail, and renders the rest as the gallery strip in this order -- there is no
     * cover field to keep in step, which is what makes these cases the whole mechanism rather than
     * a convenience.
     */

    @Test
    @DisplayName("arranging rewrites the order and the hidden set")
    void arrangeReordersAndHides() {
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3), new ImageMedia(4), new ImageMedia(5))));

        service.arrangeImages(42, List.of(5, 3, 4), List.of(3));

        assertThat(stored.getImages()).extracting(ImageMedia::getId).containsExactly(5, 3, 4);
        assertThat(stored.getHiddenImageIds()).containsExactly(3);
        // The cover is image 5, and would still be 5 if it were image 3 that led the list.
        assertThat(stored.visibleImages()).extracting(ImageMedia::getId).containsExactly(5, 4);
        verify(articleRepository).save(stored);
    }

    @Test
    @DisplayName("arranging ignores an id the article does not hold and keeps one it was not sent")
    void arrangeIsDefensiveAboutIds() {
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3), new ImageMedia(4))));

        // 99 belongs to another article; 4 is missing because the tab was opened before it was
        // attached. Neither may detach anything -- only removeImage does that.
        service.arrangeImages(42, List.of(99, 3), List.of(99));

        assertThat(stored.getImages()).extracting(ImageMedia::getId).containsExactly(3, 4);
        assertThat(stored.getHiddenImageIds()).isEmpty();
    }

    @Test
    @DisplayName("attaching an existing image adds it without an upload")
    void attachAddsExistingImage() throws Exception {
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3))));
        when(imageMediaRepository.findById(11)).thenReturn(Optional.of(new ImageMedia(11)));

        ImageMedia attached = service.attachImage(42, 11);

        assertThat(attached.getId()).isEqualTo(11);
        assertThat(stored.getImages()).extracting(ImageMedia::getId).containsExactly(3, 11);
        verify(imageService, never()).save(any());
        verify(articleRepository).save(stored);
    }

    @Test
    @DisplayName("attaching an image the article already holds is refused")
    void attachRefusesADuplicate() {
        // By id, not by object: ImageMedia is a Lombok @Data whose equals() includes the
        // timestamps, so two reads of the same @DBRef can compare unequal and a contains() check
        // would attach the same picture twice.
        ImageMedia held = new ImageMedia(11);
        held.setLastModifiedOn(LocalDateTime.of(2020, 1, 1, 0, 0));
        stored.setImages(new ArrayList<>(List.of(held)));

        ImageMedia reread = new ImageMedia(11);
        reread.setLastModifiedOn(LocalDateTime.of(2024, 6, 1, 0, 0));
        when(imageMediaRepository.findById(11)).thenReturn(Optional.of(reread));

        assertThat(held).isNotEqualTo(reread);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.attachImage(42, 11))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("already attached");
    }

    @Test
    @DisplayName("attaching is capped like uploading is")
    void attachEnforcesTheCap() {
        List<ImageMedia> ten = new ArrayList<>();
        for (int i = 0; i < MgmtArticleService.MAX_IMAGES_PER_ARTICLE; i++) {
            ten.add(new ImageMedia(i + 1));
        }
        stored.setImages(ten);
        when(imageMediaRepository.findById(99)).thenReturn(Optional.of(new ImageMedia(99)));

        // The cap is a property of the article, and images arrive by two routes.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.attachImage(42, 99))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("the limit");
    }

    @Test
    @DisplayName("removing an image prunes it from the hidden set")
    void removeImagePrunesTheHiddenSet() {
        // Otherwise the id outlives the image, and re-attaching that picture later brings it back
        // already hidden with nothing on screen to explain why.
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3), new ImageMedia(4))));
        stored.setHiddenImageIds(new ArrayList<>(List.of(3, 4)));

        service.removeImage(42, 3);

        assertThat(stored.getHiddenImageIds()).containsExactly(4);
    }

    @Test
    @DisplayName("an attached image always lands visible, even if its id was hidden before")
    void attachClearsAStaleHiddenId() {
        // ImageService.getNextId() is max-id-plus-one, so deleting the highest-numbered image
        // hands that id straight to the next upload -- a stale hidden id can be inherited by an
        // entirely different picture.
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3))));
        stored.setHiddenImageIds(new ArrayList<>(List.of(3, 11)));
        when(imageMediaRepository.findById(11)).thenReturn(Optional.of(new ImageMedia(11)));

        service.attachImage(42, 11);

        assertThat(stored.getHiddenImageIds()).containsExactly(3);
        assertThat(stored.visibleImages()).extracting(ImageMedia::getId).containsExactly(11);
    }

    @Test
    @DisplayName("an empty embedded-video box is stored as null, not as an empty string")
    void blankEmbeddedIsStoredAsNull() {
        // "" is what an untouched textarea posts, and a template that guards the gallery and the
        // widget thumbnail with `embedded == null` would put an empty .video-container -- a black
        // box -- where the article's images should be, on every article saved without a video.
        // The public templates test #strings.isEmpty, but the data must not carry the trap.
        ArticleForm form = form();
        form.setEmbedded("   ");

        service.save(42, form);

        assertThat(stored.getEmbedded()).isNull();
    }

    @Test
    @DisplayName("a real embedded video is stored verbatim")
    void realEmbeddedIsKept() {
        ArticleForm form = form();
        form.setEmbedded("<iframe src=\"https://www.youtube.com/embed/x\"></iframe>");

        service.save(42, form);

        assertThat(stored.getEmbedded()).isEqualTo("<iframe src=\"https://www.youtube.com/embed/x\"></iframe>");
    }

    @Test
    @DisplayName("saving the form leaves the images and the hidden set alone")
    void saveDoesNotTouchImages() {
        stored.setImages(new ArrayList<>(List.of(new ImageMedia(3), new ImageMedia(4))));
        stored.setHiddenImageIds(new ArrayList<>(List.of(4)));

        service.save(42, form());

        // The form carries neither, by design: an upload is durable the moment it succeeds and
        // must not be undone by a later save of the surrounding article.
        assertThat(stored.getImages()).extracting(ImageMedia::getId).containsExactly(3, 4);
        assertThat(stored.getHiddenImageIds()).containsExactly(4);
    }

    // ------------------------------------------------------------------ translations

    /** Two translations and an entry for the original, all immutable as MongoDB may hand them. */
    private void storeTwoTranslations() {
        stored.setTitles(List.of(new TextItem("Old title", "en"), new TextItem("Alter Titel", "de")));
        stored.setDescriptions(List.of(new TextItem("Old description", "en"), new TextItem("Alte Beschreibung", "de")));
        stored.setTexts(List.of(new TextItem("<p>Old body</p>", "en"), new TextItem("<p>Alter Text</p>", "de"),
                new TextItem("<p>bg copy</p>", "bg")));
        stored.setPreviousText("<p>kept</p>");
    }

    private static TranslationForm translation(String title, String description, String text) {
        TranslationForm form = new TranslationForm();
        form.setTitle(title);
        form.setDescription(description);
        form.setText(text);
        return form;
    }

    @Test
    @DisplayName("saving a translation writes that language and nothing else")
    void saveTranslationWritesOneLanguage() {
        storeTwoTranslations();

        Article saved = service.saveTranslation(42, "EN", translation("New title", "New description", "<p>New body</p>"));

        assertThat(saved.getTitles()).extracting(TextItem::getLang, TextItem::getText)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("de", "Alter Titel"),
                        org.assertj.core.groups.Tuple.tuple("en", "New title"));
        assertThat(saved.getDescriptions()).extracting(TextItem::getText)
                .containsExactlyInAnyOrder("Alte Beschreibung", "New description");
        assertThat(saved.getTexts()).extracting(TextItem::getText)
                .containsExactlyInAnyOrder("<p>Alter Text</p>", "<p>bg copy</p>", "<p>New body</p>");
        // The Bulgarian source and its history are not this screen's.
        assertThat(saved.getTitle()).isEqualTo("Старо заглавие");
        assertThat(saved.getText()).isEqualTo("<p>Старо тяло</p>");
        assertThat(saved.getPreviousText()).isEqualTo("<p>kept</p>");
        assertThat(saved.getLang()).isEqualTo("bg");
        assertThat(saved.getImages()).hasSize(1);
    }

    @Test
    @DisplayName("a blank translated description is removed, never stored as an empty string")
    void blankTranslationDescriptionIsRemoved() {
        storeTwoTranslations();

        Article saved = service.saveTranslation(42, "de", translation("Neuer Titel", "  ", "<p>Neu</p>"));

        // ArticleModel.getLocalizedText does not test for blank: a stored "" would be served.
        assertThat(saved.getDescriptions()).extracting(TextItem::getLang).containsExactly("en");
    }

    @Test
    @DisplayName("Bulgarian and languages the article is not translated into are refused")
    void saveTranslationRefusesBulgarianAndNewLanguages() {
        storeTwoTranslations();
        TranslationForm form = translation("t", null, "<p>x</p>");

        for (String lang : List.of("bg", "BG", "fr", "")) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.saveTranslation(42, lang, form))
                    .as(lang)
                    .isInstanceOf(MgmtOperationException.class);
        }
        verify(articleRepository, never()).save(any(Article.class));
    }

    @Test
    @DisplayName("removing a translation clears it from all three lists and leaves the others")
    void removeTranslationClearsOneLanguage() {
        storeTwoTranslations();

        assertThat(service.removeTranslation(42, "de")).isEqualTo("de");

        assertThat(stored.getTitles()).extracting(TextItem::getLang).containsExactly("en");
        assertThat(stored.getDescriptions()).extracting(TextItem::getLang).containsExactly("en");
        assertThat(stored.getTexts()).extracting(TextItem::getLang).containsExactly("en", "bg");
        assertThat(stored.translatedIn()).containsExactly("en");
        assertThat(stored.getText()).isEqualTo("<p>Старо тяло</p>");
    }

    @Test
    @DisplayName("removing the Bulgarian source is refused")
    void removeTranslationRefusesBulgarian() {
        storeTwoTranslations();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.removeTranslation(42, "bg"))
                .isInstanceOf(MgmtOperationException.class);
        verify(articleRepository, never()).save(any(Article.class));
    }

    @Test
    @DisplayName("the translation form loads that language's raw entries, never a fallback")
    void translationFormLoadsRawEntries() {
        storeTwoTranslations();
        stored.setDescriptions(List.of(new TextItem("Old description", "en")));

        TranslationForm form = TranslationForm.of(stored, "de");

        assertThat(form.getTitle()).isEqualTo("Alter Titel");
        assertThat(form.getText()).isEqualTo("<p>Alter Text</p>");
        // No German description: blank, not the English or Bulgarian one.
        assertThat(form.getDescription()).isNull();
    }
}
