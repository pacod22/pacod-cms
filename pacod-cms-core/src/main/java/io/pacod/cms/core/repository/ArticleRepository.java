package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ArticleRepository extends MongoRepository<Article, Integer> {

    /*
     * ---------------------------------------------------------------- the public site's reads
     *
     * What a reader may see: an article that is PUBLISHED -- not NEW, DRAFT, PRIVATE or without a
     * status, which the studio treats as unpublished too. Every query the public site runs is one
     * of these; the ones further down are the studio's, which must see drafts. PublicArticlesIT
     * pins each surface against a real MongoDB: a draft is absent or 404, a published article
     * present.
     */

    /** The {@code status} a reader may see. */
    ArticleStatus PUBLIC = ArticleStatus.PUBLISHED;

    @Query(value = "{status: 'PUBLISHED'}", sort = "{ _id : -1 }")
    Page<Article> findPublished(Pageable pageable);

    @Query(value = "{status: 'PUBLISHED'}", sort = "{ _id : -1 }")
    List<Article> findPublished();

    @Query(sort = "{ priority : -1, createdOn : -1 }")
    List<Article> findArticleByCategoriesAndStatus(Integer category, ArticleStatus status);

    @Query(sort = "{ priority : -1, createdOn : -1 }")
    Page<Article> findArticleByCategoriesAndStatus(Integer category, ArticleStatus status, Pageable pageable);

    @Query(sort = "{ createdOn : -1 }")
    Page<Article> findArticleByAuthorsAndStatus(Integer author, ArticleStatus status, Pageable pageable);

    List<Article> findArticleByAuthorsAndStatus(Integer author, ArticleStatus status);

    Page<Article> findArticleByTagsAndStatus(String tag, ArticleStatus status, Pageable pageable);

    @Query(value = "{ status: 'PUBLISHED', $or: [ { title: /?0/i }, { text: /?0/i }, { description: /?0/i } ]}",
           sort = "{ createdOn : -1 }")
    Page<Article> fulltextSearchPublished(String searchText, Pageable pageable);

    List<Article> findTop3ByIdGreaterThanAndStatusAndDateBetweenOrderByCounterDesc(
            Integer id, ArticleStatus status, LocalDateTime from, LocalDateTime to);

    /** Whether a category has anything a reader may see: what the sitemap lists it for. */
    boolean existsByCategoriesAndStatus(Integer category, ArticleStatus status);

    /** {@link #findCoversByIdIn}, published articles only: what {@code /social} may borrow. */
    @Query(value = "{ '_id': { $in: ?0 }, status: 'PUBLISHED' }",
           fields = "{ '_id': 1, 'title': 1, 'titles': 1, 'images': 1, 'hiddenImageIds': 1 }")
    List<Article> findPublishedCoversByIdIn(Collection<Integer> ids);

    /**
     * The sitemap's rows: ids and dates, nothing eager -- without
     * the projection every row would resolve three {@code @DBRef} lists, thousands of queries for
     * one {@code sitemap.xml}.
     */
    @Query(value = "{status: 'PUBLISHED'}",
           fields = "{ '_id': 1, 'date': 1, 'lastModifiedOn': 1 }",
           sort = "{ _id : -1 }")
    List<Article> findPublishedForSitemap(Pageable pageable);

    /* ---------------------------------------------------------------- the studio's (drafts included) */

    @Query(value = "{}", sort = "{ _id : -1 }")
    Page<Article> findAllArticle(Pageable pageable);

    @Query(value = "{}", sort = "{ _id : -1 }")
    List<Article> findAllArticle();

    /**
     * The highest-numbered article, ids only: called with a page of one, so MongoDB walks the
     * {@code _id} index once rather than reading every id to take the first.
     */
    @Query(value = "{}", fields="{ '_id' : 1}", sort = "{ _id : -1 }")
    List<Article> getMaxId(Pageable pageable);

    @Query(sort = "{ priority : -1, createdOn : -1 }")
    List<Article> findArticleByCategories(Integer category);

    @Query(sort = "{ priority : -1, createdOn : -1 }")
    Page<Article> findArticleByCategories(Integer category, Pageable pageable);

    /**
     * How many articles cite a category, without loading any of them.
     *
     * <p>The management console's category list needs this once per row. Reading it off a
     * {@code Page}'s {@code getTotalElements()} would also fetch an article per row, and every
     * article eager-loads its {@code authors}, {@code categories} and {@code images}
     * {@code @DBRef}s -- three extra queries each, for a number.
     */
    long countArticleByCategories(Integer category);

    /**
     * How many articles hold a given image.
     *
     * <p>An {@link io.pacod.cms.core.data.ImageMedia} can belong to several articles -- the
     * console's image library attaches an existing image to a second one -- so no teardown path
     * may delete its file outright: this count is the thing that stands between a shared picture
     * and a broken {@code <img>} on the other article.
     * {@code ImageService.deleteImage} is the one place that deletes on it -- see its javadoc; the
     * studio's image screens only show it.
     *
     * <p>Derived, like {@code countArticleByCategories} above: the {@code $id} restriction on
     * {@code @DBRef} fields applies to the aggregation framework only.
     */
    long countArticleByImages(Integer image);

    /**
     * The articles that hold a given image, newest first.
     *
     * <p>Derived on a {@code @DBRef} list exactly like {@code findArticleByAuthors} below, and the
     * Integer-to-DBRef conversion is the one {@link #countArticleByImages(Integer)} already
     * depends on.
     *
     * <p>Unfiltered, like {@code countArticleByImages}: the console's image screen shows that
     * count next to this list, and a filter on only one of the two would make them disagree.
     *
     * <p>One image at a time, deliberately. Every {@code Article} eager-loads its authors,
     * categories and images, so this costs three further queries per article returned -- nothing
     * for the one or two that hold a picture, ruinous as a column on a list of ninety-six. That is
     * why the console's image library links to the detail screen instead of inlining the titles.
     */
    @Query(sort = "{ _id : -1 }")
    List<Article> findArticleByImages(Integer image);

    /**
     * Articles whose title contains the term, for the image library's "by article" filter.
     *
     * <p>Title only, deliberately: {@link #fulltextSearch(String, Pageable)} also reads
     * {@code text} and {@code description}, and an image appearing in the results because the
     * word occurred somewhere in a body is not what "the images of this article" means.
     *
     * <p>Derived rather than a hand-written regex, for the reason
     * {@code ImageMediaRepository.findByFilenameContainingIgnoreCase} records: Spring Data's
     * {@code MongoRegexCreator} escapes metacharacters in the term.
     */
    Page<Article> findArticleByTitleContainingIgnoreCase(String title, Pageable pageable);

    @Query(sort = "{ createdOn : -1 }")
    Page<Article> findArticleByAuthors(Integer author, Pageable pageable);

    @Query(value="{ $or: [ { title: /?0/i }, { text: /?0/i }, { description: /?0/i } ]}", sort = "{ createdOn : -1 }")
    Page<Article> fulltextSearch(String searchText, Pageable pageable);


    List<Article> findArticleByAuthors (Integer author);

    List<Article> findArticleByIdIn(List<Integer> ids);

    /**
     * Ids and titles only, for naming the articles the {@code /social} statistics count
     * clicks on. The projection is the point: without it every {@code Article} eager-loads its
     * three {@code @DBRef} collections, so naming ten tiles would cost some forty queries.
     */
    @Query(value = "{ '_id': { $in: ?0 } }", fields = "{ '_id': 1, 'title': 1, 'titles': 1 }")
    List<Article> findTitlesByIdIn(Collection<Integer> ids);

    /**
     * Ids, titles in every language and pictures only -- what {@code Article.coverFilename()} and
     * {@code Article.titleIn()} need -- for article links on {@code /social} and {@code /mgmt/social}. {@code images} is still a
     * {@code @DBRef} and is still resolved; {@code authors} and {@code categories} are not loaded.
     */
    @Query(value = "{ '_id': { $in: ?0 } }",
           fields = "{ '_id': 1, 'title': 1, 'titles': 1, 'images': 1, 'hiddenImageIds': 1 }")
    List<Article> findCoversByIdIn(Collection<Integer> ids);

    List<Article> findTop3ByIdGreaterThanOrderByCounterDesc(Integer id);

    List<Article> findTop3ByIdGreaterThanAndDateBetweenOrderByCounterDesc(Integer id, LocalDateTime from, LocalDateTime to);

    Page<Article> findArticleByTags (String tag, Pageable pageable);
}
