package io.pacod.cms.studio;

import com.mongodb.DBRef;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the category list screen asks of the service: the per-category article counts, and the
 * search filter.
 *
 * <p>What is under test in the counts is the mapping out of the aggregation, because the group
 * key is a <em>DBRef document</em> rather than the plain id it looks like it should be -- an
 * aggregation cannot group on {@code categories.$id}, since a field path starting with {@code $}
 * is not a legal expression.
 */
class MgmtCategoryServiceTest {

    private MongoTemplate mongoTemplate;
    private CategoryRepository categoryRepository;
    private MgmtCategoryService service;

    private static Document row(Object groupKey, Object count) {
        return new Document("_id", groupKey).append("count", count);
    }

    /**
     * What the <em>driver</em> hands back for one element of Article.categories.
     *
     * <p>A {@code com.mongodb.DBRef}, not an {@code org.bson.Document} with {@code $ref} and
     * {@code $id} keys: the document is the shape MongoDB stores, but not the shape that arrives --
     * the default codec registry decodes it into a DBRef. A fixture in the stored shape passes
     * every test here while the column shows 0 for every category on the running stack. The
     * document form is covered below too, as the other branch of {@code MgmtDbRefs.idOf}.
     */
    private static DBRef dbRef(int categoryId) {
        return new DBRef("category", categoryId);
    }

    /** The raw stored form, which a different codec registry could still deliver. */
    private static Document dbRefDocument(int categoryId) {
        return new Document("$ref", "category").append("$id", categoryId);
    }

    @SuppressWarnings("unchecked")
    private void stubAggregation(Document... rows) {
        AggregationResults<Document> results = mock(AggregationResults.class);
        when(results.iterator()).thenReturn(List.of(rows).iterator());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("article"), eq(Document.class)))
                .thenReturn(results);
    }

    private static Category category(int id, String name, String lang) {
        Category category = new Category(id);
        category.setName(name);
        category.setLang(lang);
        return category;
    }

    /** No sort parameters at all -- what an unfiltered, unsorted GET binds. */
    private List<Integer> idsMatching(String search) {
        return idsSorted(search, null, null, Map.of());
    }

    private List<Integer> idsSorted(String search, String sort, String dir,
                                    Map<Integer, Long> usage) {
        return service.findAll(search, sort, dir, usage).stream().map(Category::getId).toList();
    }

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        categoryRepository = mock(CategoryRepository.class);
        service = new MgmtCategoryService(
                categoryRepository, mock(ArticleRepository.class), mongoTemplate);

        when(categoryRepository.findAll()).thenReturn(List.of(
                category(17, "Двор", "bg"),
                category(18, "Стар град", "bg"),
                category(19, "Old Town", "en"),
                // A name is nullable -- MgmtArticleService.allCategories() already defends
                // against it -- and must not take the screen down.
                category(20, null, null)));
    }

    @Test
    @DisplayName("counts are read out of the DBRef group key, in a single round trip")
    void countsAreMappedFromTheDbRefGroupKey() {
        stubAggregation(row(dbRef(17), 4), row(dbRef(18), 1));

        Map<Integer, Long> counts = service.countArticlesByCategory();

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(17, 4L, 18, 1L));
        // The whole point: one query for the column, not one per row.
        verify(mongoTemplate).aggregate(any(Aggregation.class), eq("article"), eq(Document.class));
    }

    @Test
    @DisplayName("the stored document form of the key is understood too")
    void countsAreMappedFromTheDocumentShapedGroupKey() {
        stubAggregation(row(dbRefDocument(17), 4));

        // Not the shape the driver sends -- kept because reading the wrong one does not throw, it
        // returns an empty map, and that is exactly how this column reached production showing 0.
        assertThat(service.countArticlesByCategory()).containsExactlyEntriesOf(Map.of(17, 4L));
    }

    @Test
    @DisplayName("a category with no articles is absent rather than zero, so callers must default it")
    void categoriesWithoutArticlesAreAbsent() {
        stubAggregation(row(dbRef(17), 4));

        assertThat(service.countArticlesByCategory()).doesNotContainKey(18);
    }

    @Test
    @DisplayName("an unexpected group key is skipped instead of breaking the screen")
    void malformedRowsAreSkipped() {
        stubAggregation(row(dbRef(17), 4), row("not-a-dbref", 9), row(dbRef(18), "not-a-number"));

        assertThat(service.countArticlesByCategory()).containsExactlyEntriesOf(Map.of(17, 4L));
    }

    @Test
    @DisplayName("a database failure costs the column, not the page")
    void aFailedAggregationDegradesToAnEmptyMap() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("article"), eq(Document.class)))
                .thenThrow(new DataAccessResourceFailureException("Timed out while waiting for a server"));

        assertThat(service.countArticlesByCategory()).isEmpty();
    }

    @Test
    @DisplayName("a blank term returns everything, still ordered by number")
    void aBlankTermFiltersNothing() {
        // The empty string is what an untouched search box submits; null is what an unfiltered
        // GET binds.
        assertThat(idsMatching(null)).containsExactly(17, 18, 19, 20);
        assertThat(idsMatching("")).containsExactly(17, 18, 19, 20);
        assertThat(idsMatching("   ")).containsExactly(17, 18, 19, 20);
    }

    @Test
    @DisplayName("name, language and number are all searchable, ignoring case")
    void everyFieldIsSearchable() {
        assertThat(idsMatching("двор")).containsExactly(17);
        assertThat(idsMatching("ДВОР")).containsExactly(17);
        assertThat(idsMatching("en")).containsExactly(19);
        // Ids are chosen by the operator and referenced by number from the landing-page layout,
        // so looking one up by its number is the point.
        assertThat(idsMatching("18")).containsExactly(18);
    }

    @Test
    @DisplayName("terms may be given in any order, because each is matched separately")
    void termsMatchIndependentlyOfOrder() {
        assertThat(idsMatching("град стар")).containsExactly(18);
        assertThat(idsMatching("стар двор")).isEmpty();
    }

    @Test
    @DisplayName("a term matching nothing gives an empty list rather than everything")
    void noMatchIsEmpty() {
        assertThat(idsMatching("несъществуваща")).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised column and an unrecognised direction fall back to the number")
    void unknownSortFallsBackToTheNumber() {
        // The parameters arrive off a query string, so this is what an operator who edited a URL
        // produces: it degrades silently to the default rather than throwing at them, and lands
        // on exactly the order the blank-term assertions above pin.
        assertThat(idsSorted(null, "password", "sideways", Map.of())).containsExactly(17, 18, 19, 20);
        assertThat(idsSorted(null, "", "", Map.of())).containsExactly(17, 18, 19, 20);

        // The default is the NUMBER here and the full name on the author list. The two screens
        // are deliberately not unified: a category's id is chosen by the operator and cited by
        // number from the landing-page layout, so it is that row's identity.
        assertThat(MgmtCategoryService.sortKey(null)).isEqualTo("id");
        assertThat(MgmtCategoryService.sortKey("ARTICLES")).isEqualTo("articles");
        // A column that exists on the OTHER screen is still unrecognised here. MgmtSort.key holds
        // each caller to its own allow-list, and collapsing that into one shared list of every
        // column in the console would let this URL sort by a field this table does not show.
        assertThat(MgmtCategoryService.sortKey("description")).isEqualTo("id");
        assertThat(MgmtCategoryService.sortDirection("DESC")).isEqualTo("desc");
        assertThat(MgmtCategoryService.sortDirection(null)).isEqualTo("asc");
    }

    @Test
    @DisplayName("sorting by number, name and language, both ways")
    void sortsByTheDocumentColumns() {
        assertThat(idsSorted(null, "id", "desc", Map.of())).containsExactly(20, 19, 18, 17);

        // Category 20 has neither a name nor a language, and must sort rather than throw.
        //
        // "Old Town" comes before "Двор": CASE_INSENSITIVE_ORDER compares code points, so Latin
        // sorts ahead of Cyrillic. That is the honest consequence of refusing a Collator -- a
        // collator would order these by locale, and the console must not reorder its rows
        // according to whichever locale the JVM happened to start with.
        assertThat(idsSorted(null, "name", "asc", Map.of())).containsExactly(20, 19, 17, 18);
        assertThat(idsSorted(null, "name", "desc", Map.of())).containsExactly(18, 17, 19, 20);

        // "bg" twice, then "en", then the null -- so the language column is mostly ties and the
        // number behind it is what keeps two renders of the same URL in the same order.
        assertThat(idsSorted(null, "lang", "asc", Map.of())).containsExactly(20, 17, 18, 19);
        assertThat(idsSorted(null, "lang", "desc", Map.of())).containsExactly(19, 18, 17, 20);
    }

    @Test
    @DisplayName("sorting by article count reads the map, and absent counts as zero")
    void sortsByArticleCount() {
        // The «Статии» column is the one sortable column whose values are not on the document.
        // 19 and 20 are absent from the map, which countArticlesByCategory documents as meaning
        // no articles -- the comparator has to default it exactly as the template does, or the
        // rows the operator is most likely looking for would sort as if unknown.
        Map<Integer, Long> usage = Map.of(17, 4L, 18, 1L);

        assertThat(idsSorted(null, "articles", "asc", usage)).containsExactly(19, 20, 18, 17);
        // Exact inverse, tie-break included: coalescing to zero is what buys that, where ordering
        // the absent rows last would leave them at the bottom of both directions.
        assertThat(idsSorted(null, "articles", "desc", usage)).containsExactly(17, 18, 20, 19);
    }

    @Test
    @DisplayName("an empty usage map is the failed-aggregation case, and orders by number")
    void articleSortWithNoCountsFallsThroughToTheNumber() {
        // countArticlesByCategory degrades to an empty map when MongoDB is unreachable, and the
        // column then shows 0 on every row. The ordering has to agree with what is on screen
        // rather than throw -- the same "cost the column, not the page" rule as the count itself.
        assertThat(idsSorted(null, "articles", "asc", Map.of())).containsExactly(17, 18, 19, 20);
        assertThat(idsSorted(null, "articles", "asc", null)).containsExactly(17, 18, 19, 20);
    }

    @Test
    @DisplayName("the sort applies to the filtered rows, not to the collection")
    void sortingAndFilteringCompose() {
        // Both parameters ride in one URL, so a header link that dropped the term -- or a search
        // that dropped the ordering -- shows up here as one of the two being ignored.
        assertThat(idsSorted("bg", "id", "desc", Map.of())).containsExactly(18, 17);
        assertThat(idsSorted("bg", "id", "asc", Map.of())).containsExactly(17, 18);
    }
}
