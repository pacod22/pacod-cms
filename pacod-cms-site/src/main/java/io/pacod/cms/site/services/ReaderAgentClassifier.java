package io.pacod.cms.site.services;

import io.pacod.cms.core.data.ReaderBrowser;
import io.pacod.cms.core.data.ReaderDevice;
import io.pacod.cms.core.data.ReaderOs;
import io.pacod.cms.core.data.SocialSource;

import java.util.List;
import java.util.Locale;

/**
 * Which browser, operating system and kind of device a reader's User-Agent names. A pure function
 * of one header, so it is tested as a table and never needs a server -- the shape
 * {@code CrawlerClassifier} has for crawlers.
 *
 * <p>Run only for requests already counted as a reader's: {@code CrawlerTraffic} has sorted the
 * automated ones out before anything reaches here, so nothing below asks whether a client is a bot.
 *
 * <h2>The order is the whole design</h2>
 * Each table is read top to bottom, a lowercase substring match, first match wins; a rule may need
 * several substrings at once. Nearly every browser claims to be another one as well, so the
 * specific ones come first:
 * <ul>
 *   <li><b>In-app browsers</b> before everything: Instagram's and Facebook's browsers are Chrome or
 *       Safari underneath and say so. Their tokens are {@code SocialSourceClassifier}'s, read through
 *       {@link SocialSourceClassifier#inAppBrowser}, so the link page's statistics and these name the
 *       same app.</li>
 *   <li><b>Edge, Opera, Samsung, Yandex, Vivaldi before Chrome</b>, whose token each of them
 *       carries; <b>Chrome before Safari</b>, because every Chrome User-Agent ends in
 *       {@code Safari/537.36}; Safari itself only with {@code Version/}, which other WebKit
 *       browsers leave out.</li>
 *   <li><b>Android before Linux</b>, which every Android User-Agent names, and <b>iOS before
 *       macOS</b>, since an iPhone is "like Mac OS X".</li>
 *   <li>A television or a console before the desktop its User-Agent also describes; a phone by
 *       {@code Mobi} before an Android tablet, which is Android without it.</li>
 * </ul>
 *
 * <h2>What it cannot tell</h2>
 * iPadOS asks for desktop sites and sends a Mac's User-Agent: an iPad reading that way is a macOS
 * desktop here. Brave is Chrome by design. Anything unrecognised is {@code OTHER}, never
 * {@code null}, so every view lands in exactly one row of each split.
 */
public final class ReaderAgentClassifier {

    /** One reader's three families. */
    public record ReaderAgent(ReaderBrowser browser, ReaderOs os, ReaderDevice device) {

        /** A User-Agent that says nothing -- absent or blank. */
        public static final ReaderAgent UNKNOWN = new ReaderAgent(ReaderBrowser.OTHER, ReaderOs.OTHER,
                ReaderDevice.OTHER);
    }

    /** Matches when the lowercased User-Agent contains every one of {@code all}. */
    private record Rule<T>(T value, List<String> all) {

        boolean matches(String ua) {
            for (String token : all) {
                if (!ua.contains(token)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final List<Rule<ReaderBrowser>> BROWSERS = List.of(
            rule(ReaderBrowser.EDGE, "edg/"),
            rule(ReaderBrowser.EDGE, "edga/"),
            rule(ReaderBrowser.EDGE, "edgios/"),
            rule(ReaderBrowser.EDGE, "edge/"),
            rule(ReaderBrowser.OPERA, "opr/"),
            rule(ReaderBrowser.OPERA, "opios/"),
            rule(ReaderBrowser.OPERA, "opt/"),
            rule(ReaderBrowser.OPERA, "opera"),
            rule(ReaderBrowser.SAMSUNG, "samsungbrowser/"),
            rule(ReaderBrowser.YANDEX, "yabrowser/"),
            rule(ReaderBrowser.VIVALDI, "vivaldi/"),
            rule(ReaderBrowser.FIREFOX, "firefox/"),
            rule(ReaderBrowser.FIREFOX, "fxios/"),
            rule(ReaderBrowser.INTERNET_EXPLORER, "msie "),
            rule(ReaderBrowser.INTERNET_EXPLORER, "trident/"),
            rule(ReaderBrowser.CHROME, "chrome/"),
            rule(ReaderBrowser.CHROME, "crios/"),
            rule(ReaderBrowser.CHROME, "chromium/"),
            rule(ReaderBrowser.SAFARI, "safari/", "version/"));

    private static final List<Rule<ReaderOs>> SYSTEMS = List.of(
            rule(ReaderOs.ANDROID, "android"),
            rule(ReaderOs.IOS, "iphone"),
            rule(ReaderOs.IOS, "ipad"),
            rule(ReaderOs.IOS, "ipod"),
            rule(ReaderOs.CHROME_OS, "cros "),
            rule(ReaderOs.WINDOWS, "windows"),
            rule(ReaderOs.MACOS, "macintosh"),
            rule(ReaderOs.MACOS, "mac os x"),
            rule(ReaderOs.LINUX, "linux"),
            rule(ReaderOs.LINUX, "x11"));

    private static final List<Rule<ReaderDevice>> DEVICES = List.of(
            rule(ReaderDevice.OTHER, "smart-tv"),
            rule(ReaderDevice.OTHER, "smarttv"),
            rule(ReaderDevice.OTHER, "crkey"),
            rule(ReaderDevice.OTHER, "xbox"),
            rule(ReaderDevice.OTHER, "playstation"),
            rule(ReaderDevice.OTHER, "nintendo"),
            rule(ReaderDevice.TABLET, "ipad"),
            rule(ReaderDevice.TABLET, "tablet"),
            rule(ReaderDevice.TABLET, "kindle"),
            rule(ReaderDevice.TABLET, "silk/"),
            rule(ReaderDevice.MOBILE, "mobi"),
            rule(ReaderDevice.MOBILE, "iphone"),
            rule(ReaderDevice.MOBILE, "ipod"),
            rule(ReaderDevice.MOBILE, "windows phone"),
            // An Android that did not say Mobi above: Chrome and Firefox leave it out on tablets.
            rule(ReaderDevice.TABLET, "android"),
            rule(ReaderDevice.DESKTOP, "windows"),
            rule(ReaderDevice.DESKTOP, "macintosh"),
            rule(ReaderDevice.DESKTOP, "cros "),
            rule(ReaderDevice.DESKTOP, "x11"),
            rule(ReaderDevice.DESKTOP, "linux"));

    private ReaderAgentClassifier() {
    }

    private static <T> Rule<T> rule(T value, String... all) {
        return new Rule<>(value, List.of(all));
    }

    /** The reader's three families; {@link ReaderAgent#UNKNOWN} for a missing User-Agent. Never null. */
    public static ReaderAgent classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return ReaderAgent.UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return new ReaderAgent(browser(userAgent, ua), first(SYSTEMS, ua, ReaderOs.OTHER),
                first(DEVICES, ua, ReaderDevice.OTHER));
    }

    private static ReaderBrowser browser(String userAgent, String ua) {
        SocialSource app = SocialSourceClassifier.inAppBrowser(userAgent);
        if (app != null) {
            ReaderBrowser inApp = ReaderBrowser.of(app.name());
            if (inApp != null) {
                return inApp;
            }
        }
        return first(BROWSERS, ua, ReaderBrowser.OTHER);
    }

    private static <T> T first(List<Rule<T>> rules, String ua, T otherwise) {
        for (Rule<T> rule : rules) {
            if (rule.matches(ua)) {
                return rule.value();
            }
        }
        return otherwise;
    }
}
