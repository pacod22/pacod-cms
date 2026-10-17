package io.pacod.cms.site.testing;

import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.site.data.SiteChrome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A website as the render tests see it: every setting filled in, so a template that forgets to
 * read one is visible, and two menus shaped like the seeded ones.
 *
 * <p>The values are deliberately distinctive -- {@link #GTM_ID}, {@link #BASE_URL} -- so an assertion
 * can tell "rendered from the settings" from "rendered from a literal in the template".
 */
public final class TestSites {

    public static final String BASE_URL = "https://www.example.org";
    public static final String NAME_EN = "Example Gazette";
    public static final String NAME_BG = "Примерен вестник";
    public static final String GTM_ID = "GTM-TEST4321";
    public static final String CSE_CX = "test-cse-0123";
    public static final String FB_APP_ID = "1234567890";

    private TestSites() {
    }

    /** The full chrome, as SiteChromeAdvice would put it in the model. */
    public static SiteChrome chrome() {
        return SiteChrome.of(settings(), menus(), "http://localhost");
    }

    public static SiteSettings settings() {
        SiteSettings settings = new SiteSettings();
        settings.setNames(texts("bg", NAME_BG, "en", NAME_EN));
        settings.setTaglines(texts("bg", "Новини и ръководства", "en", "News and guides"));
        settings.setDescriptions(texts("bg", "Примерен уебсайт", "en", "An example website"));
        settings.setFooterNotes(texts("en", "© Example Gazette"));
        settings.setBaseUrl(BASE_URL);
        settings.setLogoLight("/media/brand/logo.svg");
        settings.setLogoDark("/media/brand/logo-dark.svg");
        settings.setFavicon("/media/brand/favicon.svg");
        settings.setOgImage("/media/brand/share.png");
        settings.setContactAddress("1 Example Street\nExample City");
        settings.setContactPhone("+00 123 456");
        settings.setContactEmail("editors@example.org");
        settings.setSocialProfiles(new ArrayList<>(List.of(
                new SiteSettings.SocialProfile("Instagram", "https://www.instagram.com/example/"),
                new SiteSettings.SocialProfile("YouTube", "https://www.youtube.com/@example"))));
        settings.setGtmId(GTM_ID);
        settings.setGoogleCseCx(CSE_CX);
        settings.setFacebookAppId(FB_APP_ID);
        settings.setGoogleSiteVerification("google-token-0123");
        settings.setBingSiteVerification("bing-token-0123");
        return settings;
    }

    public static Map<String, Menu> menus() {
        Menu main = new Menu(Menu.MAIN);
        main.getItems().add(item(MenuLinkKind.HOME, null, "bg", "Начало", "en", "Home"));
        MenuItem sections = item(MenuLinkKind.NONE, null, "bg", "Рубрики", "en", "Sections");
        sections.getChildren().add(item(MenuLinkKind.CATEGORY, "2", "bg", "Новини", "en", "News"));
        sections.getChildren().add(item(MenuLinkKind.CATEGORY, "3", "bg", "Анализи", "en", "In depth"));
        main.getItems().add(sections);
        main.getItems().add(item(MenuLinkKind.PAGE, "about", "bg", "За нас", "en", "About us"));

        Menu footer = new Menu(Menu.FOOTER);
        footer.getItems().add(item(MenuLinkKind.PAGE, "about", "bg", "За нас", "en", "About us"));
        footer.getItems().add(item(MenuLinkKind.PAGE, "terms", "bg", "Условия", "en", "Terms of use"));
        footer.getItems().add(item(MenuLinkKind.PAGE, "privacy", "bg", "Поверителност", "en", "Privacy policy"));

        Map<String, Menu> menus = new LinkedHashMap<>();
        menus.put(main.getKey(), main);
        menus.put(footer.getKey(), footer);
        return menus;
    }

    private static MenuItem item(MenuLinkKind kind, String target, String... labels) {
        MenuItem item = new MenuItem();
        item.setKind(kind);
        item.setTarget(target);
        item.setLabels(texts(labels));
        return item;
    }

    private static List<TextItem> texts(String... langAndText) {
        List<TextItem> items = new ArrayList<>();
        for (int i = 0; i < langAndText.length; i += 2) {
            items.add(TextItem.builder().lang(langAndText[i]).text(langAndText[i + 1]).build());
        }
        return items;
    }
}
