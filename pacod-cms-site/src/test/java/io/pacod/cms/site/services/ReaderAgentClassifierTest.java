package io.pacod.cms.site.services;

import io.pacod.cms.core.data.ReaderBrowser;
import io.pacod.cms.core.data.ReaderDevice;
import io.pacod.cms.core.data.ReaderOs;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.site.services.ReaderAgentClassifier.ReaderAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader's browser, system and device, as a table of captured User-Agents. The order traps --
 * every browser claiming to be another -- are what this pins; add the live string of a browser
 * that lands under the wrong row before changing a rule.
 */
class ReaderAgentClassifierTest {

    static Stream<Arguments> browsers() {
        return Stream.of(
                Arguments.of(SocialSourceClassifierTest.CHROME, ReaderBrowser.CHROME, ReaderOs.WINDOWS, ReaderDevice.DESKTOP),
                Arguments.of(SocialSourceClassifierTest.SAFARI, ReaderBrowser.SAFARI, ReaderOs.IOS, ReaderDevice.MOBILE),
                Arguments.of(SocialSourceClassifierTest.CUBOT, ReaderBrowser.CHROME, ReaderOs.ANDROID, ReaderDevice.MOBILE),
                Arguments.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36", ReaderBrowser.CHROME, ReaderOs.MACOS, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "Version/17.5 Safari/605.1.15", ReaderBrowser.SAFARI, ReaderOs.MACOS, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0",
                        ReaderBrowser.FIREFOX, ReaderOs.LINUX, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0",
                        ReaderBrowser.FIREFOX, ReaderOs.ANDROID, ReaderDevice.MOBILE),
                Arguments.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "FxiOS/131.0 Mobile/15E148 Safari/605.1.15", ReaderBrowser.FIREFOX, ReaderOs.IOS, ReaderDevice.MOBILE),
                Arguments.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "CriOS/130.0.6723.90 Mobile/15E148 Safari/604.1", ReaderBrowser.CHROME, ReaderOs.IOS, ReaderDevice.MOBILE),
                Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0", ReaderBrowser.EDGE, ReaderOs.WINDOWS, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Mobile Safari/537.36 EdgA/130.0.0.0", ReaderBrowser.EDGE, ReaderOs.ANDROID, ReaderDevice.MOBILE),
                Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36 OPR/114.0.0.0", ReaderBrowser.OPERA, ReaderOs.WINDOWS, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36",
                        ReaderBrowser.SAMSUNG, ReaderOs.ANDROID, ReaderDevice.MOBILE),
                Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 YaBrowser/24.6.0.0 Safari/537.36", ReaderBrowser.YANDEX, ReaderOs.WINDOWS, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Safari/537.36 Vivaldi/7.0.3495.11", ReaderBrowser.VIVALDI, ReaderOs.LINUX, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "Version/17.5 Mobile/15E148 Safari/604.1", ReaderBrowser.SAFARI, ReaderOs.IOS, ReaderDevice.TABLET),
                // Chrome leaves "Mobile" out on an Android tablet, which is how the tablet is told apart.
                Arguments.of("Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36", ReaderBrowser.CHROME, ReaderOs.ANDROID, ReaderDevice.TABLET),
                Arguments.of("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36", ReaderBrowser.CHROME, ReaderOs.CHROME_OS, ReaderDevice.DESKTOP),
                Arguments.of("Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko",
                        ReaderBrowser.INTERNET_EXPLORER, ReaderOs.WINDOWS, ReaderDevice.DESKTOP),
                // A console and a television describe a desktop too; the device rule sees them first.
                Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64; Xbox; Xbox One) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edge/44.18363.8131",
                        ReaderBrowser.EDGE, ReaderOs.WINDOWS, ReaderDevice.OTHER),
                Arguments.of("Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
                        ReaderBrowser.SAMSUNG, ReaderOs.LINUX, ReaderDevice.OTHER));
    }

    @ParameterizedTest(name = "{1} on {2}, {3}")
    @MethodSource("browsers")
    @DisplayName("a captured User-Agent names its browser, system and device")
    void browsersAreRecognised(String userAgent, ReaderBrowser browser, ReaderOs os, ReaderDevice device) {
        assertThat(ReaderAgentClassifier.classify(userAgent)).isEqualTo(new ReaderAgent(browser, os, device));
    }

    /**
     * The link page's in-app table, read through {@code SocialSourceClassifier.inAppBrowser}: every
     * app it names is a browser of the same name here, so the two screens agree. Each string is
     * Chrome or Safari underneath and says so, which is why these rules come first.
     */
    @ParameterizedTest(name = "{1}")
    @MethodSource("io.pacod.cms.site.services.SocialSourceClassifierTest#inAppBrowsers")
    @DisplayName("an in-app browser is its app, not the engine underneath")
    void inAppBrowsersAreTheirApp(String userAgent, SocialSource source) {
        ReaderAgent agent = ReaderAgentClassifier.classify(userAgent);

        assertThat(agent.browser()).isEqualTo(ReaderBrowser.valueOf(source.name()));
        assertThat(agent.device()).isEqualTo(ReaderDevice.MOBILE);
    }

    @Test
    @DisplayName("iPadOS asking for the desktop site is a Mac: nothing in the request says otherwise")
    void iPadOsReadsAsAMac() {
        String desktopIpad = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.5 Safari/605.1.15";

        assertThat(ReaderAgentClassifier.classify(desktopIpad))
                .isEqualTo(new ReaderAgent(ReaderBrowser.SAFARI, ReaderOs.MACOS, ReaderDevice.DESKTOP));
    }

    @Test
    @DisplayName("an unknown, blank or missing User-Agent is OTHER three times, never null")
    void unknownIsOther() {
        assertThat(ReaderAgentClassifier.classify("SomethingElse/1.0")).isEqualTo(ReaderAgent.UNKNOWN);
        assertThat(ReaderAgentClassifier.classify("   ")).isEqualTo(ReaderAgent.UNKNOWN);
        assertThat(ReaderAgentClassifier.classify(null)).isEqualTo(ReaderAgent.UNKNOWN);
        // A WebKit browser that names no family of its own is not Safari: Safari says Version/.
        assertThat(ReaderAgentClassifier.classify("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148").browser()).isEqualTo(ReaderBrowser.OTHER);
    }
}
