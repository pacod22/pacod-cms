package io.pacod.cms.core.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.repository.ArticleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ArticleService {

    /** Limits the max-id lookup to a single document. */
    private static final Pageable TOP_ONE = PageRequest.of(0, 1);

    private final static int DEFAULT_MONTHS_FOR_MOSTREAD = 1;

    @Autowired
    private ArticleRepository articleRepository;

    /** Optional, so the hand-built tests need none. */
    @Autowired(required = false)
    private io.pacod.cms.core.repository.PdfEnrichmentRepository pdfEnrichments;


    @Autowired
    private ImageService imageService;

    @Value("${app.article.defaultTitle}")
    protected String defaultTitle;

    @Value("${app.article.defaultText}")
    protected String defaultText;

    @Value("${app.article.defaultDescription}")
    protected String defaultDescription;

    /**
     * Ids are assigned by the application rather than by MongoDB.
     *
     * <p>Reads a single document off the {@code _id} index, never the id of every article in
     * the collection.
     *
     * <p>Note this remains a read-then-write, so two simultaneous creates can still pick the
     * same id. Article creation is an authenticated admin action, so the window is narrow;
     * eliminating it entirely needs an atomic counter document.
     */
    private Integer getNextId() {
        Integer id = articleRepository.getMaxId(TOP_ONE)
                .stream()
                .findFirst()
                .map(a -> a.getId() + 1)
                .orElse(1);
        log.info("Next article id: {}", id);
        return id;
    }

    public Article createNewArticle() {
        log.info("Start createNewArticle");
        Integer id = getNextId();

        Article article = null;

        if(id != null && id > 0) {
            LocalDateTime now = LocalDateTime.now();

            article = new Article(id);

            article.setStatus(ArticleStatus.NEW);
            article.setTitle(defaultTitle);
            article.setText(defaultText);
            article.setDescription(defaultDescription);
            article.setDate(now);
            article.setCreatedOn(now);
            article.setLastModifiedOn(now);
            article.setCounter(0);
            article.setImages(new ArrayList<>());
            article.setCategories(new ArrayList<>());
            article.setAuthors(new ArrayList<>());

            articleRepository.save(article);

        }

        return article;
    }

    public Optional<Article> getArticleById(Integer id) {

        log.debug("Start getArticleById( id = {})", id);

        Optional<Article> article;

        if(id != null && id > 0) {
            article = articleRepository.findById(id);
            if (article.isEmpty()) {
                log.warn("Article for id={} is not existing", id);
            }
        } else {
            log.warn("Invalide article id={}", id);
            article = Optional.empty();
        }

        return article;
    }

    public Optional<Article> getArticleByIdWithCreate(Integer id) {

        log.debug("Start getArticleByIdWithCreate( id = {})", id);


        Optional<Article> article;

        if(id != null && id > 0) {
            article = articleRepository.findById(id);

            // No article.get() before the isEmpty() check below, whatever the log level: for an
            // unknown id it throws NoSuchElementException, a guaranteed HTTP 500.
            if (article.isEmpty()) {
                log.warn("No article with id={}; creating a new one as a side effect of a read. "
                        + "This endpoint is a GET; prefer an explicit create call.", id);
                article = Optional.ofNullable(createNewArticle());
            }
        } else {
            article = Optional.ofNullable(createNewArticle());
        }

        if(article.isPresent()) log.debug("article: {}", article.get().toLongView());

        return article;
    }

    public void deleteArticle(Article article) {
        if(article != null) {
            // The article document goes FIRST, and that order is load-bearing.
            //
            // ImageService.deleteImage refuses to delete an image any article still holds, because
            // the console's image library lets one image belong to several. Cleaning the images
            // while this article still exists would count it as a reference, so nothing would ever
            // be deleted and every deleted article would leave its files behind. It is also the
            // same "detach before you delete the file" ordering MgmtArticleService.removeImage
            // uses, for the same reason: a failed file delete leaves nothing pointing at a
            // document that is gone.
            articleRepository.deleteById(article.getId());
            cleanArticle(article);
            // Its PDF abstracts: written for its text, read by nothing once it is gone.
            if (pdfEnrichments != null && article.getId() != null) {
                pdfEnrichments.deleteByArticleId(article.getId());
            }
        }
    }

    /** Deletes the images this article held, skipping any another article also holds. */
    private void cleanArticle(Article article) {
        // The list itself may be null -- an article that never had images -- not just its
        // elements; checking only the elements throws NullPointerException on delete.
        if (article.getImages() == null) {
            return;
        }
        for (ImageMedia image : article.getImages()) {
            if (image != null) imageService.deleteImage(image.getId());
        }
    }

    public List<Article> getMostReadArticles () {

        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusMonths(DEFAULT_MONTHS_FOR_MOSTREAD);

        // The public site's most-read list: published articles only (ArticleRepository's public reads).
        List<Article> mostReadArticles = articleRepository.findTop3ByIdGreaterThanAndStatusAndDateBetweenOrderByCounterDesc(
                0, ArticleRepository.PUBLIC, from, to);

        return mostReadArticles;
    }
}
