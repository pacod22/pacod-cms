package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.PageService;
import io.pacod.cms.site.services.SiteMetricCollector;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * The standing pages' routing contract, and in particular which language a request asks for.
 *
 * <p>The two are one subject here: {@code /p/{slug}} answers at {@code /{lang}/p/{slug}} too, and
 * what the prefix feeds is {@code PageService.render}'s <em>requested</em> language -- not the one
 * rendered, which is whatever the page's texts actually resolve to. Asserting on the model
 * attribute alone would pass while the prefix was being dropped on the floor, because a page whose
 * text is Bulgarian renders in Bulgarian whatever anyone asked for.
 *
 * <p>Standalone MockMvc, for the reason {@code ArticleTemplateControllerTest} gives: the
 * {@code @WebMvcTest} slice bootstraps {@code WebApplication} and drags in the whole repository
 * layer.
 */
class PageControllerTest {

    private PageService pageService;
    private BannerService bannerService;
    private SiteMetricCollector metrics;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pageService = mock(PageService.class);
        bannerService = mock(BannerService.class);
        metrics = mock(SiteMetricCollector.class);

        when(pageService.languages()).thenReturn(List.of());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new PageController(pageService, composer(), bannerService, metrics))
                .build();
    }

    /** Serves whatever was asked for, so an assertion on the served language is not vacuous. */
    private void pageExists(String slug, String servedLang) {
        Page page = new Page(slug);
        when(pageService.bySlug(slug)).thenReturn(Optional.of(page));
        when(pageService.render(any(), any())).thenAnswer(invocation -> new PageService.Rendered(
                page, servedLang, false, false, null, "Title", "<p>Body</p>", "Desc"));
    }

    @Test
    @DisplayName("GET /p/{slug} renders the page")
    void canonicalRouteRenders() throws Exception {
        pageExists("about", "bg");

        mockMvc.perform(get("/p/about"))
                .andExpect(status().isOk())
                .andExpect(view().name("page"))
                .andExpect(model().attributeExists("page"));
    }

    @Test
    @DisplayName("GET /{lang}/p/{slug} asks PageService for that language")
    void languagePrefixIsTheRequestedLanguage() throws Exception {
        pageExists("about", "en");

        mockMvc.perform(get("/de/p/about"))
                .andExpect(status().isOk())
                .andExpect(view().name("page"))
                // The language SERVED, which is the fallback -- not the one the path named.
                .andExpect(model().attribute("lang", "en"))
                .andExpect(model().attribute("requestedLang", "de"));

        verify(pageService).render(any(), eq("de"));
    }

    @Test
    @DisplayName("The path language beats both ?lang and the cookie")
    void pathLanguageBeatsQueryAndCookie() throws Exception {
        pageExists("about", "bg");

        mockMvc.perform(get("/de/p/about").param("lang", "fr").cookie(new Cookie("lang", "it")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("requestedLang", "de"));

        verify(pageService).render(any(), eq("de"));
    }

    /**
     * The code is not checked against the {@code language} collection -- what that collection
     * governs is what the studio offers and what the switcher lists, never whether a body
     * resolves. A 404 here would be a reader path depending on a row existing.
     */
    @Test
    @DisplayName("An unlisted language code renders rather than 404s")
    void unlistedLanguageCodeStillRenders() throws Exception {
        pageExists("about", "bg");

        mockMvc.perform(get("/xx/p/about"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("requestedLang", "xx"));
    }

    /** Pins the two-or-three-letter constraint that keeps the prefix off other routes. */
    @Test
    @DisplayName("A prefix longer than three letters is not a language prefix")
    void longerFirstSegmentIsNotALanguagePrefix() throws Exception {
        mockMvc.perform(get("/news/p/about"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("The prefixed route carries the advertising and the traffic mark too")
    void prefixedRouteIsCountedAndAdvertised() throws Exception {
        pageExists("about", "bg");

        mockMvc.perform(get("/bg/p/about"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("topBanners"))
                .andExpect(model().attributeExists("sidebarBanners"));

        verify(metrics, times(1)).recordPageView(eq(BannerPage.PAGE), any());
    }

    @Test
    @DisplayName("A page is filed under the language it was served in, not the one asked for")
    void headNamesTheServedLanguage() throws Exception {
        pageExists("about", "en");
        io.pacod.cms.site.data.HeadMeta english = (io.pacod.cms.site.data.HeadMeta) mockMvc.perform(get("/de/p/about"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("head");
        assertThat(english.canonical()).isEqualTo("/en/p/about");

        pageExists("about", "bg");
        io.pacod.cms.site.data.HeadMeta original = (io.pacod.cms.site.data.HeadMeta) mockMvc.perform(get("/p/about").param("lang", "de"))
                .andReturn().getModelAndView().getModel().get("head");
        assertThat(original.canonical()).isEqualTo("/p/about");
        assertThat(original.isArticle()).isFalse();
    }

    private static io.pacod.cms.site.render.PageComposer composer() {
        io.pacod.cms.site.render.PageComposer composer = org.mockito.Mockito.mock(io.pacod.cms.site.render.PageComposer.class);
        org.mockito.Mockito.when(composer.compose(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(io.pacod.cms.site.render.Regions.primaryOnly());
        return composer;
    }
}
