package io.pacod.cms.site.services;

import io.pacod.cms.core.data.SocialSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attribution table, with User-Agents in the shape the apps send them. When an app changes its
 * token, a captured UA belongs here before the pattern changes.
 */
class SocialSourceClassifierTest {

    private static final String HOST = "www.example.org";

    static final String INSTAGRAM_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 Instagram 337.0.3.23.54 (iPhone14,5; iOS 17_5; bg_BG; bg; "
            + "scale=3.00; 1170x2532; 615419131; IABMV/1)";
    static final String INSTAGRAM_ANDROID = "Mozilla/5.0 (Linux; Android 14; SM-S911B Build/UP1A.231005.007; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.0.6422.165 Mobile Safari/537.36 "
            + "Instagram 408.0.0.51.78 Android (34/14; 450dpi; 1080x2340; samsung; SM-S911B; dm1q; qcom; bg_BG; "
            + "567067343; IABMV/1)";
    static final String FACEBOOK_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/470.0.0.37.97;FBBV/613335618;FBDV/iPhone14,5;"
            + "FBMD/iPhone;FBSN/iOS;FBSV/17.5;FBSS/3;FBCR/;FBID/phone;FBLC/bg_BG;FBOP/80]";
    static final String FACEBOOK_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240605.024; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.71 Mobile Safari/537.36 "
            + "[FB_IAB/FB4A;FBAV/453.0.0.40.107;]";
    static final String MESSENGER_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240605.024; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.71 Mobile Safari/537.36 "
            + "[FB_IAB/MESSENGER;FBAV/464.0.0.42.110;]";
    static final String MESSENGER_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 [FBAN/MessengerForiOS;FBAV/464.0.0.42.110;FBBV/600000000]";
    static final String TIKTOK_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 musical_ly_35.1.0 JsSdk/2.0 NetType/WIFI Channel/App Store "
            + "ByteLocale/bg Region/BG";
    static final String TIKTOK_ANDROID = "Mozilla/5.0 (Linux; Android 14; SM-A546B Build/UP1A.231005.007; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.0.6422.165 Mobile Safari/537.36 "
            + "trill_350105 JsSdk/1.0 NetType/WIFI Channel/googleplay AppName/trill app_version/35.1.5 "
            + "ByteLocale/bg Region/BG BytedanceWebview/d8a21c6";
    static final String THREADS_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 Barcelona 339.0.0.13.104 (iPhone14,5; iOS 17_5; bg_BG; bg)";
    static final String LINKEDIN_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 [LinkedInApp]/9.29.8100";
    static final String SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0.0.0 Safari/537.36";
    static final String CUBOT = "Mozilla/5.0 (Linux; Android 12; CUBOT P60) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.6099.230 Mobile Safari/537.36";

    static Stream<Arguments> inAppBrowsers() {
        return Stream.of(
                Arguments.of(INSTAGRAM_IOS, SocialSource.INSTAGRAM),
                Arguments.of(INSTAGRAM_ANDROID, SocialSource.INSTAGRAM),
                Arguments.of(FACEBOOK_IOS, SocialSource.FACEBOOK),
                Arguments.of(FACEBOOK_ANDROID, SocialSource.FACEBOOK),
                // Messenger carries FB_IAB and FBAN too, which is why it is tested first.
                Arguments.of(MESSENGER_ANDROID, SocialSource.MESSENGER),
                Arguments.of(MESSENGER_IOS, SocialSource.MESSENGER),
                Arguments.of(TIKTOK_IOS, SocialSource.TIKTOK),
                Arguments.of(TIKTOK_ANDROID, SocialSource.TIKTOK),
                Arguments.of(THREADS_IOS, SocialSource.THREADS),
                Arguments.of(LINKEDIN_IOS, SocialSource.LINKEDIN),
                Arguments.of(SAFARI + " Twitter for iPhone/10.48", SocialSource.X),
                Arguments.of(SAFARI + " Snapchat/13.0.0.42 (like Safari/8618.2.12.10.4, panda)", SocialSource.SNAPCHAT),
                Arguments.of(SAFARI + " [Pinterest/iOS]", SocialSource.PINTEREST),
                Arguments.of(SAFARI + " WAiOS/2.24.12.71", SocialSource.WHATSAPP));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("inAppBrowsers")
    @DisplayName("an in-app browser names its network")
    void inAppBrowsersAreRecognised(String userAgent, SocialSource expected) {
        assertThat(SocialSourceClassifier.classify(null, null, userAgent, null, HOST)).isEqualTo(expected);
    }

    static Stream<Arguments> referrers() {
        return Stream.of(
                Arguments.of("https://l.instagram.com/", SocialSource.INSTAGRAM),
                Arguments.of("https://www.instagram.com/", SocialSource.INSTAGRAM),
                Arguments.of("https://lm.facebook.com/l.php?u=https%3A%2F%2Fwww.example.org%2Fsocial", SocialSource.FACEBOOK),
                Arguments.of("https://l.facebook.com/", SocialSource.FACEBOOK),
                Arguments.of("https://l.messenger.com/", SocialSource.MESSENGER),
                Arguments.of("https://l.threads.net/", SocialSource.THREADS),
                Arguments.of("https://www.threads.com/", SocialSource.THREADS),
                Arguments.of("https://www.tiktok.com/", SocialSource.TIKTOK),
                Arguments.of("https://www.youtube.com/", SocialSource.YOUTUBE),
                Arguments.of("https://t.co/AbCdEf", SocialSource.X),
                Arguments.of("https://lnkd.in/xyz", SocialSource.LINKEDIN),
                Arguments.of("https://br.pinterest.com/", SocialSource.PINTEREST),
                Arguments.of("https://www.pinterest.co.uk/", SocialSource.PINTEREST),
                Arguments.of("https://t.me/examplegazette", SocialSource.TELEGRAM),
                Arguments.of("https://web.whatsapp.com/", SocialSource.WHATSAPP),
                Arguments.of("https://github.com/", SocialSource.GITHUB),
                Arguments.of("https://gist.github.com/", SocialSource.GITHUB),
                Arguments.of("https://someone.github.io/", SocialSource.OTHER),
                Arguments.of("https://mail.google.com/", SocialSource.EMAIL),
                Arguments.of("https://nm.abv.bg/", SocialSource.EMAIL),
                Arguments.of("https://www.example.org/article/3150", SocialSource.SITE),
                Arguments.of("https://example.org/", SocialSource.SITE),
                Arguments.of("https://notinstagram.com/", SocialSource.OTHER),
                Arguments.of("https://www.google.com/", SocialSource.OTHER),
                Arguments.of("not a url at all ::", SocialSource.DIRECT),
                // Chrome's referrer for an Android app that opened the link in the browser.
                Arguments.of("android-app://com.instagram.android/", SocialSource.INSTAGRAM),
                Arguments.of("android-app://com.facebook.katana", SocialSource.FACEBOOK),
                Arguments.of("android-app://com.facebook.orca/", SocialSource.MESSENGER),
                Arguments.of("android-app://com.instagram.barcelona/", SocialSource.THREADS),
                Arguments.of("android-app://com.zhiliaoapp.musically/", SocialSource.TIKTOK),
                Arguments.of("android-app://org.telegram.messenger/", SocialSource.TELEGRAM),
                Arguments.of("android-app://com.viber.voip/", SocialSource.VIBER),
                Arguments.of("android-app://com.whatsapp/", SocialSource.WHATSAPP),
                Arguments.of("android-app://com.google.android.gm/", SocialSource.EMAIL),
                // An unknown app is OTHER, never a domain-suffix guess.
                Arguments.of("android-app://com.example.reader/", SocialSource.OTHER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referrers")
    @DisplayName("a desktop browser is classified by its referrer")
    void referrersAreRecognised(String referer, SocialSource expected) {
        assertThat(SocialSourceClassifier.classify(null, null, CHROME, referer, HOST)).isEqualTo(expected);
    }

    @Test
    @DisplayName("no signal at all is DIRECT")
    void nothingIsDirect() {
        assertThat(SocialSourceClassifier.classify(null, null, SAFARI, null, HOST)).isEqualTo(SocialSource.DIRECT);
        assertThat(SocialSourceClassifier.classify("", " ", CHROME, "", HOST)).isEqualTo(SocialSource.DIRECT);
    }

    @Test
    @DisplayName("the explicit tag beats utm_source, which beats the User-Agent, which beats the referrer")
    void precedence() {
        assertThat(SocialSourceClassifier.classify("fb", "instagram", INSTAGRAM_IOS, "https://t.co/x", HOST))
                .isEqualTo(SocialSource.FACEBOOK);
        assertThat(SocialSourceClassifier.classify(null, "tiktok", INSTAGRAM_IOS, "https://t.co/x", HOST))
                .isEqualTo(SocialSource.TIKTOK);
        assertThat(SocialSourceClassifier.classify(null, null, INSTAGRAM_IOS, "https://l.facebook.com/", HOST))
                .isEqualTo(SocialSource.INSTAGRAM);
    }

    /**
     * Facebook opening a link in the phone's own browser, with the referrer stripped on the way:
     * no in-app token, no Referer, nothing but the fbclid it appends -- which would otherwise count
     * as a direct visit.
     */
    @Test
    @DisplayName("a network's click id on the address names it when nothing stronger does")
    void clickIdsAreRecognised() {
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("fbclid"), SAFARI, null, HOST))
                .isEqualTo(SocialSource.FACEBOOK);
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("lang", "FBCLID"), CHROME, null, HOST))
                .isEqualTo(SocialSource.FACEBOOK);
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("ttclid"), SAFARI, null, HOST))
                .isEqualTo(SocialSource.TIKTOK);
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("igsh"), SAFARI, null, HOST))
                .isEqualTo(SocialSource.INSTAGRAM);
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("twclid"), SAFARI, null, HOST))
                .isEqualTo(SocialSource.X);
        // An unknown referrer plus fbclid is still Facebook's link shim at work.
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("fbclid"), SAFARI,
                "https://unknown.example/", HOST)).isEqualTo(SocialSource.FACEBOOK);
        // An unknown Android app plus fbclid too.
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("fbclid"), CHROME,
                "android-app://com.example.reader/", HOST)).isEqualTo(SocialSource.FACEBOOK);
    }

    @Test
    @DisplayName("the click id ranks below the tag, the in-app browser and a recognised referrer")
    void clickIdsRankBelowStrongerSignals() {
        // Instagram is Meta and appends fbclid too; its own browser and shim are the better witness.
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("fbclid"), INSTAGRAM_IOS, null, HOST))
                .isEqualTo(SocialSource.INSTAGRAM);
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("fbclid"), SAFARI,
                "https://l.instagram.com/", HOST)).isEqualTo(SocialSource.INSTAGRAM);
        assertThat(SocialSourceClassifier.classify("qr", null, java.util.Set.of("fbclid"), SAFARI, null, HOST))
                .isEqualTo(SocialSource.QR);
        // Without a click id nothing changes: still direct.
        assertThat(SocialSourceClassifier.classify(null, null, java.util.Set.of("lang"), SAFARI, null, HOST))
                .isEqualTo(SocialSource.DIRECT);
    }

    @Test
    @DisplayName("an unknown tag is OTHER and is not overruled by a guess")
    void unknownTagIsOther() {
        assertThat(SocialSourceClassifier.classify("autumn-newsletter", null, INSTAGRAM_IOS, null, HOST))
                .isEqualTo(SocialSource.OTHER);
    }

    @Test
    @DisplayName("this site is recognised on whatever host the request arrived on")
    void ownHostIsSite() {
        assertThat(SocialSourceClassifier.classify(null, null, CHROME, "http://localhost:8000/", "localhost"))
                .isEqualTo(SocialSource.SITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "meta-externalagent/1.1 (+https://developers.facebook.com/docs/sharing/webmasters/crawler)",
            "Twitterbot/1.0",
            "LinkedInBot/1.0 (compatible; Mozilla/5.0; Apache-HttpClient +http://www.linkedin.com)",
            "WhatsApp/2.23.20.0 A",
            "TelegramBot (like TwitterBot)",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/126.0.0.0 Safari/537.36",
            "curl/8.7.1",
            "python-requests/2.32.3",
            ""})
    @DisplayName("link-preview fetchers, crawlers and scripts are bots")
    void botsAreRecognised(String userAgent) {
        assertThat(SocialSourceClassifier.isBot(userAgent)).isTrue();
    }

    @Test
    @DisplayName("real browsers, in-app ones and a Cubot handset are not bots")
    void browsersAreNotBots() {
        for (String userAgent : new String[]{INSTAGRAM_IOS, INSTAGRAM_ANDROID, FACEBOOK_IOS, FACEBOOK_ANDROID,
                MESSENGER_IOS, TIKTOK_IOS, TIKTOK_ANDROID, THREADS_IOS, LINKEDIN_IOS, SAFARI, CHROME, CUBOT}) {
            assertThat(SocialSourceClassifier.isBot(userAgent)).as(userAgent).isFalse();
        }
        assertThat(SocialSourceClassifier.isBot(null)).isTrue();
    }

    @Test
    @DisplayName("a referrer that is /social itself is a reload or a language switch")
    void ownSocialPageIsRecognised() {
        assertThat(SocialSourceClassifier.isOwnSocialPage(
                "https://www.example.org/social?src=instagram&lang=en", HOST)).isTrue();
        assertThat(SocialSourceClassifier.isOwnSocialPage("https://www.example.org/social/", HOST)).isTrue();
        assertThat(SocialSourceClassifier.isOwnSocialPage("http://localhost:8000/social", "localhost")).isTrue();
        assertThat(SocialSourceClassifier.isOwnSocialPage("https://www.example.org/article/5", HOST)).isFalse();
        assertThat(SocialSourceClassifier.isOwnSocialPage("https://l.instagram.com/social", HOST)).isFalse();
        assertThat(SocialSourceClassifier.isOwnSocialPage(null, HOST)).isFalse();
    }
}
