package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.site.services.WebArticleService;
import io.pacod.cms.site.data.ArticleList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/web/v1")
public class ArticleWebController {

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private WebArticleService articleService;



    @RequestMapping(value = "/article/{id}", method = RequestMethod.GET)
    public ResponseEntity<Article> findById(@PathVariable Integer id, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);



        return articleService.getArticleById(id)
                .map(record -> ResponseEntity.ok().body(record))
                .orElse(ResponseEntity.notFound().build());
    }

    @RequestMapping(value = "/article/paging/", method = RequestMethod.GET)
    public Page<ListItem> findAllPageable(@PageableDefault(page = 0, size = 5)
                                     @SortDefault.SortDefaults({
                                             @SortDefault(sort = "id", direction = Sort.Direction.DESC)
                                     }) Pageable pageable, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);
        return articleService.getAllArticlesPageable(pageable, lang);
    }

    /**
     * Paginated: every article in a single response -- full bodies, all localized text arrays and
     * each one's eager @DBRef graphs -- would be unbounded.
     *
     * <p>Returns a page object rather than a bare array; callers expecting an array should
     * read {@code .content}, as with {@code /article/paging/}, which has the same shape.
     */
    @RequestMapping(value = "/article", method = RequestMethod.GET)
    public Page<Article> findAll(@PageableDefault(page = 0, size = 20)
                                 @SortDefault.SortDefaults({
                                         @SortDefault(sort = "id", direction = Sort.Direction.DESC)
                                 }) Pageable pageable){
        return articleService.getAllArticlesPaged(pageable);
    }

    @RequestMapping(value = "/bycategory/{category}", method = RequestMethod.GET)
    public ArticleList findByCategory(@PathVariable Integer category, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);
        return articleService.getAllArticlesByCategory(category, lang);
    }

    @RequestMapping(value = "/bycategory/paging/{category}", method = RequestMethod.GET)
    public ArticleListPageable findByCategoryPageable(@PathVariable Integer category, @PageableDefault(page = 0, size = 5)
    @SortDefault.SortDefaults({
            @SortDefault(sort = "id", direction = Sort.Direction.DESC)
    }) Pageable pageable, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);
        return articleService.getAllArticlesByCategoryPageable(category, pageable, lang);
    }

    @RequestMapping(value = "/byauthor/{author}", method = RequestMethod.GET)
    public List<ListItem> findByAuthor(@PathVariable Integer author, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);
        return articleService.getAllArticlesByAuthor(author, lang);
    }

    @RequestMapping(value = "/byauthor/paging/{author}", method = RequestMethod.GET)
    public ArticleListPageable findByAuthorPageable(@PathVariable Integer author, @PageableDefault(page = 0, size = 5)
    @SortDefault.SortDefaults({
            @SortDefault(sort = "id", direction = Sort.Direction.DESC)
    }) Pageable pageable, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);
        return articleService.getAllArticlesByAuthorPageable(author, pageable, lang);
    }

    @RequestMapping(value = "/search/{text}", method = RequestMethod.GET)
    public Page<ListItem> searchArticles(@PathVariable String text, @PageableDefault(page = 0, size = 5)
                                          @SortDefault.SortDefaults({
                                                  @SortDefault(sort = "id", direction = Sort.Direction.DESC)
                                          }) Pageable pageable, @RequestParam(required = false) String lang){
        lang = orOriginal(lang);
        return articleService.searchArticlesPageable(text, pageable, lang);
    }



    /**
     * The language asked for, or the website's own when none was: resolved per request, because
     * the website -- and with it its language -- is only known once the host has been looked up.
     * A placeholder default would name the platform's language for every website.
     */
    private static String orOriginal(String lang) {
        return lang == null || lang.isBlank() ? io.pacod.cms.core.data.LanguageChain.original() : lang;
    }
}
