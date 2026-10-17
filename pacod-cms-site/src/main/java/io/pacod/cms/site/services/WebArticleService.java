package io.pacod.cms.site.services;


import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.services.ArticleService;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.ArticleList;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.core.services.ArticleViewCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class WebArticleService {

    /** Synthetic pseudo-category id for the "most read" list; not a real category document. */
    private final static Integer MOST_READ_ID = 100001;

    private final ArticleRepository articleRepository;
    private final ArticleService articleService;
    private final CategoryRepository categoryRepository;
    private final AuthorRepository authorRepository;
    private final ArticleReader articleReader;
    private final ArticleViewCounter viewCounter;

    public WebArticleService(ArticleRepository articleRepository,
                             ArticleService articleService,
                             CategoryRepository categoryRepository,
                             AuthorRepository authorRepository,
                             ArticleReader articleReader,
                             ArticleViewCounter viewCounter) {
        this.articleRepository = articleRepository;
        this.articleService = articleService;
        this.categoryRepository = categoryRepository;
        this.authorRepository = authorRepository;
        this.articleReader = articleReader;
        this.viewCounter = viewCounter;
    }

    /**
     * The backing query interpolates this value straight into a MongoDB regex, so anything
     * the caller types is otherwise live regex syntax. Escaping it closes a denial-of-service
     * hole -- a search for {@code (a+)+$} could pin the server on catastrophic backtracking --
     * and makes the search mean what a reader expects: a literal substring.
     */
    public Page<ListItem> searchArticlesPageable(String text, Pageable pageable, String lang) {
        log.debug("Start searchArticlesPageable(text={}, pageable={})", text, pageable);

        Page<Article> page = articleRepository.fulltextSearchPublished(escapeForRegex(text), pageable);

        return convertArticlePageToListItemPage(page, lang);
    }

    /**
     * Escapes regex metacharacters individually rather than using {@link java.util.regex.Pattern#quote},
     * because {@code \Q...\E} block quoting is a Java construct that MongoDB's PCRE engine
     * does not honour.
     */
    static String escapeForRegex(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        StringBuilder escaped = new StringBuilder(input.length() + 8);
        for (char c : input.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }


    public Page<ListItem> getAllArticlesPageable(Pageable pageable, String lang) {
        log.debug("Start getAllArticlesPageable(pageable={})", pageable);

        Page<Article> page = articleRepository.findPublished(pageable);

        Page<ListItem> pageweb = convertArticlePageToListItemPage(page, lang);

        return pageweb;
    }

    /** Spring Data's {@link Page#map} already preserves pageable and total-element metadata. */
    private Page<ListItem> convertArticlePageToListItemPage(Page<Article> page, String lang) {
        return page.map(a -> new ListItem(a, lang));
    }

    private List<ListItem> convertArticleListToArticleWebList(List<Article> articles, String lang) {
        if (articles == null) {
            return new ArrayList<>();
        }
        return articles.stream()
                .filter(java.util.Objects::nonNull)
                .map(a -> new ListItem(a, lang))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * The newest article, used as the home page's content. Reads a single document off the
     * index rather than loading the collection and taking the first element.
     */
    public Optional<Article> getLatestArticle() {
        return articleRepository.findPublished(org.springframework.data.domain.PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    /** Paginated replacement for {@link #getAllArticles()}. */
    public Page<Article> getAllArticlesPaged(Pageable pageable) {
        log.debug("Start getAllArticlesPaged(pageable={})", pageable);
        return articleRepository.findPublished(pageable);
    }

    public List<Article> getAllArticles() {
        log.debug("Start getAllArticles()");

        return articleRepository.findPublished();
    }

    /**
     * Loads an article for display and records the view.
     *
     * <p>The body is served from cache; the view counter is incremented atomically on every
     * call and applied to a per-request copy. The cached instance is never mutated, so
     * concurrent readers cannot observe each other's counter writes, and the number rendered
     * on the page is the true post-increment value rather than a cached snapshot.
     *
     * <p>A request {@code CrawlerTrafficFilter} marked as automated reads the article without
     * counting it: {@code Article.counter} feeds the most-read list, and a crawler is not a reader.
     * The page then shows the cached count, which is at most the cache's TTL behind.
     */
    public Optional<Article> getArticleById(Integer id) {

        log.debug("Start getArticleById( id = {})", id);

        if (id == null || id <= 0) {
            log.warn("Invalid article id={}", id);
            return Optional.empty();
        }

        Article cached = articleReader.findById(id);
        if (cached == null) {
            log.warn("Article for id={} is not existing", id);
            return Optional.empty();
        }
        // Before the counter: an unpublished article is not there for a reader, and not counted.
        // The cache holds it either way, so publishing shows within its TTL. The studio's draft
        // preview -- a token PreviewFilter checked against this website, uncounted -- sees it.
        if (!isPublic(cached) && io.pacod.cms.site.render.SitePreview.current().isEmpty()) {
            log.debug("Article id={} is not published ({})", id, cached.getStatus());
            return Optional.empty();
        }

        Integer liveCount = CrawlerTraffic.currentIsUncounted() ? null : viewCounter.recordView(id);

        Article view = new Article(cached);
        view.setCounter(liveCount != null ? liveCount : cached.getCounter());

        return Optional.of(view);
    }

    /** Whether a reader may see it: {@link ArticleRepository#PUBLIC}, as every public query requires. */
    public static boolean isPublic(Article article) {
        return article != null && article.getStatus() == ArticleRepository.PUBLIC;
    }

    public ArticleList getAllArticlesByCategory (Integer categoryId, String lang) {

        log.debug("getAllArticlesByCategory (categoryId: {})", categoryId);

        List<Article> articles;
        Optional<Category> category;

        if (MOST_READ_ID.equals(categoryId)) {
            log.debug("get most read articles ");
            articles = articleService.getMostReadArticles();
            // Synthetic pseudo-category: skip the lookup that could never match.
            category = Optional.empty();
        } else {
            articles = articleRepository.findArticleByCategoriesAndStatus(categoryId, ArticleRepository.PUBLIC);
            category = categoryRepository.findById(categoryId);
        }

        ArticleList categoryList = category
                .map(c -> ArticleList.builder()
                        .id(categoryId)
                        .name(getLocalizedText(c.getNames(), c.getName(), lang))
                        .build())
                .orElseGet(() -> ArticleList.builder().id(categoryId).name("NOCATEGORY").build());

        List<ListItem> list = convertArticleListToArticleWebList(articles, lang);


        categoryList.setArticles(list);


        return categoryList;
    }

    public ArticleListPageable getAllArticlesByCategoryPageable (Integer categoryId, Pageable pageable, String lang) {

        Page<Article> page;
        String catName = "";

        if (MOST_READ_ID.equals(categoryId)) {
            log.debug("get most read articles ");
            List<Article> articles = articleService.getMostReadArticles();

            // A synthetic, non-paginated list, so its size is the total: any fixed number would
            // make the pager report a wrong element count.
            page = new PageImpl<>(articles, pageable, articles.size());
        } else {
            page = articleRepository.findArticleByCategoriesAndStatus(categoryId, ArticleRepository.PUBLIC, pageable);

            // Only look the category up when it is a real one; MOST_READ_ID is synthetic, and
            // the lookup would run (and miss) on every most-read request.
            catName = categoryRepository.findById(categoryId)
                    .map(c -> getLocalizedText(c.getNames(), c.getName(), lang))
                    .orElse("");
        }

        ArticleListPageable categoryListPageable =
                ArticleListPageable.builder().id(categoryId).name(catName).build();

        categoryListPageable.setPage(convertArticlePageToListItemPage(page, lang));

        return categoryListPageable;
    }

    public List<ListItem> getAllArticlesByAuthor (Integer authorId, String lang) {
        List<Article> articles = articleRepository.findArticleByAuthorsAndStatus(authorId, ArticleRepository.PUBLIC);

        List<ListItem> list = new ArrayList<>(articles.size());

        for (Article a : articles ) {
            if(a != null) {
                list.add(new ListItem(a, lang));
            }
        }


        return list;
    }

    public ArticleListPageable getAllArticlesByAuthorPageable (Integer authorId, Pageable pageable, String lang) {

        Optional<Author> author = authorRepository.findById(authorId);

        String fullname;

        if(author.isPresent()) {
            String firstName = getLocalizedText(author.get().getFirstnames(), author.get().getFirstname(), lang);
            String lastName = getLocalizedText(author.get().getLastnames(), author.get().getLastname(), lang);
            fullname = (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "");
            fullname = fullname.trim();
        } else {
            fullname = "";
        }

        Page<Article> page;

        page = articleRepository.findArticleByAuthorsAndStatus(authorId, ArticleRepository.PUBLIC, pageable);


        Page<ListItem> convertedPage = convertArticlePageToListItemPage(page, lang);


        ArticleListPageable articleListPageable = null;
        articleListPageable = ArticleListPageable.builder().id(authorId).name(fullname).build();

        articleListPageable.setPage(convertedPage);

        return articleListPageable;
    }

    public ArticleListPageable getAllArticlesByTagPageable (String tag, Pageable pageable, String lang) {

        Page<Article> page;

        page = articleRepository.findArticleByTagsAndStatus(tag, ArticleRepository.PUBLIC, pageable);


        Page<ListItem> convertedPage = convertArticlePageToListItemPage(page, lang);


        ArticleListPageable articleTagListPageable = null;
        articleTagListPageable = ArticleListPageable.builder().name(tag).build();

        articleTagListPageable.setPage(convertedPage);

        return articleTagListPageable;
    }

    private String getLocalizedText(List<TextItem> items, String defaultText, String lang) {
        if (items != null && lang != null) {
            for (TextItem item : items) {
                if (lang.equalsIgnoreCase(item.getLang())) {
                    return item.getText();
                }
            }
        }
        // Fallback to English if not found
        if (items != null && !"en".equalsIgnoreCase(lang)) {
             for (TextItem item : items) {
                if ("en".equalsIgnoreCase(item.getLang())) {
                    return item.getText();
                }
            }
        }
        
        return defaultText;
    }
}
