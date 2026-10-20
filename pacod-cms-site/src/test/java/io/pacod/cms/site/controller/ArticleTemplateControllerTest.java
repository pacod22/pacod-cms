package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.services.TagService;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.PrintExtras;
import io.pacod.cms.site.services.SitePdfs;
import io.pacod.cms.core.repository.PdfEnrichmentRepository;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.site.render.PageComposer;
import io.pacod.cms.site.render.Regions;
import io.pacod.cms.site.services.WebArticleService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Asserts that each view receives the model attributes its template dereferences.
 *
 * <p>A template that dereferences an attribute the controller never adds fails only when it
 * renders: index.html reads {@code ${article.title}}, and without an {@code article} in the model
 * the latest-article page ({@code /latest}) is an HTTP 500. A test at this level catches that
 * immediately.
 *
 * <p>Uses a standalone MockMvc rather than {@code @WebMvcTest}: the slice bootstraps
 * {@code WebApplication}, whose {@code @ComponentScan} and {@code @EnableMongoRepositories}
 * pull in the whole repository layer that a web-only slice does not configure. Standalone
 * setup tests exactly the controller contract with no context to fight.
 */
class ArticleTemplateControllerTest {

    private WebArticleService articleService;
    private TagService tagService;
    private PageComposer composer;
    private BannerService bannerService;
    private SiteMetricCollector metrics;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        articleService = mock(WebArticleService.class);
        tagService = mock(TagService.class);
        composer = mock(PageComposer.class);
        when(composer.compose(any(), any())).thenReturn(Regions.primaryOnly());
        // Returns an empty list by default, which is all this level needs: the advertising is
        // asserted where it is actually rendered, in PublicTemplateRenderTest.
        bannerService = mock(BannerService.class);
        // The traffic collector buffers in memory and is asserted in SiteMetricCollectorTest;
        // here it only has to exist, so recording a view is not what fails a routing assertion.
        metrics = mock(SiteMetricCollector.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ArticleTemplateController(
                        articleService, tagService, composer, bannerService, metrics, new SitePdfs(""),
                        new PrintExtras(mock(PdfEnrichmentRepository.class))))
                // /list/{id} takes a @PageableDefault Pageable.
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private static Article article(int id) {
        Article article = new Article(id);
        article.setTitle("Title " + id);
        article.setCounter(0);
        return article;
    }

    @Test
    @DisplayName("GET / renders the landing view with its model")
    void landingRendersLandingView() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attributeExists("regions"))
                .andExpect(model().attributeExists("lang"));
    }

    @Test
    @DisplayName("GET / does not fail if the landing model cannot be built")
    void landingSurvivesNullFromService() throws Exception {
        // build() is left unstubbed, so Mockito returns null. The handler must not dereference it.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"));
    }

    @Test
    @DisplayName("GET / defaults to Bulgarian when nothing else says otherwise")
    void landingDefaultsToBulgarian() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lang", "bg"));

        // LocaleConfig's resolver defaults to English; the home page must not inherit that.
        ArgumentCaptor<String> lang = ArgumentCaptor.forClass(String.class);
        verify(composer).compose(eq(PageType.HOME), lang.capture());
        assertThat(lang.getValue()).isEqualTo("bg");
    }

    @Test
    @DisplayName("GET / prefers ?lang over the cookie")
    void landingPrefersRequestParamOverCookie() throws Exception {
        mockMvc.perform(get("/").param("lang", "en").cookie(new Cookie("lang", "bg")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lang", "en"));

        verify(composer).compose(PageType.HOME, "en");
    }

    @Test
    @DisplayName("GET / reduces a region-qualified language to its bare subtag")
    void landingNormalizesRegionQualifiedLang() throws Exception {
        // CookieLocaleResolver serializes a Locale, so the cookie can carry a region. Everything
        // downstream compares against bare codes like "en", and would silently miss.
        mockMvc.perform(get("/").cookie(new Cookie("lang", "en-US")))
                .andExpect(model().attribute("lang", "en"));
        mockMvc.perform(get("/").cookie(new Cookie("lang", "en_US")))
                .andExpect(model().attribute("lang", "en"));

        verify(composer, times(2)).compose(PageType.HOME, "en");
    }

    @Test
    @DisplayName("GET /latest supplies the 'article' attribute index.html requires")
    void latestPageSuppliesArticle() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getLatestArticle()).thenReturn(Optional.of(article(2)));

        mockMvc.perform(get("/latest"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                // Without it index.html is an HTTP 500.
                .andExpect(model().attributeExists("article"))
                .andExpect(model().attributeExists("lang"))
                .andExpect(model().attributeExists("tags"));
    }

    @Test
    @DisplayName("GET /index.html still reaches the controller, not the static resource handler")
    void indexHtmlStillResolvesToTheLatestArticlePage() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getLatestArticle()).thenReturn(Optional.of(article(2)));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @DisplayName("GET /latest still supplies an article when the database is empty")
    void latestPageSurvivesEmptyDatabase() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getLatestArticle()).thenReturn(Optional.empty());

        mockMvc.perform(get("/latest"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("article"));
    }

    @Test
    @DisplayName("GET /latest survives the tag lookup failing")
    void latestPageSurvivesTagFailure() throws Exception {
        when(tagService.getAllTags()).thenThrow(new RuntimeException("mongo down"));
        when(articleService.getLatestArticle()).thenReturn(Optional.of(article(2)));

        mockMvc.perform(get("/latest"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("tags"));
    }

    @Test
    @DisplayName("GET /article/{id} supplies article and lang")
    void articlePageSuppliesModel() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/article/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("article"))
                .andExpect(model().attributeExists("article"))
                .andExpect(model().attributeExists("lang"));
    }

    /**
     * The bare route normalises its {@code ?lang} too, and always has to: everything downstream
     * compares bare codes exactly -- {@code ListItem.getLocalizedText} against {@code TextItem.lang}
     * values like {@code "en"} -- so {@code en-US} would miss every translation and fall back to
     * the defaults with nothing reporting it.
     */
    @Test
    @DisplayName("A region-qualified ?lang is reduced to a bare subtag on the bare route")
    void regionQualifiedLanguageIsNormalised() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/article/1").param("lang", "en-US"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lang", "en"));
    }

    /**
     * {@code ?lang=} must not reach the templates as an empty string, which is a language nothing
     * resolves to. It falls through to the locale the request resolved to, the same way an absent
     * parameter does.
     */
    @Test
    @DisplayName("A blank ?lang falls through to the resolved locale, not to an empty string")
    void blankLanguageFallsThroughToTheLocale() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/article/1").param("lang", "").locale(java.util.Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lang", "en"));
    }

    @Test
    @DisplayName("GET /{lang}/article/{id} renders the same page in the language the path names")
    void languagePrefixedArticleRenders() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/bg/article/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("article"))
                .andExpect(model().attributeExists("article"))
                .andExpect(model().attribute("lang", "bg"));
    }

    @Test
    @DisplayName("The path language wins over a ?lang that disagrees with it")
    void pathLanguageBeatsQueryParameter() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/de/article/1").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lang", "de"));
    }

    /**
     * A code the {@code language} collection does not list still renders, exactly as
     * {@code /article/1?lang=xx} does: the fields fall back through {@code LanguageChain}.
     * Refusing it here would make a reader path depend on a database row existing.
     */
    @Test
    @DisplayName("An unlisted language code renders rather than 404s")
    void unlistedLanguageCodeStillRenders() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/xx/article/1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("lang", "xx"));
    }

    /**
     * Pins the two-or-three-letter constraint. Without it the pattern would also match
     * {@code /article-print/{id}} and {@code /test/{id}/{slug}}, whose first segment is longer --
     * and it would match them as an article, silently, since the view name is chosen here.
     */
    @Test
    @DisplayName("A prefix longer than three letters is not a language prefix")
    void longerFirstSegmentIsNotALanguagePrefix() throws Exception {
        mockMvc.perform(get("/news/article/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("The prefixed route carries the advertising and the traffic mark too")
    void languagePrefixedArticleIsCountedAndAdvertised() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/en/article/1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("topBanners"))
                .andExpect(model().attributeExists("sidebarBanners"));

        verify(metrics, times(1)).recordPageView(eq(BannerPage.ARTICLE), any());
    }

    @Test
    @DisplayName("GET /article-print/{id} supplies article and lang")
    void articleCleanSuppliesModel() throws Exception {
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/article-print/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("article-print"))
                .andExpect(model().attributeExists("article"))
                .andExpect(model().attributeExists("printMeta"));
    }

    @Test
    @DisplayName("GET /article-print/{id}?format= picks the template by enum; anything unknown is standard")
    void printFormatPicksTheTemplate() throws Exception {
        when(articleService.getArticleById(1)).thenReturn(Optional.of(article(1)));

        mockMvc.perform(get("/article-print/1").param("format", "scientific").param("mode", "llm"))
                .andExpect(status().isOk())
                .andExpect(view().name("article-print-scientific"));
        mockMvc.perform(get("/article-print/1").param("format", "../error"))
                .andExpect(status().isOk())
                .andExpect(view().name("article-print"));
    }

    @Test
    @DisplayName("GET /list/{id} resolves a numeric id as a category")
    void listResolvesNumericIdAsCategory() throws Exception {
        when(articleService.getAllArticlesByCategoryPageable(any(), any(), anyString()))
                .thenReturn(ArticleListPageable.builder().id(5).name("cat").build());

        mockMvc.perform(get("/list/5"))
                .andExpect(status().isOk())
                .andExpect(view().name("list"))
                .andExpect(model().attributeExists("list"));
    }

    @Test
    @DisplayName("GET /{lang}/list/{id} lists in the language the path names")
    void languagePrefixedListRenders() throws Exception {
        when(articleService.getAllArticlesByCategoryPageable(any(), any(), anyString()))
                .thenReturn(ArticleListPageable.builder().id(5).name("cat").build());

        mockMvc.perform(get("/bg/list/5"))
                .andExpect(status().isOk())
                .andExpect(view().name("list"))
                .andExpect(model().attributeExists("list"))
                .andExpect(model().attribute("lang", "bg"));

        // The language reaches the query, not only the model: the list's titles are localised
        // there, so a prefix that stopped at the template would translate the chrome and nothing
        // else -- and would look right on a Bulgarian page.
        verify(articleService).getAllArticlesByCategoryPageable(any(), any(), eq("bg"));
    }

    @Test
    @DisplayName("The prefix does not swallow the tag form of /list/{id}")
    void languagePrefixedListStillResolvesTags() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getAllArticlesByTagPageable(anyString(), any(), anyString()))
                .thenReturn(ArticleListPageable.builder().name("alpha").build());

        mockMvc.perform(get("/en/list/alpha"))
                .andExpect(status().isOk())
                .andExpect(view().name("list"));

        verify(articleService).getAllArticlesByTagPageable(eq("alpha"), any(), eq("en"));
    }

    @Test
    @DisplayName("A prefix longer than three letters is not a language prefix on /list either")
    void longerFirstSegmentIsNotALanguagePrefixOnList() throws Exception {
        mockMvc.perform(get("/news/list/5"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /list/{id} falls back to a tag lookup for a non-numeric id")
    void listFallsBackToTag() throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of());
        when(articleService.getAllArticlesByTagPageable(anyString(), any(), anyString()))
                .thenReturn(ArticleListPageable.builder().name("alpha").build());

        mockMvc.perform(get("/list/alpha"))
                .andExpect(status().isOk())
                .andExpect(view().name("list"))
                .andExpect(model().attributeExists("list"));
    }
}
