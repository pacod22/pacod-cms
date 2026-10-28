package io.pacod.cms.site.data;

import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.site.testing.TestSites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The website's identity as the templates read it.
 *
 * <p>The identifiers matter most: each is written into a script or an attribute, so a value of the
 * wrong shape must render as nothing rather than as whatever an operator typed.
 */
class SiteChromeTest {

    @Test
    @DisplayName("names and taglines resolve down the reader's language chain")
    void namesResolvePerLanguage() {
        SiteChrome chrome = TestSites.chrome();

        assertThat(chrome.name("en")).isEqualTo(TestSites.NAME_EN);
        assertThat(chrome.name("bg")).isEqualTo(TestSites.NAME_BG);
        // German is not written: English is the fallback the chain tries first.
        assertThat(chrome.name("de")).isEqualTo(TestSites.NAME_EN);
        assertThat(SiteChrome.empty("http://localhost").name("en")).isEmpty();
    }

    @Test
    @DisplayName("third-party ids render only in the shape their vendor issues")
    void identifiersAreValidated() {
        SiteSettings settings = new SiteSettings();
        settings.setGtmId("GTM-W1');alert(1)//");
        settings.setGoogleCseCx("<script>");
        settings.setFacebookAppId("12ab");
        settings.setGoogleSiteVerification("\" onload=\"x");
        SiteChrome hostile = SiteChrome.of(settings, Map.of(), "http://localhost");

        assertThat(hostile.gtmId()).isNull();
        assertThat(hostile.googleCseCx()).isNull();
        assertThat(hostile.facebookAppId()).isNull();
        assertThat(hostile.googleSiteVerification()).isNull();

        SiteChrome valid = TestSites.chrome();
        assertThat(valid.gtmId()).isEqualTo(TestSites.GTM_ID);
        assertThat(valid.googleCseCx()).isEqualTo(TestSites.CSE_CX);
        assertThat(valid.facebookAppId()).isEqualTo(TestSites.FB_APP_ID);
    }

    @Test
    @DisplayName("a preview's chrome tells no third party, and keeps everything else")
    void withoutTracking() {
        SiteChrome chrome = TestSites.chrome().withoutTracking();
        assertThat(chrome.gtmId()).isNull();
        assertThat(chrome.facebookAppId()).isNull();
        assertThat(chrome.googleSiteVerification()).isNull();
        assertThat(chrome.bingSiteVerification()).isNull();
        // Search is a reader's feature, not tracking; the name and links are the website's.
        assertThat(chrome.googleCseCx()).isEqualTo(TestSites.CSE_CX);
        assertThat(chrome.name("en")).isEqualTo(TestSites.chrome().name("en"));
    }

    @Test
    @DisplayName("absolute URLs use the configured base URL, else the request's origin")
    void urlsAreAbsolute() {
        assertThat(TestSites.chrome().url("/article/7?lang=en"))
                .isEqualTo("https://www.example.org/article/7?lang=en");

        SiteChrome unconfigured = SiteChrome.of(new SiteSettings(), Map.of(), "http://demo.localhost:8000/");
        assertThat(unconfigured.url("/latest")).isEqualTo("http://demo.localhost:8000/latest");
        assertThat(unconfigured.url(null)).isEqualTo("http://demo.localhost:8000/");
        // Already absolute: an image hosted elsewhere stays where it is.
        assertThat(unconfigured.url("https://cdn.example.net/a.png")).isEqualTo("https://cdn.example.net/a.png");
    }

    @Test
    @DisplayName("og:locale pairs the language with its usual region")
    void ogLocales() {
        SiteChrome chrome = TestSites.chrome();
        assertThat(chrome.ogLocale("de")).isEqualTo("de_DE");
        assertThat(chrome.ogLocale("en")).isEqualTo("en_US");
        assertThat(chrome.ogLocale("zh-TW")).isEqualTo("zh_CN");
        // Nothing usable: the website's own language, which this module's tests make Bulgarian.
        assertThat(chrome.ogLocale(null)).isEqualTo("bg_BG");
        assertThat(chrome.ogLocale("\"><x")).isEqualTo("bg_BG");
    }

    @Test
    @DisplayName("menu links are built from kind and reference, carrying the reader's language")
    void menuLinksAreBuilt() {
        List<SiteChrome.MenuEntry> main = TestSites.chrome().menu(Menu.MAIN, "en");

        assertThat(main).extracting(SiteChrome.MenuEntry::label).containsExactly("Home", "Sections", "About us");
        assertThat(main.get(0).href()).isEqualTo("/?lang=en");
        assertThat(main.get(1).group()).isTrue();
        assertThat(main.get(1).href()).isNull();
        assertThat(main.get(1).children()).extracting(SiteChrome.MenuEntry::href)
                .containsExactly("/list/2?lang=en", "/list/3?lang=en");
        assertThat(main.get(2).href()).isEqualTo("/p/about?lang=en");
        assertThat(TestSites.chrome().menu("nonexistent", "en")).isEmpty();
    }

    @Test
    @DisplayName("an item that cannot be linked safely, or has no label, is left out")
    void unsafeOrUnlabelledItemsAreDropped() {
        Menu menu = new Menu(Menu.MAIN);
        menu.getItems().add(item(MenuLinkKind.URL, "javascript:alert(1)", "Bad"));
        menu.getItems().add(item(MenuLinkKind.URL, "//evil.example/", "Also bad"));
        menu.getItems().add(item(MenuLinkKind.CATEGORY, "../2", "Not a category id"));
        menu.getItems().add(item(MenuLinkKind.PAGE, "About Us", "Not a slug"));
        menu.getItems().add(item(MenuLinkKind.HOME, null, null));
        menu.getItems().add(item(MenuLinkKind.URL, "https://elsewhere.example/", "Elsewhere"));
        menu.getItems().add(item(MenuLinkKind.URL, "https://www.example.org/archive", "Archive"));

        SiteChrome chrome = SiteChrome.of(TestSites.settings(), Map.of(Menu.MAIN, menu), "http://localhost");
        List<SiteChrome.MenuEntry> entries = chrome.menu(Menu.MAIN, "en");

        assertThat(entries).extracting(SiteChrome.MenuEntry::label).containsExactly("Elsewhere", "Archive");
        assertThat(entries.get(0).external()).isTrue();
        // The website's own host is not "external", however it was written.
        assertThat(entries.get(1).external()).isFalse();
    }

    @Test
    @DisplayName("contact details split into lines and report whether there are any")
    void contactLines() {
        SiteChrome chrome = TestSites.chrome();
        assertThat(chrome.contactAddressLines()).containsExactly("1 Example Street", "Example City");
        assertThat(chrome.hasContact()).isTrue();
        assertThat(SiteChrome.empty("http://localhost").hasContact()).isFalse();
        assertThat(SiteChrome.empty("http://localhost").contactAddressLines()).isEmpty();
    }

    @Test
    @DisplayName("profiles without an http(s) address or a network name are not rendered")
    void profilesAreFiltered() {
        SiteSettings settings = new SiteSettings();
        settings.getSocialProfiles().add(new SiteSettings.SocialProfile("Instagram", "javascript:x"));
        settings.getSocialProfiles().add(new SiteSettings.SocialProfile("", "https://example.org/"));
        settings.getSocialProfiles().add(new SiteSettings.SocialProfile("Mastodon", "https://mastodon.example/@x"));

        assertThat(SiteChrome.of(settings, Map.of(), "http://localhost").socialProfiles())
                .extracting(SiteSettings.SocialProfile::getNetwork).containsExactly("Mastodon");
    }

    @Test
    @DisplayName("the language switcher links back to this page, prefix dropped and query kept")
    void languageLinks() {
        io.pacod.cms.core.data.Language en = new io.pacod.cms.core.data.Language("en");
        en.setNativeName("English");
        io.pacod.cms.core.data.Language de = new io.pacod.cms.core.data.Language("de");
        de.setNativeName("Deutsch");
        List<io.pacod.cms.core.data.Language> languages = List.of(en, de);

        SiteChrome search = SiteChrome.of(TestSites.settings(), Map.of(), "http://localhost", languages,
                "/search", "q=solar%20panels&page=2&lang=en");
        assertThat(search.languageLinks("en")).extracting(SiteChrome.LanguageLink::href)
                .containsExactly("/search?q=solar%20panels&page=2&lang=en", "/search?q=solar%20panels&page=2&lang=de");
        assertThat(search.languageLinks("en")).extracting(SiteChrome.LanguageLink::current)
                .containsExactly(true, false);
        assertThat(search.languageLinks("en")).extracting(SiteChrome.LanguageLink::name)
                .containsExactly("English", "Deutsch");

        // The path outranks ?lang, so a prefixed address would keep rendering German.
        SiteChrome prefixed = SiteChrome.of(TestSites.settings(), Map.of(), "http://localhost", languages,
                "/de/article/5", null);
        assertThat(prefixed.languageLinks("de")).extracting(SiteChrome.LanguageLink::href)
                .containsExactly("/article/5?lang=en", "/article/5?lang=de");
        // A route that only looks prefixed keeps its path.
        SiteChrome other = SiteChrome.of(TestSites.settings(), Map.of(), "http://localhost", languages,
                "/social", null);
        assertThat(other.languageLinks("en").get(1).href()).isEqualTo("/social?lang=de");
    }

    @Test
    @DisplayName("the website's languages are its codes in the switcher's order, and offers() asks about one")
    void languagesOffered() {
        List<io.pacod.cms.core.data.Language> languages = List.of(new io.pacod.cms.core.data.Language("en"),
                new io.pacod.cms.core.data.Language("de"), new io.pacod.cms.core.data.Language("EN"),
                new io.pacod.cms.core.data.Language());
        SiteChrome chrome = SiteChrome.of(TestSites.settings(), Map.of(), "http://localhost", languages, "/", null);

        assertThat(chrome.languageCodes()).containsExactly("en", "de");
        assertThat(chrome.offers("de")).isTrue();
        assertThat(chrome.offers("de-AT")).isTrue();
        assertThat(chrome.offers("ru")).isFalse();
        assertThat(chrome.offers(null)).isFalse();
        // No language list (a render that is not a reader's page, or an unreadable collection).
        assertThat(TestSites.chrome().languageCodes()).isEmpty();
        assertThat(TestSites.chrome().offers("bg")).isFalse();
    }

    @Test
    @DisplayName("the origin is the base URL, else the request's, without a trailing slash, as SiteOrigin rules")
    void originIsSiteOrigins() {
        SiteSettings settings = new SiteSettings();
        assertThat(SiteOrigin.of(settings, "http://demo.localhost:8000//")).isEqualTo("http://demo.localhost:8000");
        assertThat(SiteOrigin.of(null, (String) null)).isEmpty();
        settings.setBaseUrl(" https://www.example.org/ ");
        assertThat(SiteOrigin.of(settings, "http://demo.localhost:8000")).isEqualTo("https://www.example.org");
        assertThat(SiteChrome.of(settings, Map.of(), "http://demo.localhost:8000").origin())
                .isEqualTo(SiteOrigin.of(settings, "http://demo.localhost:8000"));

        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setServerName("alpha.test");
        request.setServerPort(8000);
        request.setRequestURI("/article/5");
        assertThat(SiteOrigin.ofRequest(request)).isEqualTo("http://alpha.test:8000");
        assertThat(SiteOrigin.of(new SiteSettings(), request)).isEqualTo("http://alpha.test:8000");
    }

    private static MenuItem item(MenuLinkKind kind, String target, String label) {
        MenuItem item = new MenuItem();
        item.setKind(kind);
        item.setTarget(target);
        if (label != null) {
            item.setLabels(List.of(TextItem.builder().lang("en").text(label).build()));
        }
        return item;
    }
}
