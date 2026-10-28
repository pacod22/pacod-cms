package io.pacod.cms.studio;

import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.studio.form.CategoryForm;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Category administration.
 *
 * <p>Ids are chosen by the operator rather than allocated. A layout's article-list sections and the
 * site's menus both reference categories by number, so which number a category gets is a
 * decision, not an implementation detail.
 */
@Slf4j
@Service
public class MgmtCategoryService {

    /** The four sortable columns, as they are spelled in the {@code sort} query parameter. */
    static final String SORT_ID = "id";
    static final String SORT_NAME = "name";
    static final String SORT_LANG = "lang";
    static final String SORT_ARTICLES = "articles";

    /** The allow-list {@link MgmtSort#key} is held to; anything else falls to {@link #SORT_ID}. */
    private static final String[] SORTABLE = { SORT_ID, SORT_NAME, SORT_LANG, SORT_ARTICLES };

    private static final String ARTICLE_COLLECTION = "article";
    private static final String CATEGORIES_FIELD = "categories";
    private static final String COUNT_FIELD = "count";

    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;
    private final MongoTemplate mongoTemplate;

    public MgmtCategoryService(CategoryRepository categoryRepository,
                               ArticleRepository articleRepository,
                               MongoTemplate mongoTemplate) {
        this.categoryRepository = categoryRepository;
        this.articleRepository = articleRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * How many articles cite this one category.
     *
     * <p>Shown next to the delete button: removing a category leaves a dangling {@code @DBRef}
     * in every article that referenced it, so the operator should see the cost first. Use
     * {@link #countArticlesByCategory()} when more than one number is needed.
     */
    public long countArticles(Integer id) {
        return id == null ? 0 : articleRepository.countArticleByCategories(id);
    }

    /**
     * Article counts for every category, in one round trip.
     *
     * <p>The list screen shows this number on each row. Calling {@link #countArticles(Integer)}
     * per row would issue one query per category -- and doing it by reading a page's
     * {@code getTotalElements()} would additionally fetch an article per category, each of which
     * eager-loads its {@code authors}, {@code categories} and {@code images} {@code @DBRef}s.
     *
     * <p>Grouping is on the whole {@code categories} element rather than on
     * {@code categories.$id}: a field path beginning with {@code $} is not a legal aggregation
     * expression, so the group key comes back as the whole {@code @DBRef} and the id is read out
     * of it by {@link MgmtDbRefs}. The driver hands back a {@code com.mongodb.DBRef}, not an
     * {@code org.bson.Document}: a read that handled only the {@code Document} shape would return
     * an empty map, and the column would show 0 for every category. The read is shared with the
     * image library's usage filter, because one wrong guess about that shape is silent in both
     * places. Articles with no categories are dropped by {@code $unwind}, which is correct -- they
     * contribute to no category's count.
     *
     * @return category id to article count. A category with no articles is <b>absent</b> rather
     *         than mapped to zero -- callers must default it.
     */
    public Map<Integer, Long> countArticlesByCategory() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.unwind(CATEGORIES_FIELD),
                Aggregation.group(CATEGORIES_FIELD).count().as(COUNT_FIELD));

        Map<Integer, Long> counts = new HashMap<>();

        try {
            AggregationResults<Document> results =
                    mongoTemplate.aggregate(aggregation, ARTICLE_COLLECTION, Document.class);

            for (Document row : results) {
                Integer categoryId = MgmtDbRefs.idOf(row.get("_id"));
                Object count = row.get(COUNT_FIELD);

                if (categoryId != null && count instanceof Number number) {
                    counts.put(categoryId, number.longValue());
                }
            }
        } catch (DataAccessException e) {
            // A number in a column is not worth failing the whole screen for; every category
            // then reads as 0, which the delete warning treats as "unknown, be careful".
            log.error("Could not count articles per category; the list will show zeroes: {}",
                    e.getMessage());
        }

        return counts;
    }

    /**
     * The console's category list, optionally filtered and ordered by one of its four columns.
     *
     * <p>{@code search} matches the number, the name and the language; see {@link MgmtSearch} for
     * why the filtering is in memory. The number is searchable because category ids are chosen by
     * the operator and referenced by number from the layouts and the menus, so
     * "which category is 17?" is a question this screen gets asked. A blank term returns
     * everything.
     *
     * <p>The sort is in memory for the same reason, and allow-listed by {@link MgmtSort} because
     * {@code sort} arrives off a query string. The default is <b>the number</b>, not the name --
     * unlike the author list, and deliberately: these ids are chosen rather than allocated and
     * are what the layouts and the menus cite, so the number is the identity of a category in a
     * way an author's is not.
     *
     * @param usage the counts behind the «Статии» column, from {@link #countArticlesByCategory()}
     *              -- passed in rather than fetched here because the caller already needs them to
     *              render the column, and a second call would be a second aggregation. A category
     *              absent from the map has no articles and sorts as <b>zero</b>, exactly as the
     *              column displays it. May be empty: that method degrades to an empty map when
     *              MongoDB is unreachable, and then every row really is zero and the whole
     *              ordering falls through to the number. That is the honest outcome -- it agrees
     *              with what the operator can see in the column -- and must not become a throw.
     */
    public List<Category> findAll(String search, String sort, String dir, Map<Integer, Long> usage) {
        List<Category> categories = new ArrayList<>();

        for (Category category : categoryRepository.findAll()) {
            if (MgmtSearch.matches(search, category.getId(), category.getName(), category.getLang())) {
                categories.add(category);
            }
        }

        categories.sort(comparatorFor(sort, dir, usage == null ? Map.of() : usage));
        return categories;
    }

    /**
     * The requested column, reduced to one of the four this screen has.
     *
     * <p>Public and static for the reason {@code MgmtAuthorService.sortKey} is: the controller
     * echoes the <em>normalised</em> value to the template rather than the raw parameter, because
     * the header marks the active column and builds each link's flip from it. Echo it raw and
     * {@code ?sort=nonsense} sorts by number while no column says so, and the next click on any
     * header produces a descending sort out of nowhere.
     */
    public static String sortKey(String sort) {
        return MgmtSort.key(sort, SORT_ID, SORTABLE);
    }

    /** The requested direction, reduced to {@code asc} or {@code desc}. */
    public static String sortDirection(String dir) {
        return MgmtSort.direction(dir);
    }

    /**
     * <p>«Статии» is the one column on any console list whose values are not on the document:
     * they come from the aggregation above, keyed by id, and a category no article cites is
     * <b>absent</b> from that map rather than mapped to zero. Defaulting it here as well as in
     * the template is what keeps the two agreeing -- and coalescing to zero rather than ordering
     * the absent rows last is what makes the two directions exact inverses, the same argument
     * {@link MgmtSort#text} makes for a null string.
     *
     * <p>Every key ends with the number, so rows the column cannot tell apart -- most of them,
     * on «Статии» and on «Език» -- come back in the same order on every render rather than in
     * whatever order the driver happened to return them.
     */
    private static Comparator<Category> comparatorFor(String sort, String dir,
                                                      Map<Integer, Long> usage) {
        Comparator<Category> comparator = switch (sortKey(sort)) {
            case SORT_NAME -> Comparator.comparing(
                    MgmtCategoryService::nameOf, String.CASE_INSENSITIVE_ORDER);
            case SORT_LANG -> Comparator.comparing(
                    MgmtCategoryService::langOf, String.CASE_INSENSITIVE_ORDER);
            case SORT_ARTICLES -> Comparator.comparing(category -> articlesOf(category, usage));
            default -> Comparator.comparing(MgmtCategoryService::idOf);
        };

        comparator = comparator.thenComparing(MgmtCategoryService::idOf);
        return MgmtSort.isDescending(dir) ? comparator.reversed() : comparator;
    }

    private static Integer idOf(Category category) {
        return MgmtSort.number(category.getId());
    }

    private static String nameOf(Category category) {
        return MgmtSort.text(category.getName());
    }

    private static String langOf(Category category) {
        return MgmtSort.text(category.getLang());
    }

    private static Long articlesOf(Category category, Map<Integer, Long> usage) {
        return category.getId() == null ? 0L : usage.getOrDefault(category.getId(), 0L);
    }

    public Optional<Category> find(Integer id) {
        return id == null ? Optional.empty() : categoryRepository.findById(id);
    }

    public Category create(CategoryForm form) {
        if (form.getId() == null || form.getId() < 1) {
            throw new MgmtOperationException("articles.error.categoryIdPositive");
        }
        if (categoryRepository.existsById(form.getId())) {
            throw new MgmtOperationException("articles.error.categoryExists", form.getId());
        }

        return apply(new Category(form.getId()), form);
    }

    public Category save(Integer id, CategoryForm form) {
        return apply(require(id), form);
    }

    /**
     * Deleting a category leaves dangling {@code @DBRef}s in any article that cites it, so the
     * caller is expected to have checked first -- {@link #countArticles} is what the confirmation
     * screen uses.
     */
    public void delete(Integer id) {
        Category category = require(id);
        log.info("Deleting category {} ({})", category.getId(), category.getName());
        categoryRepository.deleteById(category.getId());
        // A booking for it alone would run nowhere, without a word: it goes back to every section
        // only when it named no other. $pull, not a save, so no counter is written back stale.
        mongoTemplate.updateMulti(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("categories").is(category.getId())),
                new org.springframework.data.mongodb.core.query.Update().pull("categories", category.getId()),
                io.pacod.cms.core.data.Banner.class);
    }

    /** Leaves the per-language {@code names} list alone. */
    private Category apply(Category category, CategoryForm form) {
        category.setName(form.getName());
        if (form.getLang() != null && !form.getLang().isBlank()) {
            category.setLang(form.getLang().strip());
        }

        log.info("Saving category {} ({})", category.getId(), category.getName());
        return categoryRepository.save(category);
    }

    private Category require(Integer id) {
        return find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.categoryNotFound", id));
    }
}
