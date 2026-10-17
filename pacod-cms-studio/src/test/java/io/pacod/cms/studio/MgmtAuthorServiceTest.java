package io.pacod.cms.studio;

import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.services.AuthorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The author list's search filter.
 *
 * <p>{@code MgmtScreenRenderTest} mocks this service, so it proves the term and the two sort
 * parameters reach it and the screen renders -- not what the term actually matches or what the
 * ordering actually is. That is here.
 */
class MgmtAuthorServiceTest {

    private AuthorRepository authorRepository;
    private MgmtAuthorService service;

    private static Author author(int id, String title, String firstname, String lastname,
                                 String description) {
        Author author = new Author(id);
        author.setTitle(title);
        author.setFirstname(firstname);
        author.setLastname(lastname);
        author.setDescription(description);
        return author;
    }

    /** No sort parameters at all -- what an unfiltered, unsorted GET binds. */
    private List<Integer> idsMatching(String search) {
        return idsSorted(search, null, null);
    }

    private List<Integer> idsSorted(String search, String sort, String dir) {
        return service.findAll(search, sort, dir).stream().map(Author::getId).toList();
    }

    @BeforeEach
    void setUp() {
        authorRepository = mock(AuthorRepository.class);
        service = new MgmtAuthorService(authorRepository, mock(AuthorService.class));

        when(authorRepository.findAll()).thenReturn(List.of(
                author(7, "Проф.", "Мария", "Влахова", "Фотография"),
                author(8, "Доц.", "Иван", "Петров", "Етнография"),
                // Every scalar an Author has is nullable -- the no-arg constructor leaves them
                // null -- and a row like this must not take the screen down.
                author(9, null, null, null, null)));
    }

    @Test
    @DisplayName("a blank term returns everything, still sorted by full name")
    void aBlankTermFiltersNothing() {
        // The empty string is what an untouched search box submits; null is what an unfiltered
        // GET binds.
        assertThat(idsMatching(null)).containsExactly(9, 8, 7);
        assertThat(idsMatching("")).containsExactly(9, 8, 7);
        assertThat(idsMatching("   ")).containsExactly(9, 8, 7);
    }

    @Test
    @DisplayName("matching ignores case, including in Cyrillic")
    void matchingIsCaseInsensitive() {
        assertThat(idsMatching("влахова")).containsExactly(7);
        assertThat(idsMatching("ВЛАХОВА")).containsExactly(7);
    }

    @Test
    @DisplayName("every field on the row is searchable, the number included")
    void everyFieldIsSearchable() {
        assertThat(idsMatching("проф")).containsExactly(7);
        assertThat(idsMatching("иван")).containsExactly(8);
        assertThat(idsMatching("етнография")).containsExactly(8);
        assertThat(idsMatching("8")).containsExactly(8);
    }

    @Test
    @DisplayName("terms may be given in any order, because each is matched separately")
    void termsMatchIndependentlyOfOrder() {
        assertThat(idsMatching("Мария Влахова")).containsExactly(7);
        // A plain contains over the joined fields would miss this one.
        assertThat(idsMatching("Влахова Мария")).containsExactly(7);
        // Both terms have to match, so this pairing belongs to nobody.
        assertThat(idsMatching("Мария Петров")).isEmpty();
    }

    @Test
    @DisplayName("a term matching nothing gives an empty list rather than everything")
    void noMatchIsEmpty() {
        assertThat(idsMatching("несъществуващ")).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised column and an unrecognised direction fall back to the default")
    void unknownSortFallsBackToFullNameAscending() {
        // The parameters arrive off a query string, so this is the case an operator who edited a
        // URL produces. It degrades to the default silently rather than throwing at them -- and
        // it must land on exactly the order the three assertions above pin.
        assertThat(idsSorted(null, "password", "sideways")).containsExactly(9, 8, 7);
        assertThat(idsSorted(null, "", "")).containsExactly(9, 8, 7);
    }

    @Test
    @DisplayName("the column keys are matched ignoring case, like everything else the box takes")
    void sortKeysAreCaseInsensitive() {
        assertThat(idsSorted(null, "ID", "DESC")).containsExactly(9, 8, 7);
        assertThat(MgmtAuthorService.sortKey("DeScRiPtIoN")).isEqualTo("description");
        // A column that exists on the category screen is still unrecognised here -- MgmtSort.key
        // is held to this screen's allow-list, not to a shared list of every column there is.
        assertThat(MgmtAuthorService.sortKey("articles")).isEqualTo("name");
        assertThat(MgmtAuthorService.sortDirection("DESC")).isEqualTo("desc");
        // Anything else, null included, is the default rather than an error.
        assertThat(MgmtAuthorService.sortKey(null)).isEqualTo("name");
        assertThat(MgmtAuthorService.sortDirection(null)).isEqualTo("asc");
    }

    @Test
    @DisplayName("sorting by number, both ways")
    void sortsByNumber() {
        assertThat(idsSorted(null, "id", "asc")).containsExactly(7, 8, 9);
        assertThat(idsSorted(null, "id", "desc")).containsExactly(9, 8, 7);
    }

    @Test
    @DisplayName("sorting by full name, both ways, ignoring case")
    void sortsByFullName() {
        assertThat(idsSorted(null, "name", "asc")).containsExactly(9, 8, 7);
        assertThat(idsSorted(null, "name", "desc")).containsExactly(7, 8, 9);
    }

    @Test
    @DisplayName("sorting by description puts the row with none first, and the reverse is exact")
    void sortsByDescription() {
        // Id 9 has no description at all. Coalescing it to "" rather than ordering nulls last is
        // what makes the two directions exact inverses; with nullsLast it would sit at the bottom
        // of both and the operator could never bring the empty rows into view.
        assertThat(idsSorted(null, "description", "asc")).containsExactly(9, 8, 7);
        assertThat(idsSorted(null, "description", "desc")).containsExactly(7, 8, 9);
    }

    @Test
    @DisplayName("rows the column cannot tell apart fall back to the number, in both directions")
    void tiesBreakOnTheNumber() {
        // Most authors carry no description at all, so "every row looks the same to this column"
        // is the normal case on that column rather than an edge one. Without the tie-break the
        // order would be whatever the driver happened to return, and it would change under the
        // operator between two renders of the same URL with nothing having been edited.
        when(authorRepository.findAll()).thenReturn(List.of(
                author(30, null, "Борис", "Илиев", "Етнография"),
                author(10, null, "Ана", "Илиева", "Етнография"),
                author(20, null, "Виктор", "Илиев", "Етнография")));

        assertThat(idsSorted(null, "description", "asc")).containsExactly(10, 20, 30);
        // Reversed through the tie-break too, which is what makes the two directions exact
        // inverses rather than "the same order with the groups flipped".
        assertThat(idsSorted(null, "description", "desc")).containsExactly(30, 20, 10);
    }

    @Test
    @DisplayName("the sort applies to the filtered rows, not to the collection")
    void sortingAndFilteringCompose() {
        // Both parameters ride in the same URL, so a header link that dropped the term -- or a
        // search that dropped the ordering -- would show up here as one of the two being ignored.
        assertThat(idsSorted("графия", "id", "desc")).containsExactly(8, 7);
        assertThat(idsSorted("графия", "id", "asc")).containsExactly(7, 8);
    }
}
