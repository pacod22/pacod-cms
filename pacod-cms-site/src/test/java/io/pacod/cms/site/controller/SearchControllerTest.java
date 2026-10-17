package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.WebArticleService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The contract of the two search screens.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest}, for the reason
 * {@link ArticleTemplateControllerTest} gives: the slice bootstraps {@code WebApplication} and
 * drags in the whole repository layer. The templates themselves are rendered for real in
 * {@code PublicTemplateRenderTest}.
 */
class SearchControllerTest {

    private WebArticleService articleService;
    private BannerService bannerService;
    private SiteMetricCollector metrics;
    private MockMvc mockMvc;
    private io.pacod.cms.site.services.SiteChromeReader siteChrome;

    @BeforeEach
    void setUp() {
        articleService = mock(WebArticleService.class);
        bannerService = mock(BannerService.class);
        metrics = mock(SiteMetricCollector.class);

        when(bannerService.forPage(any(), any())).thenReturn(List.of());
        when(articleService.searchArticlesPageable(anyString(), any(), anyString()))
                .thenReturn(emptyPage());

        siteChrome = mock(io.pacod.cms.site.services.SiteChromeReader.class);
        when(siteChrome.settings()).thenReturn(io.pacod.cms.site.testing.TestSites.settings());
        when(siteChrome.menus()).thenReturn(io.pacod.cms.site.testing.TestSites.menus());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(articleService, composer(), bannerService, metrics))
                // The advice puts ${site} in the model, as it does for every page in production;
                // the Google screen reads its engine id from it.
                .setControllerAdvice(new SiteChromeAdvice(siteChrome, mock(io.pacod.cms.site.services.LanguageReader.class),
                        mock(io.pacod.cms.site.render.ThemeReader.class)))
                // Required here and nowhere else in these tests, and not decoration: standalone
                // setup resolves a view name to a forward of the same string, so view "search" at
                // request path "/search" is refused as a circular dispatch before any assertion
                // runs. ArticleTemplateControllerTest never hits it because its view names
                // ("landing", "article") differ from its paths. A prefix and suffix make the
                // forward URL a different string; nothing is ever rendered at this level.
                .setViewResolvers(searchSafeViewResolver())
                .build();
    }

    private static InternalResourceViewResolver searchSafeViewResolver() {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver();
        resolver.setPrefix("/WEB-INF/templates/");
        resolver.setSuffix(".html");
        return resolver;
    }

    private static Page<ListItem> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    }

    @Test
    @DisplayName("Both canonical routes render their own view")
    void canonicalRoutesRender() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"));

        mockMvc.perform(get("/search-google"))
                .andExpect(status().isOk())
                .andExpect(view().name("search-google"));
    }

    @Test
    @DisplayName("There are no .html aliases of the search screens")
    void noHtmlAliases() throws Exception {
        mockMvc.perform(get("/search.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/search-google.html")).andExpect(status().isNotFound());
    }

    /**
     * A website that has not configured a Programmable Search engine has no Google screen: its
     * only content would be a script that cannot load. The reader is handed to the site search
     * instead, with what they typed and the language they were reading in.
     */
    @Test
    @DisplayName("Without a Google engine the Google screen redirects to the site search")
    void googleScreenWithoutAnEngineRedirects() throws Exception {
        io.pacod.cms.core.data.SiteSettings settings = io.pacod.cms.site.testing.TestSites.settings();
        settings.setGoogleCseCx(null);
        when(siteChrome.settings()).thenReturn(settings);

        mockMvc.perform(get("/search-google").param("q", "solar panels").param("lang", "en"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search?q=solar%20panels&lang=en"));
        verify(metrics, org.mockito.Mockito.never()).recordPageView(any(), any());
    }

    /**
     * A reader who has not typed anything must not be told nothing matched.
     *
     * <p>{@code searched} is what the template branches on, and the service must not be called at
     * all -- a blank query through {@code fulltextSearch} is a regex matching every article, so
     * this is a wasted query as well as a wrong answer.
     */
    @Test
    @DisplayName("No query searches nothing and says so, rather than reporting no matches")
    void blankQueryDoesNotSearch() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attribute("searched", false))
                .andExpect(model().attributeDoesNotExist("results"));

        mockMvc.perform(get("/search").param("q", "   "))
                .andExpect(model().attribute("searched", false))
                .andExpect(model().attribute("q", ""))
                .andExpect(model().attributeDoesNotExist("results"));

        verify(articleService, never()).searchArticlesPageable(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("A query is trimmed, searched and handed to the view")
    void queryIsSearched() throws Exception {
        mockMvc.perform(get("/search").param("q", "  фестивал  "))
                .andExpect(status().isOk())
                .andExpect(model().attribute("q", "фестивал"))
                .andExpect(model().attribute("searched", true))
                .andExpect(model().attributeExists("results"));

        verify(articleService)
                .searchArticlesPageable(eq("фестивал"), any(Pageable.class), anyString());
    }

    /**
     * {@code page} reaches the service, because the pager builds links from it and a search that
     * always returned page one would page through nothing while looking like it worked.
     */
    @Test
    @DisplayName("The requested page number reaches the query, and a negative one is floored")
    void pageNumberIsHonoured() throws Exception {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/search").param("q", "фестивал").param("page", "3"));
        verify(articleService).searchArticlesPageable(anyString(), pageable.capture(), anyString());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);

        // A hand-edited URL must not throw IllegalArgumentException out of PageRequest.of.
        mockMvc.perform(get("/search").param("q", "фестивал").param("page", "-2"))
                .andExpect(status().isOk());
    }

    /**
     * The Google screen takes the term too.
     *
     * <p>Its button in the navbar submits the search box: as an {@code <a href>} it would carry
     * nothing, and a reader who typed a word and clicked G would lose it. Nothing in Java reads
     * {@code q} here -- Google's script takes it off the URL, where its {@code queryParameterName}
     * defaults to {@code q} -- but the model attribute is what the {@code <noscript>} fallback
     * link is built from, and asserting it is the only part of that mechanism this side of the
     * browser can hold.
     */
    @Test
    @DisplayName("The Google screen accepts and trims a query")
    void googleScreenTakesTheQuery() throws Exception {
        mockMvc.perform(get("/search-google").param("q", "  фестивал  "))
                .andExpect(status().isOk())
                .andExpect(model().attribute("q", "фестивал"));

        mockMvc.perform(get("/search-google"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("q", ""));
    }

    /** Both screens carry advertising and both mark the traffic buckets, under their own page. */
    @Test
    @DisplayName("Both screens book advertising and record a view under SEARCH")
    void bothScreensAdvertiseAndCount() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attributeExists("topBanners"))
                .andExpect(model().attributeExists("sidebarBanners"));
        mockMvc.perform(get("/search-google"))
                .andExpect(model().attributeExists("topBanners"))
                .andExpect(model().attributeExists("sidebarBanners"));

        // Not LIST and not PAGE: folding search into either would put it in that page type's
        // column on the monitoring screen with nothing showing it had happened.
        verify(bannerService, org.mockito.Mockito.times(2))
                .forPage(BannerPlacement.TOP, BannerPage.SEARCH);
        verify(bannerService, org.mockito.Mockito.times(2))
                .forPage(BannerPlacement.SIDEBAR, BannerPage.SEARCH);
        verify(metrics, org.mockito.Mockito.times(2))
                .recordPageView(eq(BannerPage.SEARCH), any());
    }

    /**
     * {@code ?lang} beats the cookie, and neither being present ends at the language the site is
     * written in rather than at the resolver's English.
     */
    @Test
    @DisplayName("Language is ?lang, then the cookie, then Bulgarian")
    void languagePrecedence() throws Exception {
        mockMvc.perform(get("/search").param("lang", "en").cookie(new Cookie("lang", "ru")))
                .andExpect(model().attribute("lang", "en"));

        mockMvc.perform(get("/search").cookie(new Cookie("lang", "ru")))
                .andExpect(model().attribute("lang", "ru"));

        mockMvc.perform(get("/search"))
                .andExpect(model().attribute("lang", "bg"));

        // CookieLocaleResolver serialises a whole Locale, so the cookie can hold a region.
        // Everything downstream compares bare codes exactly.
        mockMvc.perform(get("/search").cookie(new Cookie("lang", "en-US")))
                .andExpect(model().attribute("lang", "en"));
    }

    private static io.pacod.cms.site.render.PageComposer composer() {
        io.pacod.cms.site.render.PageComposer composer = org.mockito.Mockito.mock(io.pacod.cms.site.render.PageComposer.class);
        org.mockito.Mockito.when(composer.compose(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(io.pacod.cms.site.render.Regions.primaryOnly());
        return composer;
    }
}
