package io.pacod.cms.studio;

import io.pacod.cms.core.data.ArticleStatus;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.services.ArticleService;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.studio.form.ArticleForm;
import io.pacod.cms.studio.form.TranslationForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the console does to articles.
 *
 * <p>Reads and writes MongoDB directly through the existing repositories and
 * {@link ArticleService}, the same way {@code WebArticleService} does. The browser calls nothing
 * but the studio, so no credential for any other service is ever shipped to it.
 *
 * <h2>Cache eviction: there is none, and that is the topology speaking</h2>
 * The caches a console edit makes stale -- the public site's {@code articles}, {@code widgets} and
 * {@code categories} -- live in another JVM, which no {@code @CacheEvict} here can reach. What
 * bounds staleness after a console edit is {@code CacheConfig}'s TTL and nothing else -- 60s
 * across the board.
 *
 * <p>The honest options if that is ever not good enough are the ones {@code CacheConfig} already
 * names: a shared cache, or an invalidation endpoint the writer calls. Adding
 * {@code @CacheEvict} is not one of them -- this process has no cache manager, so the annotations
 * would do nothing while reading like a guarantee.
 */
@Slf4j
@Service
public class MgmtArticleService {

    /** The most images one article may hold, whichever route attaches them. */
    public static final int MAX_IMAGES_PER_ARTICLE = 10;

    private final ArticleRepository articleRepository;
    private final ArticleService articleService;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final ImageMediaRepository imageMediaRepository;
    private final ImageService imageService;

    public MgmtArticleService(ArticleRepository articleRepository,
                              ArticleService articleService,
                              AuthorRepository authorRepository,
                              CategoryRepository categoryRepository,
                              ImageMediaRepository imageMediaRepository,
                              ImageService imageService) {
        this.articleRepository = articleRepository;
        this.articleService = articleService;
        this.authorRepository = authorRepository;
        this.categoryRepository = categoryRepository;
        this.imageMediaRepository = imageMediaRepository;
        this.imageService = imageService;
    }

    /**
     * The console's article list.
     *
     * <p>Precedence: a search term wins over a category, which wins over an author. Only one
     * filter is ever applied.
     */
    public Page<Article> list(String search, Integer categoryId, Integer authorId, Pageable pageable) {

        if (search != null && !search.isBlank()) {
            return articleRepository.fulltextSearch(search.strip(), pageable);
        }
        if (categoryId != null && categoryId > 0) {
            return articleRepository.findArticleByCategories(categoryId, pageable);
        }
        if (authorId != null && authorId > 0) {
            return articleRepository.findArticleByAuthors(authorId, pageable);
        }
        return articleRepository.findAllArticle(pageable);
    }

    public Optional<Article> find(Integer id) {
        return id == null ? Optional.empty() : articleRepository.findById(id);
    }

    /**
     * Creates an empty article and returns it.
     *
     * <p>Called from an explicit POST, never from a GET: creating a row as a side effect of a GET
     * would leave a new empty article behind on every visit to the editor -- a reload, or a
     * crawler.
     */
    public Article create() {
        return articleService.createNewArticle();
    }

    /**
     * Applies the form onto the stored document.
     *
     * <p>Deliberately a read-modify-write rather than a replace: the fields the console does not
     * edit ({@code titles}, {@code texts}, {@code descriptions}, {@code lang},
     * {@code titleClasses}, {@code suppressArticleInfo}, {@code counter}, {@code createdOn},
     * {@code images}) must survive an edit untouched.
     */
    public Article save(Integer id, ArticleForm form) {
        return save(id, form, true);
    }

    /**
     * {@link #save(Integer, ArticleForm)} for someone who may or may not publish. Without
     * {@code content.publish} the article's place in front of readers does not move: a status
     * change into or out of {@code PUBLISHED} is ignored (the editor shows the control disabled),
     * any other change applies.
     */
    public Article save(Integer id, ArticleForm form, boolean mayPublish) {
        Article article = require(id);

        // Keep one level of history for the body, and only when it actually changed.
        //
        // The console's editor parses HTML into a schema and re-serializes it. Its configuration
        // is tuned hard against rewriting stored bodies, but some rewriting is unavoidable --
        // TinyMCE's forced_root_block cannot be switched off, so a body that begins with bare text
        // or an inline element is rewritten on open. Combined with the editor's load-time warning,
        // this turns "an old article was silently reformatted" into something the operator sees
        // and can undo. Only console saves write it, so only edited articles pay the storage.
        if (!Objects.equals(article.getText(), form.getText())) {
            article.setPreviousText(article.getText());
        }

        article.setTitle(form.getTitle());
        article.setDate(form.getDate());
        article.setDescription(form.getDescription());
        article.setText(form.getText());
        // blankToNull, not the raw value. An empty textarea posts "", and a template that tested
        // this field for null alone would put an empty .video-container on the page -- which
        // carries the responsive-iframe aspect-ratio rule and therefore draws as a black box --
        // AND suppress the article's gallery and the widget thumbnail. One save of an article
        // with no video would be enough. The templates test for empty as well, but the data
        // should not carry the trap.
        article.setEmbedded(blankToNull(form.getEmbedded()));
        article.setPriority(form.getPriority());
        article.setTitleOverlayClasses(blankToNull(form.getTitleOverlayClasses()));
        if (mayPublish || !crossesPublished(article.getStatus(), form.getStatus())) {
            article.setStatus(form.getStatus());
        }
        article.setTags(new ArrayList<>(form.tagList()));
        article.setAuthors(resolveAuthors(form.getAuthorIds()));
        article.setCategories(resolveCategories(form.getCategoryIds()));

        log.info("Saving article {}", article.toLongView());
        return articleRepository.save(article);
    }

    static boolean crossesPublished(ArticleStatus before, ArticleStatus after) {
        return after == null || (before == ArticleStatus.PUBLISHED) != (after == ArticleStatus.PUBLISHED);
    }

    /**
     * Writes one existing translation: that language's entries in {@code titles},
     * {@code descriptions} and {@code texts}, and nothing else.
     *
     * <p>Every other language, the original-language scalars, {@code previousText} (which covers
     * the original body alone), the images and the classification are left exactly as stored. A
     * blank description removes that entry rather than storing {@code ""}; see
     * {@link ArticleTranslations}.
     *
     * <p>Only languages the article is already translated into are accepted -- new translations
     * come from translation jobs.
     */
    public Article saveTranslation(Integer id, String lang, TranslationForm form) {
        Article article = require(id);
        String code = requireTranslation(article, lang);

        article.setTitles(ArticleTranslations.put(article.getTitles(), code, form.getTitle()));
        article.setDescriptions(ArticleTranslations.put(article.getDescriptions(), code, form.getDescription()));
        article.setTexts(ArticleTranslations.put(article.getTexts(), code, form.getText()));

        log.info("Saving the {} translation of article {}", code, id);
        return articleRepository.save(article);
    }

    /**
     * Removes one translation -- title, description and body in that language. The original
     * language is the source, held in the scalar fields, and is refused.
     *
     * @return the normalised language code that was removed
     */
    public String removeTranslation(Integer id, String lang) {
        Article article = require(id);
        String code = requireTranslation(article, lang);

        article.setTitles(ArticleTranslations.put(article.getTitles(), code, null));
        article.setDescriptions(ArticleTranslations.put(article.getDescriptions(), code, null));
        article.setTexts(ArticleTranslations.put(article.getTexts(), code, null));

        log.info("Removed the {} translation of article {}", code, id);
        articleRepository.save(article);
        return code;
    }

    /**
     * The normalised code, when it names a translation this article has.
     *
     * <p>The original language ({@code LanguageChain.original()}) is refused: an entry in
     * {@code texts} under that code is not what the site renders (the source lives in the scalar
     * {@code text}) -- see {@code Article.translatedIn()}.
     */
    public static String requireTranslation(Article article, String lang) {
        String code = LanguageChain.normalise(lang);
        if (code == null || LanguageChain.original().equals(code)) {
            throw new MgmtOperationException("articles.error.originalInMainScreen");
        }
        if (!article.translatedIn().contains(code)) {
            throw new MgmtOperationException("articles.error.noTranslation", code.toUpperCase());
        }
        return code;
    }

    /**
     * Puts {@code previousText} back into {@code text}, swapping the two.
     *
     * <p>The swap rather than a one-way move is deliberate: restoring is itself an edit, so it has
     * to be undoable too. Pressing restore twice returns you where you started.
     */
    public Article restoreText(Integer id) {
        Article article = require(id);

        if (article.getPreviousText() == null) {
            throw new MgmtOperationException("articles.error.noPreviousText");
        }

        String current = article.getText();
        article.setText(article.getPreviousText());
        article.setPreviousText(current);

        log.info("Restored the previous body of article {}", id);
        return articleRepository.save(article);
    }

    public void delete(Integer id) {
        Article article = require(id);
        log.info("Deleting article {}", article);
        // Cascades the image files and their documents.
        articleService.deleteArticle(article);
    }

    /**
     * Stores the uploaded file, attaches it to the article and saves in the same request.
     *
     * <p>Persisted at once rather than held until the next save: uploading and then navigating
     * away must not leave the file on disk owned by nothing.
     */
    public ImageMedia addImage(Integer id, MultipartFile file) throws MediaException {
        Article article = require(id);

        List<ImageMedia> images = article.getImages() == null
                ? new ArrayList<>()
                : new ArrayList<>(article.getImages());

        if (images.size() >= MAX_IMAGES_PER_ARTICLE) {
            throw new MediaException(String.format(
                    "The article already has %d images; the limit is %d.",
                    images.size(), MAX_IMAGES_PER_ARTICLE),
                    "articles.error.imageCap", images.size(), MAX_IMAGES_PER_ARTICLE);
        }

        ImageMedia image = imageService.save(file);

        if (image == null) {
            throw new MediaException("The upload produced no image.", "articles.error.noImageProduced");
        }

        images.add(image);
        article.setImages(images);
        article.setHiddenImageIds(withoutHidden(article, images, image.getId()));
        articleRepository.save(article);

        log.info("Attached image {} to article {}", image.getId(), id);
        return image;
    }

    /**
     * Attaches an image that is already on the platform, without re-uploading it.
     *
     * <p>The second way an image reaches an article, and the reason
     * {@code ImageService.deleteImage} counts references: an {@code ImageMedia} can belong to
     * several articles, so deleting it cannot be unconditional.
     *
     * @throws MgmtOperationException when the article is full or already holds the image
     */
    public ImageMedia attachImage(Integer id, Integer imageId) {
        Article article = require(id);

        ImageMedia image = imageMediaRepository.findById(imageId).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.imageNotFound", imageId));

        List<ImageMedia> images = article.getImages() == null
                ? new ArrayList<>()
                : new ArrayList<>(article.getImages());

        // By id, not by object. ImageMedia is a Lombok @Data, so its equals() includes
        // createdOn/lastModifiedOn -- two reads of the same @DBRef can compare unequal, and
        // contains() would then happily attach the same picture twice.
        boolean already = images.stream()
                .anyMatch(i -> i != null && imageId.equals(i.getId()));

        if (already) {
            throw new MgmtOperationException("articles.error.alreadyAttached");
        }

        // The cap has to be here too, not only on the upload path: it is a limit on the article,
        // and images arrive by two routes.
        if (images.size() >= MAX_IMAGES_PER_ARTICLE) {
            throw new MgmtOperationException("articles.error.imageCap",
                    images.size(), MAX_IMAGES_PER_ARTICLE);
        }

        images.add(image);
        article.setImages(images);
        // An attach always lands visible. Without this, hiding an image, removing it and later
        // re-attaching it would bring it back already hidden, with nothing on screen saying why.
        article.setHiddenImageIds(withoutHidden(article, images, imageId));
        articleRepository.save(article);

        log.info("Attached existing image {} to article {}", imageId, id);
        return image;
    }

    /**
     * Rewrites the image order and the hidden set in one go.
     *
     * <p>Order is the whole mechanism for choosing a cover: the public site reads the first
     * <em>visible</em> image as the share card and every list thumbnail, and renders the rest as
     * the gallery strip in this order. There is no separate cover field to keep in step.
     *
     * <p>Saved immediately rather than with the article form, which matches how uploads on this
     * screen already behave -- and is what lets the console do it without a page reload, which
     * would take the operator's unsaved rich-text edits with it.
     */
    public Article arrangeImages(Integer id, List<Integer> orderedIds, List<Integer> hiddenIds) {
        Article article = require(id);

        List<ImageMedia> current = article.getImages() == null
                ? new ArrayList<ImageMedia>()
                : new ArrayList<>(article.getImages());

        Map<Integer, ImageMedia> byId = new LinkedHashMap<>();
        for (ImageMedia image : current) {
            if (image != null && image.getId() != null) {
                byId.put(image.getId(), image);
            }
        }

        // Same defensive shape as resolve(): take the ids the browser sent that this article
        // actually holds, then append anything it holds that the browser did not mention. A stale
        // tab must not be able to detach an image by omitting it from the order.
        List<ImageMedia> arranged = new ArrayList<>(current.size());
        if (orderedIds != null) {
            for (Integer wanted : orderedIds) {
                ImageMedia image = byId.remove(wanted);
                if (image == null) {
                    log.warn("Ignoring image id {} that article {} does not hold", wanted, id);
                } else {
                    arranged.add(image);
                }
            }
        }
        arranged.addAll(byId.values());

        article.setImages(arranged);
        article.setHiddenImageIds(retainHidden(hiddenIds, arranged));
        articleRepository.save(article);

        log.info("Arranged the images of article {}: order {}, hidden {}",
                id, arranged.stream().map(ImageMedia::getId).toList(), article.getHiddenImageIds());
        return article;
    }

    public void removeImage(Integer id, Integer imageId) {
        Article article = require(id);

        List<ImageMedia> remaining = new ArrayList<>();
        if (article.getImages() != null) {
            for (ImageMedia image : article.getImages()) {
                if (image != null && !image.getId().equals(imageId)) {
                    remaining.add(image);
                }
            }
        }

        // Detach first: if the file delete fails the article is already clean, whereas the
        // reverse order can leave an article pointing at a document that is gone.
        article.setImages(remaining);
        // And prune the hidden set, or a removed id lingers there and silently re-hides the image
        // if it is ever attached again from the library.
        article.setHiddenImageIds(retainHidden(article, remaining));
        articleRepository.save(article);

        // Deletes the file and the document only if no other article holds this image.
        imageService.deleteImage(imageId);
        log.info("Removed image {} from article {}", imageId, id);
    }

    /** The article's existing hidden ids, narrowed to images it still holds. */
    private List<Integer> retainHidden(Article article, List<ImageMedia> images) {
        return retainHidden(article.getHiddenImageIds(), images);
    }

    /**
     * As {@link #retainHidden(Article, List)}, minus one id that has just been attached.
     *
     * <p>Not paranoia: {@code ImageService.getNextId()} is max-id-plus-one, so deleting the
     * highest-numbered image hands that id straight to the next upload. A hidden id that outlived
     * its image could therefore be inherited by an entirely different picture.
     */
    private List<Integer> withoutHidden(Article article, List<ImageMedia> images, Integer attached) {
        List<Integer> hidden = retainHidden(article, images);
        hidden.remove(attached);
        return hidden;
    }

    /**
     * Narrows a hidden set to ids the article actually holds.
     *
     * <p>Every path that changes {@code images} runs through this. A hidden id that outlives its
     * image is invisible in the console and only shows itself much later, when the same image is
     * attached again and arrives already hidden.
     */
    private List<Integer> retainHidden(List<Integer> hiddenIds, List<ImageMedia> images) {
        if (hiddenIds == null || hiddenIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<Integer> attached = images.stream()
                .filter(Objects::nonNull)
                .map(ImageMedia::getId)
                .toList();

        List<Integer> hidden = new ArrayList<>(hiddenIds.size());
        for (Integer wanted : hiddenIds) {
            if (attached.contains(wanted) && !hidden.contains(wanted)) {
                hidden.add(wanted);
            }
        }
        return hidden;
    }

    /**
     * Every author, for the editor's picker.
     *
     * <p>Unpaged on purpose: a picker fed a page of 20 would make the twenty-first author
     * impossible to select.
     */
    public List<Author> allAuthors() {
        List<Author> authors = new ArrayList<>(authorRepository.findAll());
        authors.sort(Comparator.comparing(Author::fullname, String.CASE_INSENSITIVE_ORDER));
        return authors;
    }

    /**
     * Every author, ordered for the editor's picker: the ones this article already carries first,
     * in the order they are stored, then the rest alphabetically.
     *
     * <p>Not cosmetic. A {@code <select multiple>} submits its selected options in <em>tree
     * order</em>, so the order of the {@code <option>} elements <em>is</em> the order the operator
     * can express -- there is no second parameter, and adding one would be the thing the picker's
     * whole design forbids. Rendering the list alphabetically would therefore not merely fail to
     * show the byline's order, it would destroy it: every console save would post the article's
     * authors in alphabetical order and {@link #save} stores exactly what it is sent, so
     * {@code ArticleModel.getAuthorsAsText()} -- which walks the stored list -- would print a
     * byline the operator had never chosen and could not correct.
     *
     * <p>Takes the ids from the <em>form</em> rather than from the document, so the re-render after
     * a validation error keeps the order the operator was working with instead of reverting to what
     * is stored.
     *
     * <p>Unknown and duplicated ids are dropped, exactly as {@code resolve()} drops them on save --
     * an author deleted in another tab must not leave a hole in the list.
     */
    public List<Author> authorsForPicker(List<Integer> selectedIds) {
        List<Author> all = allAuthors();

        if (selectedIds == null || selectedIds.isEmpty()) {
            return all;
        }

        Map<Integer, Author> remaining = new LinkedHashMap<>();
        for (Author author : all) {
            remaining.put(author.getId(), author);
        }

        List<Author> ordered = new ArrayList<>(all.size());
        for (Integer wanted : selectedIds) {
            // remove(), so a duplicated id contributes one row rather than two.
            Author author = remaining.remove(wanted);
            if (author != null) {
                ordered.add(author);
            }
        }
        ordered.addAll(remaining.values());

        return ordered;
    }

    /** Every category, for the pickers and the filter. Unpaged, for the same reason. */
    public List<Category> allCategories() {
        List<Category> categories = new ArrayList<>(categoryRepository.findAll());
        categories.sort(Comparator.comparing(
                c -> c.getName() == null ? "" : c.getName(), String.CASE_INSENSITIVE_ORDER));
        return categories;
    }

    private List<Author> resolveAuthors(List<Integer> ids) {
        return resolve(ids, authorRepository.findAll(), Author::getId);
    }

    private List<Category> resolveCategories(List<Integer> ids) {
        return resolve(ids, categoryRepository.findAll(), Category::getId);
    }

    /**
     * Maps submitted ids back to documents, preserving the order the operator chose and silently
     * dropping ids that no longer exist (an author deleted in another tab, say).
     */
    private <T> List<T> resolve(List<Integer> ids, List<T> all, java.util.function.Function<T, Integer> id) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Integer, T> byId = new LinkedHashMap<>();
        for (T item : all) {
            byId.put(id.apply(item), item);
        }

        List<T> resolved = new ArrayList<>(ids.size());
        for (Integer wanted : ids) {
            T item = byId.get(wanted);
            if (item == null) {
                log.warn("Ignoring unknown id {} submitted by the console", wanted);
            } else {
                resolved.add(item);
            }
        }
        return resolved;
    }

    private Article require(Integer id) {
        return find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.articleNotFound", id));
    }

    /** An empty select value means "no override", which the templates read as absent. */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
