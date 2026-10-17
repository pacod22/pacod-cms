package io.pacod.cms.studio;

import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.services.ImageService;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The platform's image library.
 *
 * <p>Every image ever uploaded, searchable and reusable, rather than one article's attachments.
 * That is the point of the screen: {@code ImageService.save} refuses a filename it already holds
 * (the {@code filename} index is unique), so without it the only way to put an existing picture
 * on a second article would be to rename the file and upload a duplicate.
 *
 * <p>Attaching is deliberately <em>not</em> here -- it belongs to
 * {@link MgmtArticleService#attachImage(Integer, Integer)}, which owns the per-article cap and the
 * hidden set. This class only finds images and deletes them.
 */
@Slf4j
@Service
public class MgmtImageService {

    /** Where {@code app.image.folder} is served from. The one place this is spelled out. */
    public static final String PUBLIC_PREFIX = "/assets/img/articles/";

    /** How many articles a title search may pull images from. Reported when it bites. */
    public static final int ARTICLE_MATCH_LIMIT = 25;

    private static final String ARTICLE_COLLECTION = "article";
    private static final String IMAGES_FIELD = "images";

    /**
     * Whether an image is on an article at all.
     *
     * <p>{@code UNUSED} is the one an operator comes looking for: an image nothing references is
     * a file the platform is carrying for nothing, and it is also the only kind
     * {@code ImageService.deleteImage} will let go of.
     */
    public enum Usage {
        ANY, USED, UNUSED;

        /** Lenient by design: a hand-edited URL should fall back to "all", not answer 400. */
        public static Usage of(String value) {
            if (value == null) {
                return ANY;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ANY;
            }
        }
    }

    /**
     * How the library is ordered.
     *
     * <p>Three keys, and only two of them are a MongoDB sort. {@code ARTICLES} counts references
     * that live on the <em>article</em> side -- an {@code ImageMedia} does not know who holds it --
     * so it is ordered in the JVM over one aggregation, exactly as «неизползвани» is resolved to an
     * id set before the image query runs. See {@link #pageByUsage}.
     *
     * <p>Lenient for the reason {@link Usage#of} is, and for a stronger one: this arrives off a
     * query string and is fed to {@code Sort.by}, so it is an <em>allow-list</em> and never a
     * property path. The value the caller gets back is the one that was applied, never the one that
     * was asked for -- the {@code MgmtSort} rule, which exists because a screen that echoes
     * {@code ?sort=nonsense} raw marks a column as active that is not the one it sorted by.
     */
    public enum ImageSort {

        /** The number the console shows beside every image, and the default everywhere. */
        ID,

        /** The filename. */
        NAME,

        /** How many articles hold the image -- the library's «в N статии». */
        ARTICLES;

        public static ImageSort of(String value) {
            if (value == null) {
                return ID;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ID;
            }
        }
    }

    private final ImageMediaRepository imageMediaRepository;
    private final ArticleRepository articleRepository;
    private final ImageService imageService;

    /**
     * The same directory {@code ImageService} writes uploads to, read here and never written.
     *
     * <p>The detail screen reports what is actually on disk next to what the document claims,
     * which is the one place the two can be seen to disagree -- {@code ImageService.deleteImage}
     * logs "file was already absent; removing the orphaned DB record", so that state occurs, and
     * this screen is where it can be noticed before a delete stumbles on it.
     */
    private final String imageFolder;

    /** Only for {@link #usedImageIds()}: the one question no repository method can answer. */
    private final MongoTemplate mongoTemplate;

    public MgmtImageService(ImageMediaRepository imageMediaRepository,
                            ArticleRepository articleRepository,
                            ImageService imageService,
                            MongoTemplate mongoTemplate,
                            @Value("${app.image.folder}") String imageFolder) {
        this.imageMediaRepository = imageMediaRepository;
        this.articleRepository = articleRepository;
        this.imageService = imageService;
        this.mongoTemplate = mongoTemplate;
        this.imageFolder = imageFolder;
    }

    /**
     * One row of the library, and the JSON the editor's picker receives.
     *
     * <p>A projection rather than the document: {@code ImageMedia} carries a {@code @Transient}
     * byte array, a {@code source} nothing sets and two timestamps, none of which any screen shows.
     * {@code path} is computed here so the prefix is not concatenated again in two templates and a
     * script -- the value the operator copies is the value the picker inserts into the body.
     */
    public record ImageRow(Integer id, String filename, String caption, String path, long usages) {
    }

    /**
     * What the «статия» box resolved to: the images to restrict the library to, and enough about
     * the match for the screen to say what it did.
     *
     * <p>{@code matchedArticles} of zero with an active term is "no such article", which is a
     * different empty result from "that article has no images" -- and the screen distinguishes
     * them, because the first means the operator should retype and the second does not.
     */
    public record ArticleFilter(String term, List<Integer> imageIds, long matchedArticles,
                                boolean capped) {

        public boolean active() {
            return term != null && !term.isBlank();
        }

        public static ArticleFilter none() {
            return new ArticleFilter(null, List.of(), 0, false);
        }
    }

    /**
     * One article holding the image, for the detail screen's "used by" list.
     *
     * <p>A projection for the same reason {@link ImageRow} is one: the screen needs four fields,
     * and an {@code Article} drags three eager {@code @DBRef} collections into the template with
     * it.
     */
    public record ImageUsage(Integer id, String title, ArticleStatus status, LocalDateTime date) {
    }

    /**
     * What the file on disk says, as opposed to what the document says.
     *
     * <p>{@code present} false is not a rendering detail: it means the record survives and the
     * picture does not, so every article holding it shows a broken image. Nothing else in the
     * console can see that.
     *
     * <p>{@code width}/{@code height} are null when no reader recognises the file -- a renamed
     * non-image, or a format this JVM has no plugin for. That is different from absent, and the
     * screen says so.
     */
    public record ImageFile(boolean present, long bytes, Integer width, Integer height,
                            LocalDateTime modifiedOn) {

        static ImageFile missing() {
            return new ImageFile(false, 0L, null, null, null);
        }
    }

    /**
     * The editor's library picker: a term and an ordering.
     *
     * <p>The number a term may be is worth supporting because it is what the console shows in its
     * own lists and what the {@code /assets/img/articles/} tree does not: an operator holding an id
     * has no other way to find the file.
     *
     * <p>The ordering is the service's to build, not the caller's -- {@code ARTICLES} is not a
     * {@code Sort} at all, and a controller assembling a {@code PageRequest} would have to know
     * that. What arrives here is the page and the size; what leaves is a page ordered by the key
     * that was actually applied.
     *
     * <p>{@code ID} ascending is the default, which is also what the author and category pickers
     * do with their own rows: the number beside a row is the only handle an operator has on a file
     * the {@code /assets/img/articles/} tree does not name. Unsorted would not merely be
     * arbitrary -- a paged MongoDB query with no sort has no defined order between pages, so an
     * image could be shown twice and another never at all.
     */
    public Page<ImageRow> search(String search, ImageSort sort, boolean descending, Pageable pageable) {
        return search(search, ArticleFilter.none(), Usage.ANY, sort, descending, pageable);
    }

    /**
     * The library screen: a term, an article, whether the image is used at all, and an ordering.
     *
     * <p>The four narrow and order independently, and only the term is a MongoDB regex. The
     * article filter arrives already resolved to a bounded id list (see
     * {@link #resolveArticleFilter(String)}); the usage filter is the complement of one
     * aggregation over every article's {@code images}.
     *
     * <p>The ordering is the service's to build, not the caller's -- {@code ARTICLES} is not a
     * {@code Sort} at all, and a controller assembling a {@code PageRequest} would have to know
     * that. What arrives here is the page and the size; what leaves is a page ordered by the key
     * that was actually applied.
     *
     * <p>{@code ID} ascending is the default, which is also what the author and category pickers
     * do with their own rows: the number beside a row is the only handle an operator has on a file
     * the {@code /assets/img/articles/} tree does not name. Unsorted would not merely be
     * arbitrary -- a paged MongoDB query with no sort has no defined order between pages, so an
     * image could be shown twice and another never at all.
     */
    public Page<ImageRow> search(String search, ArticleFilter articles, Usage usage,
                                 ImageSort sort, boolean descending, Pageable pageable) {

        String term = search == null ? "" : search.strip();
        ArticleFilter filter = articles == null ? ArticleFilter.none() : articles;
        Usage want = usage == null ? Usage.ANY : usage;
        ImageSort key = sort == null ? ImageSort.ID : sort;
        Sort.Direction direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;

        // `_id`, not `id`: the field as it is stored, the spelling resolveArticleFilter already
        // uses. NAME sorts by code point in MongoDB -- uppercase ahead of lowercase -- while the
        // term matches case-insensitively. The asymmetry is the honest price of refusing a
        // collation, the same one MgmtSort refuses a Collator for: an ordering that depends on the
        // server's locale is one that changes under a screen without the data changing.
        //
        // ARTICLES is not on this list because it cannot be: see pageByUsage. Every branch below
        // therefore passes `paged` to the repository and `direction` to the in-JVM ordering, and
        // exactly one of the two is used.
        Pageable paged = key == ImageSort.ARTICLES
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(direction, key == ImageSort.NAME ? "filename" : "_id"));

        if (filter.active()) {
            if (want == Usage.UNUSED) {
                // Not a result to be explained away afterwards: an image attached to an article
                // IS used, so the two filters together describe nothing. Answering empty and
                // letting the screen say why beats silently dropping one of them.
                return Page.empty(paged);
            }

            List<Integer> ids = filter.imageIds();
            if (key == ImageSort.ARTICLES) {
                // findAllById rather than the whole collection: this restriction is already a
                // bounded id list, and the ARTICLES branch reads its candidates in full.
                return pageByUsage(term, ids::contains, direction, paged,
                        () -> imageMediaRepository.findAllById(ids));
            }
            return page(term, ids::contains, paged,
                    () -> imageMediaRepository.findByIdIn(ids, paged),
                    () -> imageMediaRepository.findByIdInAndFilenameContainingIgnoreCase(ids, term, paged));
        }

        if (want != Usage.ANY) {
            Set<Integer> used = usedImageIds();

            if (want == Usage.USED) {
                if (key == ImageSort.ARTICLES) {
                    return pageByUsage(term, used::contains, direction, paged,
                            () -> imageMediaRepository.findAllById(used));
                }
                return page(term, used::contains, paged,
                        () -> imageMediaRepository.findByIdIn(used, paged),
                        () -> imageMediaRepository.findByIdInAndFilenameContainingIgnoreCase(used, term, paged));
            }

            if (key == ImageSort.ARTICLES) {
                // Every row here has a count of zero by definition, so the ordering falls through
                // to the id -- which is the honest answer, not a special case: the operator asked
                // for a column whose values are all the same and gets a stable list rather than an
                // arbitrary one. There is no id list for "unused", so this reads the collection.
                return pageByUsage(term, id -> !used.contains(id), direction, paged,
                        imageMediaRepository::findAll);
            }
            return page(term, id -> !used.contains(id), paged,
                    () -> imageMediaRepository.findByIdNotIn(used, paged),
                    () -> imageMediaRepository.findByIdNotInAndFilenameContainingIgnoreCase(used, term, paged));
        }

        if (key == ImageSort.ARTICLES) {
            return pageByUsage(term, id -> true, direction, paged, imageMediaRepository::findAll);
        }

        return page(term, id -> true, paged,
                () -> imageMediaRepository.findAll(paged),
                () -> imageMediaRepository.findByFilenameContainingIgnoreCase(term, paged));
    }

    /**
     * The body every branch above shares: a blank term reads the restriction, a term matches
     * filenames within it, and an exact number still wins the first slot.
     *
     * <p>{@code allowed} is what keeps the number honest under a filter. Without it, typing an id
     * while «неизползвани» is on would surface an image that <em>is</em> used, in a list whose
     * whole claim is the opposite -- and the operator would go on to try to delete it.
     */
    private Page<ImageRow> page(String term, Predicate<Integer> allowed, Pageable pageable,
                                Supplier<Page<ImageMedia>> everything,
                                Supplier<Page<ImageMedia>> byFilename) {

        if (term.isEmpty()) {
            return everything.get().map(this::row);
        }

        Optional<ImageMedia> byId = numeric(term)
                .filter(allowed)
                .flatMap(imageMediaRepository::findById);

        Page<ImageMedia> byName = byFilename.get();

        if (byId.isEmpty()) {
            return byName.map(this::row);
        }

        // An exact number wins the first slot, on page one only -- paging past it would otherwise
        // repeat it on every page. It is dropped from the name matches so it cannot appear twice.
        ImageMedia exact = byId.get();
        List<ImageRow> rows = new ArrayList<>();
        if (pageable.getPageNumber() == 0) {
            rows.add(row(exact));
        }
        byName.getContent().stream()
                .filter(image -> !exact.getId().equals(image.getId()))
                .map(this::row)
                .forEach(rows::add);

        return new PageImpl<>(rows, pageable, byName.getTotalElements() + 1);
    }

    /**
     * The library ordered by how many articles hold each image.
     *
     * <p>There is no query for this. The reference points from article to image and carries no
     * back-reference, so the count is not a field the image can be sorted on -- the same fact that
     * makes «неизползвани» the complement of an aggregation rather than a condition. So the whole
     * matching set is read, ordered here, and only then sliced into the requested page. Ordering a
     * page at a time would order each page independently: every page would look sorted, the library
     * would not be, and nothing on screen would say so.
     *
     * <p>What that costs: one aggregation over the articles, plus every image the term matches --
     * the whole collection when there is no term. Acceptable here for the reason
     * {@code findByIdNotIn}'s collection scan is: this is one operator on a console screen, not a
     * reader path. The counts come back with the rows, so this branch also makes the per-row
     * {@code countArticleByImages} query that {@link #row(ImageMedia)} runs unnecessary.
     *
     * <p>An image no article holds is <em>absent</em> from the aggregation rather than mapped to
     * zero, and both the comparator and the row default it exactly as the screen reads it --
     * the trap {@code MgmtCategoryService.countArticlesByCategory} documents. The total is the
     * number of candidates, never the size of the used set: a smaller total would make
     * {@code hasNext()} false while images were still unshown, and «Покажи още» would disappear
     * with rows left behind it.
     *
     * @param allowed    the screen's article and usage filters, as {@link #page} takes them. It is
     *                   applied to the candidates <em>and</em> to the exact-number shortcut, or
     *                   typing an id under «неизползвани» would surface a used image at the top of
     *                   a list whose whole claim is the opposite.
     * @param everything the candidates when there is no term. A supplier rather than a list,
     *                   because two of the three callers know the ids already and the third has to
     *                   read the collection.
     */
    private Page<ImageRow> pageByUsage(String search, Predicate<Integer> allowed,
                                       Sort.Direction direction, Pageable pageable,
                                       Supplier<List<ImageMedia>> everything) {

        String term = search == null ? "" : search.strip();
        Map<Integer, Long> counts = usageCounts();

        List<ImageMedia> candidates = (term.isEmpty()
                ? everything.get()
                : imageMediaRepository.findByFilenameContainingIgnoreCase(term))
                .stream()
                .filter(image -> allowed.test(image.getId()))
                .toList();

        // The id ends every key, so images the count cannot tell apart come back in the same order
        // on every render, and reversing the whole comparator rather than its first part keeps the
        // two directions exact inverses of one another -- both MgmtSort's rules.
        Comparator<ImageMedia> order = Comparator
                .<ImageMedia>comparingLong(image -> usageOf(counts, image.getId()))
                .thenComparingInt(image -> image.getId() == null ? Integer.MAX_VALUE : image.getId());

        List<ImageMedia> ordered = new ArrayList<>(candidates);
        ordered.sort(direction == Sort.Direction.DESC ? order.reversed() : order);

        /*
         * The exact-number shortcut, as page() applies it under every other key: an operator who
         * types an id is looking for that image and must find it whatever the list is ordered by.
         * Removed from the body first so it cannot appear twice, and pinned to the whole list
         * rather than to page one alone -- the slice below is over a list this method holds
         * entirely, so it can be honest about the position instead of returning an oversized first
         * page the way the paged branches have to.
         */
        Optional<ImageMedia> exact = numeric(term).filter(allowed).flatMap(imageMediaRepository::findById);
        if (exact.isPresent()) {
            Integer id = exact.get().getId();
            ordered.removeIf(image -> id.equals(image.getId()));
            ordered.add(0, exact.get());
        }

        int from = Math.min((int) pageable.getOffset(), ordered.size());
        int to = Math.min(from + pageable.getPageSize(), ordered.size());

        List<ImageRow> rows = ordered.subList(from, to).stream()
                .map(image -> row(image, usageOf(counts, image.getId())))
                .toList();

        return new PageImpl<>(rows, pageable, ordered.size());
    }

    /** Absent means zero: the aggregation only ever names images some article holds. */
    private static long usageOf(Map<Integer, Long> counts, Integer id) {
        Long count = id == null ? null : counts.get(id);
        return count == null ? 0L : count;
    }

    /**
     * Every image id that appears on at least one article, in one round trip.
     *
     * <p>Usage is stored on the article side, so "unused" has no direct query: it is the
     * complement of this set. Reading it by loading articles is out -- each one eager-loads three
     * {@code @DBRef} collections -- so this is the {@code $unwind} + {@code $group} pattern
     * {@code MgmtCategoryService.countArticlesByCategory} documents: grouping on the whole
     * {@code images} element, because a field path beginning with {@code $} is not a legal
     * aggregation expression and {@code images.$id} is exactly that. The id comes out of the
     * group key.
     *
     * <p>Deliberately <em>not</em> degraded to an empty set on failure, which is what the category
     * count does. An empty set here does not mean "no numbers on screen"; it means every image in
     * the library is listed as unused and offered for deletion. Better to fail the screen.
     * ({@code ImageService.deleteImage} would still refuse each one -- that guard is what makes
     * this a wrong screen rather than lost pictures.)
     */
    public Set<Integer> usedImageIds() {

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.unwind(IMAGES_FIELD),
                Aggregation.group(IMAGES_FIELD));

        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, ARTICLE_COLLECTION, Document.class);

        Set<Integer> used = new HashSet<>();
        for (Document row : results) {
            Integer id = MgmtDbRefs.idOf(row.get("_id"));
            if (id != null) {
                used.add(id);
            }
        }

        return used;
    }

    /**
     * How many articles hold each image, in one round trip.
     *
     * <p>{@link #usedImageIds()} with the counts kept: the same {@code $unwind} + {@code $group}
     * over every article's {@code images}, grouping on the whole element because {@code images.$id}
     * is not a legal aggregation expression, and reading the id back out of the group key through
     * {@link MgmtDbRefs#idOf} -- the third caller of that method, and the reason it is shared
     * rather than re-derived. See {@code MgmtCategoryService} for what one wrong guess about the
     * key's decoded shape costs: nothing throws, and every count reads zero.
     *
     * <p>Deliberately <em>not</em> a refactor of {@code usedImageIds()} into this method's key set,
     * and not because the stakes are lower here. This map is read twice per request: once by the
     * comparator and once by {@link #row(ImageMedia, long)}, which puts the number on the screen.
     * Degrading it to an empty map would therefore not merely misorder the list -- every row would
     * read «—» and the whole library would be presented as deletable, which is the exact screen
     * {@code usedImageIds()} refuses to produce. Both must fail rather than degrade. What separates
     * them is only that they are never needed by the same request: the usage filter belongs to the
     * screen, the count ordering to whichever screen asked for it.
     */
    public Map<Integer, Long> usageCounts() {

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.unwind(IMAGES_FIELD),
                Aggregation.group(IMAGES_FIELD).count().as("n"));

        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, ARTICLE_COLLECTION, Document.class);

        Map<Integer, Long> counts = new HashMap<>();
        for (Document row : results) {
            Integer id = MgmtDbRefs.idOf(row.get("_id"));
            if (id != null) {
                Object count = row.get("n");
                // merge rather than put: two group keys that decode to the same id are one image.
                counts.merge(id, count instanceof Number n ? n.longValue() : 0L, Long::sum);
            }
        }

        return counts;
    }

    /**
     * Resolves what the operator typed into the «статия» box into a set of image ids.
     *
     * <p>A number is that article; anything else is a title search. Both are done here rather than
     * in the query above because the answer is bounded either way -- ten images an article, and at
     * most {@value #ARTICLE_MATCH_LIMIT} articles -- while an image-side query for "attached to an
     * article whose title matches" does not exist at all: the reference points the other way.
     *
     * <p>The cap is reported rather than applied quietly: {@link ArticleFilter#capped()} drives a
     * line on the screen naming how many articles matched, because a filter that silently used the
     * first twenty-five of two hundred looks exactly like one that found twenty-five.
     */
    public ArticleFilter resolveArticleFilter(String article) {

        String term = article == null ? "" : article.strip();
        if (term.isEmpty()) {
            return ArticleFilter.none();
        }

        Optional<Integer> id = numeric(term);
        if (id.isPresent()) {
            Optional<Article> found = articleRepository.findById(id.get());
            return new ArticleFilter(term,
                    imageIdsOf(found.map(List::of).orElseGet(List::of)),
                    found.isPresent() ? 1 : 0,
                    false);
        }

        Page<Article> matches = articleRepository.findArticleByTitleContainingIgnoreCase(
                term, PageRequest.of(0, ARTICLE_MATCH_LIMIT, Sort.by(Sort.Direction.DESC, "_id")));

        return new ArticleFilter(term,
                imageIdsOf(matches.getContent()),
                matches.getTotalElements(),
                matches.getTotalElements() > ARTICLE_MATCH_LIMIT);
    }

    /** In attachment order, deduplicated: one image can be on several of the matched articles. */
    private List<Integer> imageIdsOf(List<Article> articles) {
        Set<Integer> ids = new LinkedHashSet<>();

        for (Article article : articles) {
            if (article.getImages() == null) {
                continue;
            }
            for (ImageMedia image : article.getImages()) {
                if (image != null && image.getId() != null) {
                    ids.add(image.getId());
                }
            }
        }

        return List.copyOf(ids);
    }

    /** Images uploaded since {@code since}, for the dashboard; {@code createdOn} is set on upload. */
    public long countCreatedSince(LocalDateTime since) {
        return mongoTemplate.count(
                new Query(Criteria.where("createdOn").gte(since)),
                ImageMedia.class);
    }

    public Optional<ImageMedia> find(Integer id) {
        return id == null ? Optional.empty() : imageMediaRepository.findById(id);
    }

    public ImageRow row(ImageMedia image) {
        return row(image, articleRepository.countArticleByImages(image.getId()));
    }

    /**
     * The same row with the count already in hand.
     *
     * <p>For {@link #pageByUsage}, which has just read every count in one aggregation: going back
     * to the repository per row would be a query apiece for a number the method is holding.
     */
    public ImageRow row(ImageMedia image, long usages) {
        return new ImageRow(
                image.getId(),
                image.getFilename(),
                image.getCaption(),
                publicPath(image),
                usages);
    }

    /**
     * The articles holding this image, newest first.
     *
     * <p>The answer to "may I delete this?", spelled out. The list screen can only say <em>how
     * many</em> -- the count is one cheap query per row -- and a number is no help when the
     * operator has to go and detach the picture from each of them.
     */
    public List<ImageUsage> usedBy(Integer id) {
        if (id == null) {
            return List.of();
        }

        return articleRepository.findArticleByImages(id).stream()
                .map(article -> new ImageUsage(
                        article.getId(), article.getTitle(), article.getStatus(), article.getDate()))
                .toList();
    }

    /**
     * Reads the file behind an image, without decoding it.
     *
     * <p>Dimensions come from an {@link ImageReader} reading the header rather than from
     * {@code ImageIO.read}, which would decode every pixel of every file into a
     * {@code BufferedImage} to report two integers.
     *
     * <p>Never throws: every failure here is something the screen should <em>report</em>. A
     * missing file, an unreadable one and an unrecognised format are three different facts, and
     * an exception page would turn all three into "the console is broken".
     */
    public ImageFile fileOf(ImageMedia image) {

        Path path = fileFor(image);
        if (path == null || !Files.isRegularFile(path)) {
            return ImageFile.missing();
        }

        long bytes;
        LocalDateTime modifiedOn;
        try {
            bytes = Files.size(path);
            modifiedOn = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            log.warn("Could not stat image file {}", path, e);
            return ImageFile.missing();
        }

        Integer width = null;
        Integer height = null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = stream == null
                    ? Collections.emptyIterator()
                    : ImageIO.getImageReaders(stream);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            // A truncated or malformed file throws from the reader, not from the open. The size
            // and the timestamp above are still true, so they are still reported.
            log.warn("Could not read the dimensions of image file {}", path, e);
        }

        return new ImageFile(true, bytes, width, height, modifiedOn);
    }

    /**
     * Writes the caption and nothing else.
     *
     * <p>Deliberately not a form object bound over the document. {@code filename} is what locates
     * the file on disk and is a unique index; Spring Data {@code save()} writes the whole
     * document, so a bindable filename would orphan the file and break the {@code <img>} on every
     * article holding it -- the same silent failure {@code BannerForm} keeps {@code impressions}
     * and {@code clicks} out of itself to avoid. The caption is the only field on an
     * {@code ImageMedia} that is text an operator wrote, so it is the only one this screen edits.
     *
     * <p>Blank becomes null: an upload seeds the caption from the multipart resource description
     * ("MultipartFile resource [file]"), and clearing that has to mean absent rather than an empty
     * string the public site would render as a caption.
     */
    public ImageMedia saveCaption(Integer id, String caption) {

        ImageMedia image = find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.imageNotFound", id));

        image.setCaption(blankToNull(caption));

        ImageMedia saved = imageMediaRepository.save(image);
        log.info("Updated the caption of image {} ({})", id, image.getFilename());
        return saved;
    }

    /**
     * The caption and the alt text per language, together -- the one form of the image screen. An
     * alt left blank is removed for that language ({@code TextItems.put}); codes that are not a
     * language's shape are ignored.
     */
    public ImageMedia saveDescription(Integer id, String caption, java.util.Map<String, String> alts) {
        ImageMedia image = find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.imageNotFound", id));
        image.setCaption(blankToNull(caption));
        java.util.List<io.pacod.cms.core.data.TextItem> items = image.getAlts();
        for (java.util.Map.Entry<String, String> alt : alts.entrySet()) {
            if (alt.getKey() != null && alt.getKey().matches("[a-z]{2,3}")) {
                String value = alt.getValue() == null ? null : alt.getValue().strip();
                items = io.pacod.cms.core.data.TextItems.put(items, alt.getKey(),
                        value != null && value.length() > 300 ? value.substring(0, 300) : value);
            }
        }
        image.setAlts(items);
        ImageMedia saved = imageMediaRepository.save(image);
        log.info("Updated the caption and alt text of image {} ({})", id, image.getFilename());
        return saved;
    }

    /** The image's file, for a model to look at; empty when absent, unreadable or outside the folder. */
    public Optional<byte[]> bytesOf(ImageMedia image) {
        Path file = fileFor(image);
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(java.nio.file.Files.readAllBytes(file));
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }

    /** The URL the file answers on, which is also what an operator pastes into a body. */
    public static String publicPath(ImageMedia image) {
        return image == null || image.getFilename() == null
                ? ""
                : PUBLIC_PREFIX + image.getFilename();
    }

    /**
     * Deletes an image outright, file and document.
     *
     * <p>Refuses while any article still holds it. {@code ImageService.deleteImage} enforces that
     * too and is the guard that actually counts -- this one exists so the console can say
     * <em>why</em> instead of reporting a silent no-op, which is what a bare {@code false} return
     * would look like on screen.
     */
    public void delete(Integer id) {
        ImageMedia image = find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.imageNotFound", id));

        long usages = articleRepository.countArticleByImages(id);
        if (usages > 0) {
            throw new MgmtOperationException("articles.error.imageInUse", usages);
        }

        if (!imageService.deleteImage(id)) {
            throw new MgmtOperationException("articles.error.imageFileNotDeleted");
        }

        log.info("Deleted image {} ({})", id, image.getFilename());
    }

    /**
     * The file a document names, or null when the name would leave the image folder.
     *
     * <p>{@code filename} comes from the name the browser sent for an upload -- reduced by
     * {@code ImageService.safeFilename} on the way in, but a document may also have been written
     * by hand. Other paths in the codebase concatenate it onto the folder and open the result,
     * which is fine while the only operations are write-then-delete of a name this application
     * chose. This method opens an arbitrary stored name for <em>reading</em> and reports what it
     * finds, so it checks that the name stays inside the folder rather than assuming the
     * collection has never been written to by hand.
     */
    private Path fileFor(ImageMedia image) {
        if (image == null || image.getFilename() == null || image.getFilename().isBlank()) {
            return null;
        }

        Path folder = Path.of(SiteStorage.folder(imageFolder)).toAbsolutePath().normalize();
        Path file = folder.resolve(image.getFilename()).normalize();

        return file.startsWith(folder) ? file : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Optional<Integer> numeric(String term) {
        try {
            return Optional.of(Integer.valueOf(term));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
