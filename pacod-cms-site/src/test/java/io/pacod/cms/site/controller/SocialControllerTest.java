package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.site.data.SocialView;
import io.pacod.cms.site.services.LanguageReader;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.SocialPageReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class SocialControllerTest {

    private static final String SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String INSTAGRAM = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 337.0.3.23.54 "
            + "(iPhone14,5; iOS 17_5; bg_BG; bg; scale=3.00; 1170x2532; 615419131; IABMV/1)";

    private SocialPageReader reader;
    private SiteMetricCollector metrics;
    private MockMvc mockMvc;
    private SocialPage page;

    @BeforeEach
    void setUp() {
        reader = mock(SocialPageReader.class);
        metrics = mock(SiteMetricCollector.class);
        LanguageReader languages = mock(LanguageReader.class);
        when(languages.findAll()).thenReturn(List.of());

        page = new SocialPage();
        page.setPublished(true);
        page.putName("bg", "Примерен вестник");
        page.getLinks().add(link("insta001", SocialLinkPlacement.ICON, "https://www.instagram.com/example/"));
        page.getLinks().add(link("button01", SocialLinkPlacement.BUTTON, "/p/subscription"));
        when(reader.page()).thenAnswer(i -> Optional.of(new SocialPageReader.Snapshot(page, null, java.util.Map.of())));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SocialController(reader, languages, metrics, siteChrome()))
                // View "social" at path "/social" would otherwise be refused as a circular
                // dispatch -- the SearchControllerTest arrangement.
                .setViewResolvers(viewResolver())
                .build();
    }

    /** Settings naming the website's host, so its own absolute addresses are recognised. */
    private static io.pacod.cms.site.services.SiteChromeReader siteChrome() {
        io.pacod.cms.site.services.SiteChromeReader chrome = mock(io.pacod.cms.site.services.SiteChromeReader.class);
        io.pacod.cms.core.data.SiteSettings settings = new io.pacod.cms.core.data.SiteSettings();
        settings.setBaseUrl("https://www.example.org");
        when(chrome.settings()).thenReturn(settings);
        return chrome;
    }

    private static InternalResourceViewResolver viewResolver() {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver();
        resolver.setPrefix("/WEB-INF/templates/");
        resolver.setSuffix(".html");
        return resolver;
    }

    private static SocialLink link(String id, SocialLinkPlacement placement, String url) {
        SocialLink link = new SocialLink(id);
        link.setPlacement(placement);
        link.setUrl(url);
        link.setIcon(placement == SocialLinkPlacement.ICON ? SocialIcon.INSTAGRAM : null);
        link.putTitle("bg", "Връзка " + id);
        return link;
    }

    /* ------------------------------------------------------------------- the page */

    @Test
    @DisplayName("the page renders and the view is recorded under the network it came from")
    void rendersAndRecordsTheSource() throws Exception {
        MvcResult result = mockMvc.perform(get("/social").param("src", "ig").header("User-Agent", SAFARI))
                .andExpect(status().isOk())
                .andExpect(view().name("social"))
                .andReturn();

        verify(metrics).recordSocialView(eq(SocialSource.INSTAGRAM), any());
        SocialView view = (SocialView) result.getModelAndView().getModel().get("social");
        assertThat(view.sourceKey()).isEqualTo("instagram");
        assertThat(view.icons()).singleElement()
                .satisfies(t -> assertThat(t.href()).isEqualTo("/social/go/insta001?src=instagram"));
        assertThat(view.buttons()).singleElement()
                // /p/subscription is the site's own page: its link carries the reader's language.
                .satisfies(t -> assertThat(t.href()).isEqualTo("/social/go/button01?src=instagram&lang=bg"));
    }

    @Test
    @DisplayName("a visit from Facebook with no tag and no referrer is recognised by its fbclid")
    void fbclidIsFacebook() throws Exception {
        MvcResult result = mockMvc.perform(get("/social").param("fbclid", "IwZXh0bgNhZW0CMTEAAR2abc")
                        .header("User-Agent", SAFARI))
                .andExpect(status().isOk())
                .andReturn();

        verify(metrics).recordSocialView(eq(SocialSource.FACEBOOK), any());
        // ... and every tile carries it on, so the clicks are Facebook's too.
        SocialView view = (SocialView) result.getModelAndView().getModel().get("social");
        assertThat(view.sourceKey()).isEqualTo("facebook");
    }

    @Test
    @DisplayName("the trailing-slash spelling is the same page")
    void trailingSlashRenders() throws Exception {
        mockMvc.perform(get("/social/").header("User-Agent", INSTAGRAM))
                .andExpect(status().isOk());
        verify(metrics).recordSocialView(eq(SocialSource.INSTAGRAM), any());
    }

    @Test
    @DisplayName("an unpublished or missing page is a 404, and nothing is counted")
    void unpublishedIs404() throws Exception {
        page.setPublished(false);
        mockMvc.perform(get("/social").header("User-Agent", SAFARI)).andExpect(status().isNotFound());

        when(reader.page()).thenReturn(Optional.empty());
        mockMvc.perform(get("/social").header("User-Agent", SAFARI)).andExpect(status().isNotFound());

        verify(metrics, never()).recordSocialView(any(), any());
    }

    @Test
    @DisplayName("a link-preview bot gets the page but is not counted")
    void botsAreNotCounted() throws Exception {
        mockMvc.perform(get("/social").header("User-Agent", "facebookexternalhit/1.1"))
                .andExpect(status().isOk());
        verify(metrics, never()).recordSocialView(any(), any());
    }

    @Test
    @DisplayName("the studio's preview gets the page and counts neither the view nor a tapped link")
    void previewIsNotCounted() throws Exception {
        mockMvc.perform(get("/social").header("User-Agent", SAFARI)
                        .requestAttr(io.pacod.cms.site.services.CrawlerTraffic.UNCOUNTED_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isOk());
        mockMvc.perform(get("/social/go/insta001").param("src", "instagram")
                        .header("User-Agent", SAFARI).header("Sec-Fetch-Site", "same-origin")
                        .requestAttr(io.pacod.cms.site.services.CrawlerTraffic.UNCOUNTED_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isFound());
        verify(metrics, never()).recordSocialView(any(), any());
        verify(metrics, never()).recordSocialClick(anyString(), any());
    }

    @Test
    @DisplayName("a language switch from the page itself is not a second visit")
    void selfReferralIsNotCounted() throws Exception {
        mockMvc.perform(get("/social").param("lang", "en").param("src", "instagram")
                        .header("User-Agent", SAFARI)
                        .header("Referer", "http://localhost/social?src=instagram"))
                .andExpect(status().isOk());
        verify(metrics, never()).recordSocialView(any(), any());
    }

    @Test
    @DisplayName("a switched-off or out-of-window link is not on the page")
    void scheduleIsApplied() throws Exception {
        page.link("button01").orElseThrow().setActiveFrom(LocalDateTime.now().plusDays(1));
        page.link("insta001").orElseThrow().setActive(false);

        MvcResult result = mockMvc.perform(get("/social").header("User-Agent", SAFARI)).andReturn();

        SocialView view = (SocialView) result.getModelAndView().getModel().get("social");
        assertThat(view.icons()).isEmpty();
        assertThat(view.buttons()).isEmpty();
    }

    /* ---------------------------------------------------------------- the redirect */

    @Test
    @DisplayName("a tile redirects to its target and counts the click under the carried source")
    void redirectsAndCounts() throws Exception {
        mockMvc.perform(get("/social/go/insta001").param("src", "instagram")
                        .header("User-Agent", INSTAGRAM).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://www.instagram.com/example/"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(metrics).recordSocialClick("insta001", SocialSource.INSTAGRAM);
    }

    @Test
    @DisplayName("a site-relative target is a site-relative Location")
    void sitePathRedirects() throws Exception {
        mockMvc.perform(get("/social/go/button01").header("User-Agent", SAFARI))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/p/subscription"));
        // No ?src= at all is DIRECT, never a guess from the click request.
        verify(metrics).recordSocialClick("button01", SocialSource.DIRECT);
    }

    @Test
    @DisplayName("an article link opens the article in the reader's language; other links are untouched")
    void articleRedirectCarriesTheLanguage() throws Exception {
        page.getLinks().add(link("article1", SocialLinkPlacement.BUTTON, "/article/3150"));
        page.getLinks().add(link("article2", SocialLinkPlacement.BUTTON, "https://www.example.org/article/17?lang=bg"));
        page.getLinks().add(link("article3", SocialLinkPlacement.BUTTON, "/de/article/17"));

        mockMvc.perform(get("/social/go/article1").param("lang", "en").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/article/3150?lang=en"));
        // An existing ?lang is replaced, not doubled.
        mockMvc.perform(get("/social/go/article2").param("lang", "de").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "https://www.example.org/article/17?lang=de"));
        // A language in the path is the operator's explicit choice, and outranks ?lang anyway.
        mockMvc.perform(get("/social/go/article3").param("lang", "en").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/de/article/17"));
        // Not an article: another site's query string is not ours to touch.
        mockMvc.perform(get("/social/go/insta001").param("lang", "en").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "https://www.instagram.com/example/"));
        // Standing pages and category listings are the site's own content too.
        page.getLinks().add(link("pagelink", SocialLinkPlacement.BUTTON, "/p/about"));
        page.getLinks().add(link("listlink", SocialLinkPlacement.BUTTON, "/list/5"));
        page.getLinks().add(link("statichl", SocialLinkPlacement.BUTTON, "/subscription.html"));
        mockMvc.perform(get("/social/go/pagelink").param("lang", "ru").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/p/about?lang=ru"));
        mockMvc.perform(get("/social/go/listlink").param("lang", "en").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/list/5?lang=en"));
        // A path the website does not route as content is left exactly as written.
        mockMvc.perform(get("/social/go/statichl").param("lang", "tr").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/subscription.html"));
        // A hostile value is no language at all.
        mockMvc.perform(get("/social/go/article1").param("lang", "\"><x>").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/article/3150"));
    }

    @Test
    @DisplayName("a cross-site or bot request is redirected but not counted")
    void crossSiteAndBotsAreNotCounted() throws Exception {
        mockMvc.perform(get("/social/go/insta001").param("src", "instagram")
                        .header("User-Agent", SAFARI).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://www.instagram.com/example/"));
        mockMvc.perform(get("/social/go/insta001").header("User-Agent", "Twitterbot/1.0"))
                .andExpect(status().isFound());

        verify(metrics, never()).recordSocialClick(anyString(), any());
    }

    @Test
    @DisplayName("a stored target that would make this an open redirect is refused")
    void hostileTargetsAreRefused() throws Exception {
        page.link("button01").orElseThrow().setUrl("javascript:alert(1)");
        page.link("insta001").orElseThrow().setUrl("//evil.example/");

        mockMvc.perform(get("/social/go/button01").header("User-Agent", SAFARI))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/social"));
        mockMvc.perform(get("/social/go/insta001").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/social"));

        verify(metrics, never()).recordSocialClick(anyString(), any());
    }

    @Test
    @DisplayName("an unknown, switched-off or expired link lands on /social uncounted")
    void staleLinksLandOnThePage() throws Exception {
        page.link("button01").orElseThrow().setActive(false);
        page.link("insta001").orElseThrow().setActiveTo(LocalDateTime.now().minusMinutes(1));

        for (String id : new String[]{"button01", "insta001", "nosuchid"}) {
            mockMvc.perform(get("/social/go/" + id).header("User-Agent", SAFARI))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/social"));
        }
        verify(metrics, never()).recordSocialClick(anyString(), any());
    }

    @Test
    @DisplayName("with the page unpublished a tile goes to the home page")
    void unpublishedTilesGoHome() throws Exception {
        page.setPublished(false);
        mockMvc.perform(get("/social/go/button01").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/"));
    }

    /* ------------------------------------------------------------ article tiles */

    @Test
    @DisplayName("an article tile counts only an article that is in the block")
    void articleClicksAreCountedForTheBlockOnly() throws Exception {
        page.setShowLatestArticles(true);
        page.setLatestArticlesCount(3);
        Article shown = new Article(3150);
        when(reader.latestArticles(3)).thenReturn(List.of(shown));

        mockMvc.perform(get("/social/read/3150").param("src", "tiktok").param("lang", "de")
                        .header("User-Agent", SAFARI))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/article/3150?lang=de"));
        verify(metrics).recordSocialArticleClick(3150, SocialSource.TIKTOK);

        mockMvc.perform(get("/social/read/17").header("User-Agent", SAFARI))
                .andExpect(header().string("Location", "/article/17"));
        verify(metrics, never()).recordSocialArticleClick(eq(17), any());
    }

    @Test
    @DisplayName("with the block switched off nothing is counted, and a hostile lang is dropped")
    void articleBlockOff() throws Exception {
        mockMvc.perform(get("/social/read/3150").param("lang", "\"><script>")
                        .header("User-Agent", SAFARI))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/article/3150"));
        verify(metrics, never()).recordSocialArticleClick(anyInt(), any());
    }
}
