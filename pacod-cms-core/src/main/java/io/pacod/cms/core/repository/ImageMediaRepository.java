package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.ImageMedia;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImageMediaRepository extends MongoRepository<ImageMedia, Integer> {

    @Query(value="{}", fields="{ '_id' : 1}", sort = "{ _id : -1 }")
    List<ImageMedia> getMaxId(Pageable pageable);

    Optional<ImageMedia> findImageMediaByFilename(String filename);

    /**
     * Backs the console's image library search.
     *
     * <p>Derived rather than a hand-written {@code @Query("{filename: /?0/i}")} like
     * {@code ArticleRepository.fulltextSearch}: Spring Data builds this through
     * {@code MongoRegexCreator}, which escapes regex metacharacters in the term. A filename
     * containing {@code (} or {@code *} would otherwise either match nothing or blow up.
     *
     * <p>Paged, and a repository query rather than the in-memory {@code MgmtSearch} the author,
     * category and banner screens use: those are tens of rows already being read and sorted in the
     * JVM, the image library is not bounded by anything.
     */
    Page<ImageMedia> findByFilenameContainingIgnoreCase(String filename, Pageable pageable);

    /**
     * The same match, unpaged: the whole matching set in one list.
     *
     * <p>The one caller is the library's by-article-count ordering, which cannot be expressed as a
     * MongoDB sort at all -- the count lives on the article side, so the rows have to be ordered
     * in the JVM and only then sliced into a page. Reading them a page at a time would order each
     * page independently, which is the silent-wrong-answer shape this codebase keeps refusing:
     * every page would look sorted and the library as a whole would not be.
     *
     * <p>Bounded by the filename term, and by the collection when there is none -- see
     * {@code MgmtImageService.pageByUsage} for what that costs and why it is acceptable on a
     * console screen.
     */
    List<ImageMedia> findByFilenameContainingIgnoreCase(String filename);

    /**
     * The images with these ids, paged. Backs the console's "used" and "by article" filters.
     *
     * <p>The id set is computed elsewhere -- from one article's attachments, or from the
     * {@code $unwind}/{@code $group} over every article's {@code images} that
     * {@code MgmtImageService.usedImageIds} runs. An empty collection matches nothing, which is
     * the right answer for "the images of an article that has none".
     */
    Page<ImageMedia> findByIdIn(Collection<Integer> ids, Pageable pageable);

    Page<ImageMedia> findByIdInAndFilenameContainingIgnoreCase(
            Collection<Integer> ids, String filename, Pageable pageable);

    /**
     * Everything <em>except</em> these ids: the console's "unused" filter.
     *
     * <p>Usage lives on the article side -- an {@code ImageMedia} does not know who references it
     * -- so "unused" can only be expressed as the complement of the used set, and there is no
     * index-friendly plan for a large {@code $nin}. This is a collection scan with one comparison
     * list per document, which is acceptable on an admin screen and would not be on a public path.
     * The list holds one id per <em>used</em> image, so it grows with the library; thousands of
     * integers are still far inside the 16 MB command limit.
     *
     * <p>An empty collection matches everything, which is correct: nothing is used, so everything
     * is unused.
     */
    Page<ImageMedia> findByIdNotIn(Collection<Integer> ids, Pageable pageable);

    Page<ImageMedia> findByIdNotInAndFilenameContainingIgnoreCase(
            Collection<Integer> ids, String filename, Pageable pageable);
}
