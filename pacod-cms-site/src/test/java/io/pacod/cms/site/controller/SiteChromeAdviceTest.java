package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.site.data.SiteChrome;
import io.pacod.cms.site.render.SitePreview;
import io.pacod.cms.site.render.SitePrint;
import io.pacod.cms.site.render.ThemeReader;
import io.pacod.cms.site.services.LanguageReader;
import io.pacod.cms.site.services.PageReader;
import io.pacod.cms.site.services.PageService;
import io.pacod.cms.site.services.SiteChromeReader;
import io.pacod.cms.site.testing.TestSites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The chrome {@link SiteChromeAdvice} puts in every page's model, as far as the consent banner
 * goes: which standing page it links, and that a preview or a print render has none to show.
 */
class SiteChromeAdviceTest {

    private final SiteChromeReader reader = mock(SiteChromeReader.class);
    private final PageReader pages = mock(PageReader.class);

    private SiteChrome site(SiteSettings settings, MockHttpServletRequest request) {
        when(reader.settings()).thenReturn(settings);
        when(reader.settingsNow()).thenReturn(settings);
        when(reader.menus()).thenReturn(Map.of());
        when(reader.menusNow()).thenReturn(Map.of());
        return new SiteChromeAdvice(reader, mock(LanguageReader.class), mock(ThemeReader.class),
                new PageService(pages, mock(LanguageReader.class))).site(request);
    }

    private SiteChrome site(List<String> published) {
        when(pages.findAll()).thenReturn(published.stream().map(Page::new).toList());
        return site(TestSites.settings(), new MockHttpServletRequest("GET", "/"));
    }

    @Test
    @DisplayName("the banner links the cookie policy, else the privacy policy, else nothing")
    void linksThePolicyPublished() {
        assertThat(site(List.of("about", "privacy", "cookies")).cookiePolicyHref("en")).isEqualTo("/p/cookies?lang=en");
        assertThat(site(List.of("about", "privacy")).cookiePolicyHref("de")).isEqualTo("/p/privacy?lang=de");
        assertThat(site(List.of("about")).cookiePolicyHref("en")).isNull();
        // The reader's page has the container either way.
        assertThat(site(List.of("about")).gtmId()).isEqualTo(TestSites.GTM_ID);
    }

    @Test
    @DisplayName("a website without a container is not asked about its pages")
    void noContainerNoLookup() {
        SiteSettings settings = TestSites.settings();
        settings.setGtmId(null);

        SiteChrome chrome = site(settings, new MockHttpServletRequest("GET", "/"));

        assertThat(chrome.gtmId()).isNull();
        assertThat(chrome.cookiePolicyHref("en")).isNull();
        verify(pages, never()).findAll();
    }

    @Test
    @DisplayName("a preview and a print render have no container, so no banner and no link")
    void previewAndPrintHaveNoBanner() {
        when(pages.findAll()).thenReturn(List.of(new Page("cookies")));

        MockHttpServletRequest preview = new MockHttpServletRequest("GET", "/");
        preview.setAttribute(SitePreview.ATTRIBUTE, new SitePreview.Preview("token", null));
        MockHttpServletRequest print = new MockHttpServletRequest("GET", "/article-print/7");
        print.setAttribute(SitePrint.ATTRIBUTE, Boolean.TRUE);

        for (MockHttpServletRequest request : List.of(preview, print)) {
            SiteChrome chrome = site(TestSites.settings(), request);
            assertThat(chrome.gtmId()).as(request.getRequestURI()).isNull();
            assertThat(chrome.cookiePolicyHref("en")).as(request.getRequestURI()).isNull();
        }
    }

    @Test
    @DisplayName("pages that cannot be read cost the banner its link, never the page its chrome")
    void unreadablePagesCostOnlyTheLink() {
        when(pages.findAll()).thenThrow(new IllegalStateException("down"));

        SiteChrome chrome = site(TestSites.settings(), new MockHttpServletRequest("GET", "/"));

        assertThat(chrome.gtmId()).isEqualTo(TestSites.GTM_ID);
        assertThat(chrome.name("en")).isEqualTo(TestSites.NAME_EN);
        assertThat(chrome.cookiePolicyHref("en")).isNull();
    }
}
