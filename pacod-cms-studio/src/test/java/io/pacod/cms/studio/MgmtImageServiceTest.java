package io.pacod.cms.studio;

import com.mongodb.DBRef;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.studio.MgmtImageService.ArticleFilter;
import io.pacod.cms.studio.MgmtImageService.ImageFile;
import io.pacod.cms.studio.MgmtImageService.ImageRow;
import io.pacod.cms.studio.MgmtImageService.ImageSort;
import io.pacod.cms.studio.MgmtImageService.ImageUsage;
import io.pacod.cms.studio.MgmtImageService.Usage;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The image library: finding images, and refusing to delete one that is still in use.
 *
 * <p>The deletion cases are the ones that matter. One picture can be on several articles, and an
 * unconditional delete would break the pages that were not being edited.
 */
class MgmtImageServiceTest {

    private ImageMediaRepository imageMediaRepository;
    private ArticleRepository articleRepository;
    private ImageService imageService;
    private MongoTemplate mongoTemplate;
    private MgmtImageService service;

    private static ImageMedia image(int id, String filename) {
        ImageMedia image = new ImageMedia(id);
        image.setFilename(filename);
        image.setContent("image/jpeg");
        return image;
    }

    @TempDir
    Path imageFolder;

    @BeforeEach
    void setUp() {
        imageMediaRepository = mock(ImageMediaRepository.class);
        articleRepository = mock(ArticleRepository.class);
        imageService = mock(ImageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new MgmtImageService(imageMediaRepository, articleRepository, imageService,
                mongoTemplate, imageFolder.toString());
    }

    @Test
    @DisplayName("a row carries the public path and the number of articles using the image")
    void rowCarriesPathAndUsage() {
        when(articleRepository.countArticleByImages(3)).thenReturn(2L);

        ImageRow row = service.row(image(3, "garden.jpg"));

        // The value the operator copies is the value the picker inserts into the body, computed
        // once here rather than concatenated in two templates and a script.
        assertThat(row.path()).isEqualTo("/assets/img/articles/garden.jpg");
        assertThat(row.usages()).isEqualTo(2);
    }

    /** What the picker's default ordering turns a plain page request into. */
    private static Pageable byId(int page) {
        return PageRequest.of(page, 24, Sort.by(Sort.Direction.ASC, "_id"));
    }

    @Test
    @DisplayName("a blank search reads the whole collection, paged")
    void blankSearchReadsEverything() {
        Pageable pageable = byId(0);
        when(imageMediaRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(image(1, "a.jpg"), image(2, "b.jpg")), pageable, 2));

        Page<ImageRow> page = service.search("  ", ImageSort.ID, false, PageRequest.of(0, 24));

        assertThat(page.getContent()).extracting(ImageRow::filename).containsExactly("a.jpg", "b.jpg");
        // Not findByFilenameContainingIgnoreCase(""): that would be a regex match on every
        // document rather than a plain paged read.
        verify(imageMediaRepository, never()).findByFilenameContainingIgnoreCase(any(), any());
    }

    @Test
    @DisplayName("the picker orders by id ascending unless told otherwise")
    void searchDefaultsToIdAscending() {
        // The whole point of the assertion is the Sort: an unsorted paged query has no defined
        // order between pages, so the same image can arrive twice and another never at all.
        when(imageMediaRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(image(1, "a.jpg")), byId(0), 1));

        // A null key is the default, which is what an absent ?sort= resolves to.
        service.search(null, null, false, PageRequest.of(0, 24));
        service.search(null, ImageSort.ID, false, PageRequest.of(0, 24));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageMediaRepository, org.mockito.Mockito.times(2)).findAll(captor.capture());

        assertThat(captor.getAllValues()).allSatisfy(pageable ->
                assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "_id")));
    }

    @Test
    @DisplayName("«име» sorts by the filename, and the direction reverses it")
    void searchByName() {
        when(imageMediaRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(image(1, "a.jpg")), byId(0), 1));

        service.search(null, ImageSort.NAME, false, PageRequest.of(0, 24));
        service.search(null, ImageSort.NAME, true, PageRequest.of(0, 24));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageMediaRepository, org.mockito.Mockito.times(2)).findAll(captor.capture());

        assertThat(captor.getAllValues()).extracting(Pageable::getSort).containsExactly(
                Sort.by(Sort.Direction.ASC, "filename"),
                Sort.by(Sort.Direction.DESC, "filename"));
    }

    @Test
    @DisplayName("a term matches filenames")
    void searchMatchesFilenames() {
        Pageable pageable = byId(0);
        when(imageMediaRepository.findByFilenameContainingIgnoreCase("gard", pageable))
                .thenReturn(new PageImpl<>(List.of(image(3, "garden.jpg")), pageable, 1));

        assertThat(service.search("gard", ImageSort.ID, false, PageRequest.of(0, 24)).getContent())
                .extracting(ImageRow::id).containsExactly(3);
    }

    @Test
    @DisplayName("a number finds the image with that id, on the first page only")
    void numericSearchResolvesTheId() {
        Pageable first = byId(0);
        when(imageMediaRepository.findById(17)).thenReturn(Optional.of(image(17, "seventeen.jpg")));
        when(imageMediaRepository.findByFilenameContainingIgnoreCase("17", first))
                .thenReturn(new PageImpl<>(List.of(image(117, "photo-17.jpg")), first, 1));

        // An id is what every other console screen shows, and the /assets/img/articles/ tree does
        // not: without this there is no way to go from a number to a file.
        assertThat(service.search("17", ImageSort.ID, false, PageRequest.of(0, 24)).getContent())
                .extracting(ImageRow::id).containsExactly(17, 117);

        Pageable second = byId(1);
        when(imageMediaRepository.findByFilenameContainingIgnoreCase("17", second))
                .thenReturn(new PageImpl<>(List.of(image(217, "x-17.jpg")), second, 3));

        // Repeating it on every page is the obvious way to get this wrong.
        assertThat(service.search("17", ImageSort.ID, false, PageRequest.of(1, 24)).getContent())
                .extracting(ImageRow::id).containsExactly(217);
    }

    /** The $unwind/$group over every article's images, with the counts kept. */
    private void stubUsageCounts(Map<Integer, Integer> counts) {
        List<Document> rows = counts.entrySet().stream()
                .map(entry -> new Document("_id", new DBRef("image", entry.getKey()))
                        .append("n", entry.getValue()))
                .toList();

        when(mongoTemplate.aggregate(any(Aggregation.class), eq("article"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(rows, new Document()));
    }

    @Test
    @DisplayName("«статии» orders by how many articles hold the image, zero included")
    void searchByArticleCount() {
        // 7 is deliberately absent from the aggregation rather than present with a zero: that is
        // exactly what MongoDB returns, and reading it as "no such image" is the trap
        // MgmtCategoryService.countArticlesByCategory documents.
        stubUsageCounts(Map.of(3, 2, 5, 9));
        when(imageMediaRepository.findAll())
                .thenReturn(List.of(image(3, "c.jpg"), image(5, "a.jpg"), image(7, "b.jpg")));

        Page<ImageRow> ascending = service.search(null, ImageSort.ARTICLES, false, PageRequest.of(0, 24));

        assertThat(ascending.getContent()).extracting(ImageRow::id).containsExactly(7, 3, 5);
        // The count comes back with the row, so no per-row count query is run for it.
        assertThat(ascending.getContent()).extracting(ImageRow::usages).containsExactly(0L, 2L, 9L);
        verify(articleRepository, never()).countArticleByImages(any());

        Page<ImageRow> descending = service.search(null, ImageSort.ARTICLES, true, PageRequest.of(0, 24));

        // The two directions are exact inverses of one another -- MgmtSort's rule, and the reason
        // the id is part of the key rather than a nullsLast on the count.
        assertThat(descending.getContent()).extracting(ImageRow::id).containsExactly(5, 3, 7);
    }

    @Test
    @DisplayName("«статии» pages over the whole ordering, not over one page of it")
    void searchByArticleCountPages() {
        stubUsageCounts(Map.of(1, 1, 2, 2, 3, 3));
        when(imageMediaRepository.findAll())
                .thenReturn(List.of(image(1, "a.jpg"), image(2, "b.jpg"), image(3, "c.jpg")));

        Page<ImageRow> second = service.search(null, ImageSort.ARTICLES, true, PageRequest.of(1, 2));

        assertThat(second.getContent()).extracting(ImageRow::id).containsExactly(1);
        // The total is the candidates, not the used set: a smaller one would make hasNext() false
        // with rows still unshown, and «Покажи още» would disappear early.
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    @DisplayName("«статии» narrows to the filename term, and an exact number still leads")
    void searchByArticleCountUnderATerm() {
        stubUsageCounts(Map.of(11, 4));
        when(imageMediaRepository.findByFilenameContainingIgnoreCase("11"))
                .thenReturn(List.of(image(11, "photo-11.jpg"), image(211, "x-11.jpg")));
        when(imageMediaRepository.findById(11)).thenReturn(Optional.of(image(11, "photo-11.jpg")));

        Page<ImageRow> page = service.search("11", ImageSort.ARTICLES, false, PageRequest.of(0, 24));

        // 211 has no articles and would lead an ascending count ordering; the exact number wins
        // the first slot under every key, as page() gives it under the other two. Once, not twice.
        assertThat(page.getContent()).extracting(ImageRow::id).containsExactly(11, 211);
        assertThat(page.getTotalElements()).isEqualTo(2);
        verify(imageMediaRepository, never()).findAll();
    }

    @Test
    @DisplayName("«статии» orders within the article filter, off the filter's own ids")
    void searchByArticleCountUnderAnArticleFilter() {
        stubUsageCounts(Map.of(3, 5, 9, 1));
        ArticleFilter filter = new ArticleFilter("42", List.of(9, 3), 1, false);
        when(imageMediaRepository.findAllById(List.of(9, 3)))
                .thenReturn(List.of(image(9, "cover.jpg"), image(3, "second.jpg")));

        Page<ImageRow> page = service.search(null, filter, Usage.ANY,
                ImageSort.ARTICLES, true, PageRequest.of(0, 24));

        assertThat(page.getContent()).extracting(ImageRow::id).containsExactly(3, 9);
        // The restriction is already a bounded id list, so the whole collection is never read.
        verify(imageMediaRepository, never()).findAll();
    }

    @Test
    @DisplayName("«статии» under «неизползвани» is stable, and cannot surface a used image")
    void searchByArticleCountUnderTheUnusedFilter() {
        stubUsageCounts(Map.of(3, 4));
        when(imageMediaRepository.findAll())
                .thenReturn(List.of(image(3, "shared.jpg"), image(11, "orphan.jpg"), image(7, "spare.jpg")));

        Page<ImageRow> page = service.search(null, null, Usage.UNUSED,
                ImageSort.ARTICLES, true, PageRequest.of(0, 24));

        // Every row here counts zero by definition, so the ordering falls through to the id --
        // a stable list rather than an arbitrary one. Image 3 is used and must not appear at the
        // top of a list whose entire claim is the opposite, whatever it is ordered by.
        assertThat(page.getContent()).extracting(ImageRow::id).containsExactly(11, 7);
        assertThat(page.getContent()).extracting(ImageRow::usages).containsOnly(0L);
    }

    @Test
    @DisplayName("an unrecognised sort key is the default rather than a property path")
    void sortParsingIsAnAllowList() {
        // It arrives off a query string and ends up in Sort.by. Anything not on the list is `id`.
        assertThat(ImageSort.of(null)).isEqualTo(ImageSort.ID);
        assertThat(ImageSort.of("  Articles ")).isEqualTo(ImageSort.ARTICLES);
        assertThat(ImageSort.of("name")).isEqualTo(ImageSort.NAME);
        assertThat(ImageSort.of("filename")).isEqualTo(ImageSort.ID);
        assertThat(ImageSort.of("id; drop")).isEqualTo(ImageSort.ID);
    }

    @Test
    @DisplayName("an image still used by an article cannot be deleted")
    void deleteIsRefusedWhileInUse() {
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(image(3, "shared.jpg")));
        when(articleRepository.countArticleByImages(3)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(3))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("2");

        // The refusal has to happen before the file is touched, not be reported after it.
        verify(imageService, never()).deleteImage(anyInt());
    }

    @Test
    @DisplayName("an unused image is deleted, file and document")
    void deleteRemovesAnUnusedImage() {
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(image(3, "orphan.jpg")));
        when(articleRepository.countArticleByImages(3)).thenReturn(0L);
        when(imageService.deleteImage(3)).thenReturn(true);

        service.delete(3);

        verify(imageService).deleteImage(3);
    }

    @Test
    @DisplayName("a file that could not be deleted is reported, not swallowed")
    void deleteReportsAFailedFileDelete() {
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(image(3, "locked.jpg")));
        when(articleRepository.countArticleByImages(3)).thenReturn(0L);
        // ImageService keeps the record when the file survives, so the two cannot diverge. A
        // silent false would look on screen exactly like a successful delete.
        when(imageService.deleteImage(3)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(3))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("deleting an image that is not there is a 404, not a silent success")
    void deleteUnknownImage() {
        when(imageMediaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99))
                .isInstanceOf(MgmtNotFoundException.class);
    }

    @Test
    @DisplayName("the articles holding an image are named, not just counted")
    void usedByNamesTheArticles() {
        Article article = new Article();
        article.setId(42);
        article.setTitle("Пролетен концерт");
        article.setStatus(ArticleStatus.PUBLISHED);

        when(articleRepository.findArticleByImages(3)).thenReturn(List.of(article));

        assertThat(service.usedBy(3))
                .extracting(ImageUsage::id, ImageUsage::title, ImageUsage::status)
                .containsExactly(tuple(42, "Пролетен концерт", ArticleStatus.PUBLISHED));
    }

    @Test
    @DisplayName("an image nothing holds has an empty usage list, and the repository is asked once")
    void usedByReadsTheRepositoryOnce() {
        when(articleRepository.findArticleByImages(3)).thenReturn(List.of());

        assertThat(service.usedBy(3)).isEmpty();

        // The count query answers the same question and the detail screen must not run both.
        verify(articleRepository, never()).countArticleByImages(anyInt());
    }

    @Test
    @DisplayName("the file's size and dimensions are read off the disk")
    void fileFactsComeFromTheDisk() throws IOException {
        writeImage("garden.jpg", 600, 400);

        ImageFile file = service.fileOf(image(3, "garden.jpg"));

        assertThat(file.present()).isTrue();
        assertThat(file.bytes()).isGreaterThan(0);
        assertThat(file.width()).isEqualTo(600);
        assertThat(file.height()).isEqualTo(400);
        assertThat(file.modifiedOn()).isNotNull();
    }

    @Test
    @DisplayName("a record whose file is gone reports absent rather than throwing")
    void fileFactsForAMissingFile() {
        // ImageService.deleteImage logs "file was already absent; removing the orphaned DB
        // record", so this state occurs. Without this screen its only symptom is a broken <img>
        // on the public site.
        ImageFile file = service.fileOf(image(3, "never-uploaded.jpg"));

        assertThat(file.present()).isFalse();
        assertThat(file.width()).isNull();
    }

    @Test
    @DisplayName("a file no reader recognises still reports its size")
    void fileFactsForAnUnreadableFile() throws IOException {
        Files.writeString(imageFolder.resolve("broken.jpg"), "this is not an image");

        ImageFile file = service.fileOf(image(3, "broken.jpg"));

        assertThat(file.present()).isTrue();
        assertThat(file.bytes()).isEqualTo(20);
        // Absent dimensions and an absent file are different facts, and the screen says which.
        assertThat(file.width()).isNull();
    }

    @Test
    @DisplayName("a stored filename cannot read its way out of the image folder")
    void fileFactsRefuseToEscapeTheFolder() throws IOException {
        Files.writeString(imageFolder.getParent().resolve("outside.txt"), "secret");

        assertThat(service.fileOf(image(3, "../outside.txt")).present()).isFalse();
    }

    @Test
    @DisplayName("saving writes the caption and leaves the filename alone")
    void saveCaptionWritesOnlyTheCaption() {
        ImageMedia stored = image(3, "garden.jpg");
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(stored));
        when(imageMediaRepository.save(any(ImageMedia.class))).thenAnswer(i -> i.getArgument(0));

        service.saveCaption(3, "  Стар град  ");

        ArgumentCaptor<ImageMedia> saved = ArgumentCaptor.forClass(ImageMedia.class);
        verify(imageMediaRepository).save(saved.capture());
        assertThat(saved.getValue().getCaption()).isEqualTo("Стар град");
        // save() writes the whole document. The filename locates the file on disk and is a unique
        // index, so the one thing this must never do is carry a new one in.
        assertThat(saved.getValue().getFilename()).isEqualTo("garden.jpg");
    }

    @Test
    @DisplayName("a blank caption is stored as absent, not as an empty string")
    void saveCaptionBlanksToNull() {
        ImageMedia stored = image(3, "garden.jpg");
        stored.setCaption("MultipartFile resource [file]");
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(stored));
        when(imageMediaRepository.save(any(ImageMedia.class))).thenAnswer(i -> i.getArgument(0));

        service.saveCaption(3, "   ");

        ArgumentCaptor<ImageMedia> saved = ArgumentCaptor.forClass(ImageMedia.class);
        verify(imageMediaRepository).save(saved.capture());
        assertThat(saved.getValue().getCaption()).isNull();
    }

    @Test
    @DisplayName("alt text per language: set, replaced, blank removed, a code of the wrong shape ignored; caption alongside")
    void saveDescription() {
        ImageMedia stored = image(3, "garden.jpg");
        stored.setAlts(new java.util.ArrayList<>(List.of(new io.pacod.cms.core.data.TextItem("Old", "de"),
                new io.pacod.cms.core.data.TextItem("Gone", "fr"))));
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(stored));
        when(imageMediaRepository.save(any(ImageMedia.class))).thenAnswer(i -> i.getArgument(0));

        java.util.Map<String, String> alts = new java.util.LinkedHashMap<>();
        alts.put("de", " Ein Garten ");
        alts.put("fr", "  ");
        alts.put("../x", "nope");
        alts.put("en", "A garden");
        service.saveDescription(3, "Caption", alts);

        ArgumentCaptor<ImageMedia> saved = ArgumentCaptor.forClass(ImageMedia.class);
        verify(imageMediaRepository).save(saved.capture());
        assertThat(saved.getValue().getAlts()).extracting(io.pacod.cms.core.data.TextItem::getLang, io.pacod.cms.core.data.TextItem::getText)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("de", "Ein Garten"),
                        org.assertj.core.groups.Tuple.tuple("en", "A garden"));
        assertThat(saved.getValue().getCaption()).isEqualTo("Caption");
        assertThat(saved.getValue().getFilename()).isEqualTo("garden.jpg");
    }

    @Test
    @DisplayName("captioning an image that is not there is a 404")
    void saveCaptionForAnUnknownImage() {
        when(imageMediaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveCaption(99, "x"))
                .isInstanceOf(MgmtNotFoundException.class);
    }

    /**
     * The aggregation's shape, not a convenience: the group key is the whole {@code @DBRef}
     * document, because {@code images.$id} is not a legal aggregation expression. A test that
     * returned bare integers would pass against code that could never work.
     */
    private void stubUsedImageIds(Integer... ids) {
        List<Document> rows = Stream.of(ids)
                .map(id -> new Document("_id", new Document("$ref", "image").append("$id", id)))
                .toList();

        when(mongoTemplate.aggregate(any(Aggregation.class), eq("article"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(rows, new Document()));
    }

    @Test
    @DisplayName("the used set is read off the DBRef group key, in either decoded shape")
    void usedImageIdsReadsTheGroupKey() {
        stubUsedImageIds(3, 9);

        assertThat(service.usedImageIds()).containsExactlyInAnyOrder(3, 9);

        // The driver hands back a DBRef against the running stack; MgmtCategoryService reads the
        // same key as a Document. Reading only one of the two would not fail -- it would return an
        // empty set, and the screen would offer every image in the library for deletion.
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("article"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(
                        List.of(new Document("_id", new DBRef("image", 11))), new Document()));

        assertThat(service.usedImageIds()).containsExactly(11);
    }

    @Test
    @DisplayName("«неизползвани» is the complement of the used set, in one query")
    void unusedFilterExcludesTheUsedIds() {
        Pageable pageable = PageRequest.of(0, 24);
        // Every search sorts; id ascending is the default, so this is what the repository sees.
        Pageable sorted = byId(0);
        stubUsedImageIds(3, 9);
        when(imageMediaRepository.findByIdNotIn(Set.of(3, 9), sorted))
                .thenReturn(new PageImpl<>(List.of(image(11, "orphan.jpg")), sorted, 1));

        Page<ImageRow> page = service.search("", null, Usage.UNUSED, ImageSort.ID, false, pageable);

        assertThat(page.getContent()).extracting(ImageRow::id).containsExactly(11);
        // Not by reading every image and counting references per row: that is a query per row on a
        // screen whose whole point is scanning the collection.
        verify(imageMediaRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("«използвани» is the same set the other way round")
    void usedFilterRestrictsToTheUsedIds() {
        Pageable pageable = PageRequest.of(0, 24);
        // Every search sorts; id ascending is the default, so this is what the repository sees.
        Pageable sorted = byId(0);
        stubUsedImageIds(3);
        when(imageMediaRepository.findByIdIn(Set.of(3), sorted))
                .thenReturn(new PageImpl<>(List.of(image(3, "shared.jpg")), sorted, 1));

        assertThat(service.search(null, null, Usage.USED, ImageSort.ID, false, pageable).getContent())
                .extracting(ImageRow::id).containsExactly(3);
    }

    @Test
    @DisplayName("a filename term narrows within the usage filter rather than replacing it")
    void unusedFilterCombinesWithTheTerm() {
        Pageable pageable = PageRequest.of(0, 24);
        // Every search sorts; id ascending is the default, so this is what the repository sees.
        Pageable sorted = byId(0);
        stubUsedImageIds(3);
        when(imageMediaRepository.findByIdNotInAndFilenameContainingIgnoreCase(Set.of(3), "gard", sorted))
                .thenReturn(new PageImpl<>(List.of(image(11, "garden-2.jpg")), sorted, 1));

        assertThat(service.search("gard", null, Usage.UNUSED, ImageSort.ID, false, pageable).getContent())
                .extracting(ImageRow::id).containsExactly(11);
    }

    @Test
    @DisplayName("the exact-number shortcut obeys the usage filter")
    void numericTermCannotEscapeTheUsageFilter() {
        Pageable pageable = PageRequest.of(0, 24);
        // Every search sorts; id ascending is the default, so this is what the repository sees.
        Pageable sorted = byId(0);
        stubUsedImageIds(9);
        when(imageMediaRepository.findByIdNotInAndFilenameContainingIgnoreCase(Set.of(9), "9", sorted))
                .thenReturn(new PageImpl<>(List.of(), sorted, 0));

        // Image 9 IS used. Surfacing it at the top of a list whose entire claim is "these are
        // unused" would send the operator to delete something the site is rendering.
        assertThat(service.search("9", null, Usage.UNUSED, ImageSort.ID, false, pageable).getContent()).isEmpty();
        verify(imageMediaRepository, never()).findById(9);
    }

    @Test
    @DisplayName("the exact-number shortcut still fires when the filter allows it")
    void numericTermSurvivesTheUsageFilter() {
        Pageable pageable = PageRequest.of(0, 24);
        // Every search sorts; id ascending is the default, so this is what the repository sees.
        Pageable sorted = byId(0);
        stubUsedImageIds(3);
        when(imageMediaRepository.findById(11)).thenReturn(Optional.of(image(11, "orphan.jpg")));
        when(imageMediaRepository.findByIdNotInAndFilenameContainingIgnoreCase(Set.of(3), "11", sorted))
                .thenReturn(new PageImpl<>(List.of(image(211, "x-11.jpg")), sorted, 1));

        // The mirror of the test below: a predicate that refused everything would pass that one and
        // silently drop the shortcut from both filtered branches, which is the whole reason the
        // restriction is threaded through as a predicate rather than applied afterwards.
        assertThat(service.search("11", null, Usage.UNUSED, ImageSort.ID, false, pageable).getContent())
                .extracting(ImageRow::id).containsExactly(11, 211);
    }

    @Test
    @DisplayName("an article number resolves to that article's attachments, in order")
    void articleFilterByNumber() {
        Article article = new Article();
        article.setId(42);
        article.setImages(List.of(image(9, "cover.jpg"), image(3, "second.jpg")));
        when(articleRepository.findById(42)).thenReturn(Optional.of(article));

        ArticleFilter filter = service.resolveArticleFilter("42");

        assertThat(filter.active()).isTrue();
        assertThat(filter.imageIds()).containsExactly(9, 3);
        assertThat(filter.matchedArticles()).isEqualTo(1);
        assertThat(filter.capped()).isFalse();
        // A number is an article id, not a title fragment, so no regex search runs.
        verify(articleRepository, never()).findArticleByTitleContainingIgnoreCase(any(), any());
    }

    @Test
    @DisplayName("an unknown article number is 'no such article', not 'no images'")
    void articleFilterByUnknownNumber() {
        when(articleRepository.findById(42)).thenReturn(Optional.empty());

        ArticleFilter filter = service.resolveArticleFilter("42");

        assertThat(filter.active()).isTrue();
        assertThat(filter.imageIds()).isEmpty();
        // The screen tells the two apart on this number: zero means retype.
        assertThat(filter.matchedArticles()).isZero();
    }

    @Test
    @DisplayName("a title fragment collects the images of the matching articles, deduplicated")
    void articleFilterByTitle() {
        Article first = new Article();
        first.setId(42);
        first.setImages(List.of(image(9, "shared.jpg")));
        Article second = new Article();
        second.setId(43);
        // The same picture on both: the library must list it once.
        second.setImages(List.of(image(9, "shared.jpg"), image(11, "other.jpg")));

        when(articleRepository.findArticleByTitleContainingIgnoreCase(eq("градина"), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 25), 2));

        ArticleFilter filter = service.resolveArticleFilter("градина");

        assertThat(filter.imageIds()).containsExactly(9, 11);
        assertThat(filter.matchedArticles()).isEqualTo(2);
        assertThat(filter.capped()).isFalse();
    }

    @Test
    @DisplayName("a title matching more articles than the cap says so")
    void articleFilterReportsItsCap() {
        when(articleRepository.findArticleByTitleContainingIgnoreCase(eq("а"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, MgmtImageService.ARTICLE_MATCH_LIMIT), 200));

        ArticleFilter filter = service.resolveArticleFilter("а");

        // Silently using the first 25 of 200 looks exactly like finding 25, and the difference is
        // a library quietly missing rows.
        assertThat(filter.capped()).isTrue();
        assertThat(filter.matchedArticles()).isEqualTo(200);
    }

    @Test
    @DisplayName("an article filter plus «неизползвани» describes nothing and answers nothing")
    void articleFilterAndUnusedAreContradictory() {
        Pageable pageable = PageRequest.of(0, 24);
        ArticleFilter filter = new ArticleFilter("42", List.of(9), 1, false);

        assertThat(service.search(null, filter, Usage.UNUSED, ImageSort.ID, false, pageable)).isEmpty();

        // An attached image is used by definition, so this is answered without asking MongoDB --
        // and without silently dropping one of the two filters, which is the tempting alternative.
        verify(imageMediaRepository, never()).findByIdIn(any(), any());
        verify(mongoTemplate, never()).aggregate(any(Aggregation.class), any(String.class), any());
    }

    @Test
    @DisplayName("an unrecognised usage parameter falls back to 'all' rather than failing")
    void usageParsingIsLenient() {
        assertThat(Usage.of(null)).isEqualTo(Usage.ANY);
        assertThat(Usage.of("")).isEqualTo(Usage.ANY);
        assertThat(Usage.of("nonsense")).isEqualTo(Usage.ANY);
        // A hand-edited URL is the case: lowercase is what a person types.
        assertThat(Usage.of("unused")).isEqualTo(Usage.UNUSED);
        assertThat(Usage.of(" USED ")).isEqualTo(Usage.USED);
    }

    private void writeImage(String filename, int width, int height) throws IOException {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                "jpg", imageFolder.resolve(filename).toFile());
    }

    @Test
    @DisplayName("new images are counted by createdOn, the field an upload stamps")
    void countCreatedSinceQueriesCreatedOn() {
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> query =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        when(mongoTemplate.count(query.capture(), eq(ImageMedia.class))).thenReturn(4L);

        assertThat(service.countCreatedSince(java.time.LocalDateTime.now().minusHours(24))).isEqualTo(4L);
        assertThat(query.getValue().getQueryObject()).containsKey("createdOn");
    }
}
