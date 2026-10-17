package io.pacod.cms.studio;

import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.services.AuthorService;
import io.pacod.cms.studio.form.AuthorForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Author administration.
 *
 * <p>Thin on purpose: {@link AuthorService#createAuthor(Author)} already allocates the id, so
 * this only adds the form mapping and the ordering. Authors are embedded in article documents as
 * {@code @DBRef}s, so renaming one changes every article that cites them -- on the public site
 * once its caches turn over, since no eviction here can reach them (see
 * {@link MgmtArticleService}).
 */
@Slf4j
@Service
public class MgmtAuthorService {

    /** The three sortable columns, as they are spelled in the {@code sort} query parameter. */
    static final String SORT_ID = "id";
    static final String SORT_NAME = "name";
    static final String SORT_DESCRIPTION = "description";

    /** The allow-list {@link MgmtSort#key} is held to; the default is the first thing it falls to. */
    private static final String[] SORTABLE = { SORT_ID, SORT_NAME, SORT_DESCRIPTION };

    private final AuthorRepository authorRepository;
    private final AuthorService authorService;

    public MgmtAuthorService(AuthorRepository authorRepository, AuthorService authorService) {
        this.authorRepository = authorRepository;
        this.authorService = authorService;
    }

    /**
     * The console's author list, optionally filtered and ordered by one of its three columns.
     *
     * <p>{@code search} matches the number, the title, either name and the description; see
     * {@link MgmtSearch} for why the filtering is in memory. A blank term returns everything.
     *
     * <p>The sort is in memory for the same reason the filter is -- this is tens of rows the
     * method already reads whole -- and it is chosen from an allow-list rather than turned into
     * a property path: {@code sort} arrives off a query string, and a dynamic path would let a
     * URL name a field no column shows. {@link #sortKey} and {@link #sortDirection} normalise
     * both parameters, so an unrecognised value falls back to the default silently instead of
     * throwing at an operator who edited a URL.
     */
    public List<Author> findAll(String search, String sort, String dir) {
        List<Author> authors = new ArrayList<>();

        for (Author author : authorRepository.findAll()) {
            if (MgmtSearch.matches(search, author.getId(), author.getTitle(),
                    author.getFirstname(), author.getLastname(), author.getDescription())) {
                authors.add(author);
            }
        }

        authors.sort(comparatorFor(sort, dir));
        return authors;
    }

    /**
     * The requested column, reduced to one of the three this screen has.
     *
     * <p>Public and static because the controller echoes the <em>normalised</em> value back to
     * the template rather than the raw parameter: the header marks the active column and builds
     * each link's flip from it, so {@code ?sort=nonsense} has to arrive at the template as
     * {@code name} -- otherwise the screen sorts by full name while no column says it does, and
     * the first click on any header would produce a descending sort out of nowhere.
     */
    public static String sortKey(String sort) {
        return MgmtSort.key(sort, SORT_NAME, SORTABLE);
    }

    /**
     * The requested direction, reduced to {@code asc} or {@code desc}.
     *
     * <p>A thin delegate rather than the controller calling {@link MgmtSort} itself: which
     * columns this screen has is a fact about this screen, and the controller should ask the
     * service for it rather than a helper shared with every other list.
     */
    public static String sortDirection(String dir) {
        return MgmtSort.direction(dir);
    }

    /**
     * <p>Every key ends with the id, so rows the column cannot tell apart -- and there are many:
     * {@code description} is null on most authors -- come back in the same order on every
     * render rather than in whatever order the driver happened to return them. See
     * {@link MgmtSort#text} for why nulls are coalesced rather than ordered last.
     */
    private static Comparator<Author> comparatorFor(String sort, String dir) {
        Comparator<Author> comparator = switch (sortKey(sort)) {
            case SORT_ID -> Comparator.comparing(MgmtAuthorService::idOf);
            case SORT_DESCRIPTION -> Comparator.comparing(
                    MgmtAuthorService::descriptionOf, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(Author::fullname, String.CASE_INSENSITIVE_ORDER);
        };

        comparator = comparator.thenComparing(MgmtAuthorService::idOf);
        return MgmtSort.isDescending(dir) ? comparator.reversed() : comparator;
    }

    /** {@code Author.id} is an {@code Integer} and the no-arg constructor leaves it at 0, but a
     *  document written by other means can carry none at all. */
    private static Integer idOf(Author author) {
        return MgmtSort.number(author.getId());
    }

    private static String descriptionOf(Author author) {
        return MgmtSort.text(author.getDescription());
    }

    public Optional<Author> find(Integer id) {
        return id == null ? Optional.empty() : authorRepository.findById(id);
    }

    public Author create(AuthorForm form) {
        Author author = authorService.createAuthor(new Author());
        return apply(author, form);
    }

    public Author save(Integer id, AuthorForm form) {
        return apply(require(id), form);
    }

    public void delete(Integer id) {
        Author author = require(id);
        log.info("Deleting author {} ({})", author.getId(), author.fullname());
        authorRepository.deleteById(author.getId());
    }

    /**
     * Applies only the scalar fields; the per-language {@code firstnames} / {@code lastnames} /
     * {@code titles} / {@code descriptions} lists are left as they are.
     */
    private Author apply(Author author, AuthorForm form) {
        author.setTitle(form.getTitle());
        author.setFirstname(form.getFirstname());
        author.setLastname(form.getLastname());
        author.setDescription(form.getDescription());

        log.info("Saving author {} ({})", author.getId(), author.fullname());
        return authorRepository.save(author);
    }

    private Author require(Integer id) {
        return find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.authorNotFound", id));
    }
}
