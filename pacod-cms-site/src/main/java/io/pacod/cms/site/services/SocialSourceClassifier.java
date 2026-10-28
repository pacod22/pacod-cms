package io.pacod.cms.site.services;

import io.pacod.cms.core.data.SocialSource;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which social network a visit to {@code /social} came from. A pure function of four request
 * values, so it is tested as a table and never needs a server.
 *
 * <h2>The order is the whole design</h2>
 * <ol>
 *   <li><b>{@code ?src=}</b>, the tag the operator put in the bio
 *       ({@code /social?src=instagram}). The only signal that is always right, which is why the
 *       studio prints a ready-to-paste address per network.</li>
 *   <li><b>{@code utm_source}</b>, the same thing spelled the way campaign tools spell it.</li>
 *   <li><b>The in-app browser's User-Agent token.</b> A tap on a bio link opens the app's own
 *       browser, which says so: {@code Instagram 300.0…}, {@code FBAN/FBIOS}, {@code musical_ly}.
 *       Above the referrer because those browsers often send none, or send the shim's.</li>
 *   <li><b>The {@code Referer} host</b> -- {@code l.instagram.com}, {@code l.facebook.com},
 *       {@code t.co}, {@code lnkd.in}: the link shims the networks route outbound clicks through.
 *       Or an {@code android-app://<package>/} referrer, which Chrome sends for an app that opens
 *       links in the browser rather than in a WebView of its own -- matched by exact package
 *       name, see {@link #ANDROID_PACKAGES}.</li>
 *   <li><b>The network's click id</b> on the address -- {@code fbclid}, {@code ttclid} and the
 *       rest: what is left when the app opened the link in the phone's own browser and the
 *       referrer was stripped. Below the referrer, because Instagram appends {@code fbclid} too.</li>
 *   <li>this site's own host → {@link SocialSource#SITE}; any other referrer →
 *       {@link SocialSource#OTHER}; nothing at all → {@link SocialSource#DIRECT}.</li>
 * </ol>
 * An explicit tag that names nothing known is {@link SocialSource#OTHER}, not a reason to look
 * further: it is still a statement about where the link was posted, and a UA guess must not
 * overrule it.
 *
 * <h2>Where the tokens come from</h2>
 * The UA patterns follow the {@code inapp-spy} library's table (v5.0.10, {@code regexAppName.ts}),
 * in its order -- Messenger before Facebook, because Messenger's in-app browser carries
 * {@code FB_IAB} too, and Instagram before Facebook for the same reason. These tokens change with
 * app releases; when a network's share drops to nothing while its tagged address still works,
 * capture a live UA from that app and update the table here.
 *
 * <p>Only the resulting key is stored -- never a header. See {@code SiteVisitor}.
 *
 * <h2>Bots</h2>
 * Link-preview fetchers ({@code facebookexternalhit}, {@code Twitterbot}, {@code WhatsApp/…}) fetch
 * {@code /social} every time the address is pasted into a post, and their User-Agents name the very
 * network they belong to -- so unless they are excluded first, every paste would count as a
 * Facebook visit. {@link #isBot} is checked by the controller before anything is recorded.
 */
public final class SocialSourceClassifier {

    private record UaRule(SocialSource source, Pattern pattern) {
    }

    private static final List<UaRule> UA_RULES = List.of(
            rule(SocialSource.MESSENGER, "\\bFB[\\w_]+/(Messenger)"),
            rule(SocialSource.INSTAGRAM, "\\bInstagram"),
            rule(SocialSource.FACEBOOK, "\\bFB[\\w_]+/|\\bFacebook|\\bFBAN\\b|\\bFBAV\\b"),
            rule(SocialSource.THREADS, "\\bBarcelona"),
            rule(SocialSource.TIKTOK, "musical_ly|Bytedance|\\bTikTok"),
            rule(SocialSource.LINKEDIN, "LinkedInApp"),
            rule(SocialSource.SNAPCHAT, "Snapchat"),
            rule(SocialSource.X, "\\bTwitter"),
            rule(SocialSource.PINTEREST, "\\bPinterest"),
            rule(SocialSource.WHATSAPP, "\\b(WAiOS|WA4A)/"));

    /**
     * Host suffixes per network. A referrer host matches a suffix when it equals it or ends with
     * {@code "." + suffix}, so {@code l.instagram.com} and {@code www.instagram.com} are both
     * Instagram and {@code notinstagram.com} is not.
     */
    private static final List<Map.Entry<SocialSource, List<String>>> REFERRER_HOSTS = List.of(
            Map.entry(SocialSource.INSTAGRAM, List.of("instagram.com")),
            Map.entry(SocialSource.FACEBOOK, List.of("facebook.com", "fb.com", "fb.me")),
            Map.entry(SocialSource.MESSENGER, List.of("messenger.com", "m.me")),
            Map.entry(SocialSource.THREADS, List.of("threads.net", "threads.com")),
            Map.entry(SocialSource.TIKTOK, List.of("tiktok.com")),
            Map.entry(SocialSource.YOUTUBE, List.of("youtube.com", "youtu.be")),
            Map.entry(SocialSource.X, List.of("t.co", "x.com", "twitter.com")),
            Map.entry(SocialSource.LINKEDIN, List.of("linkedin.com", "lnkd.in")),
            Map.entry(SocialSource.PINTEREST, List.of("pin.it")),
            Map.entry(SocialSource.SNAPCHAT, List.of("snapchat.com")),
            Map.entry(SocialSource.TELEGRAM, List.of("t.me", "telegram.me", "telegram.org")),
            Map.entry(SocialSource.VIBER, List.of("viber.com")),
            Map.entry(SocialSource.WHATSAPP, List.of("whatsapp.com", "wa.me")),
            // A link in a repository's README, an issue or a gist (gist.github.com).
            Map.entry(SocialSource.GITHUB, List.of("github.com")),
            // Webmail: a link in a newsletter opened in the browser. abv.bg and mail.bg are
            // regional providers, listed beside the international ones.
            Map.entry(SocialSource.EMAIL, List.of("mail.google.com", "outlook.live.com",
                    "outlook.office.com", "outlook.office365.com", "mail.yahoo.com", "abv.bg", "mail.bg")));

    /**
     * Android apps that open a link in Chrome or a Custom Tab rather than in a WebView of their
     * own. There is then no in-app token in the User-Agent, and Chrome reports the app as
     * {@code Referer: android-app://<package>/} -- so the "host" is a package name, which no web
     * domain suffix above can match. Exact names, each checked against the Play Store listing
     * (2026-10-11); {@code com.ss.android.ugc.trill} is left out because it is not listed from
     * here, and TikTok's in-app token covers it anyway.
     */
    private static final Map<String, SocialSource> ANDROID_PACKAGES = Map.ofEntries(
            Map.entry("com.instagram.android", SocialSource.INSTAGRAM),
            Map.entry("com.instagram.barcelona", SocialSource.THREADS),
            Map.entry("com.facebook.katana", SocialSource.FACEBOOK),
            Map.entry("com.facebook.lite", SocialSource.FACEBOOK),
            Map.entry("com.facebook.orca", SocialSource.MESSENGER),
            Map.entry("com.zhiliaoapp.musically", SocialSource.TIKTOK),
            Map.entry("com.google.android.youtube", SocialSource.YOUTUBE),
            Map.entry("com.twitter.android", SocialSource.X),
            Map.entry("com.linkedin.android", SocialSource.LINKEDIN),
            Map.entry("com.pinterest", SocialSource.PINTEREST),
            Map.entry("com.snapchat.android", SocialSource.SNAPCHAT),
            Map.entry("org.telegram.messenger", SocialSource.TELEGRAM),
            Map.entry("com.viber.voip", SocialSource.VIBER),
            Map.entry("com.whatsapp", SocialSource.WHATSAPP),
            Map.entry("com.whatsapp.w4b", SocialSource.WHATSAPP),
            Map.entry("com.google.android.gm", SocialSource.EMAIL),
            Map.entry("com.microsoft.office.outlook", SocialSource.EMAIL),
            Map.entry("com.yahoo.mobile.client.android.mail", SocialSource.EMAIL));

    /**
     * The click ids networks append to outbound links, by query parameter name (compared
     * case-insensitively). Only the name is read -- the value identifies the click and is never
     * stored, the rule {@code SiteVisitor} states for everything here.
     */
    private static final List<Map.Entry<String, SocialSource>> CLICK_IDS = List.of(
            Map.entry("fbclid", SocialSource.FACEBOOK),
            Map.entry("igshid", SocialSource.INSTAGRAM),
            Map.entry("igsh", SocialSource.INSTAGRAM),
            Map.entry("ttclid", SocialSource.TIKTOK),
            Map.entry("twclid", SocialSource.X),
            Map.entry("li_fat_id", SocialSource.LINKEDIN),
            Map.entry("ScCid", SocialSource.SNAPCHAT));

    /** {@code pinterest.com}, {@code pinterest.co.uk}, {@code de.pinterest.com} -- one brand, many TLDs. */
    private static final Pattern PINTEREST_HOST = Pattern.compile("(?:.+\\.)?pinterest\\.[a-z.]+");

    /**
     * Link-preview fetchers, crawlers and scripted clients. The generic {@code bot} is guarded
     * against the Cubot handset brand, the one real phone whose UA would otherwise match.
     * {@code WhatsApp/} is the preview fetcher; WhatsApp's in-app browser says {@code WAiOS}/
     * {@code WA4A} instead, which is why it is not a bot here.
     */
    private static final Pattern BOT = Pattern.compile(
            "facebookexternalhit|facebookcatalog|facebot|meta-external(agent|fetcher)"
                    + "|twitterbot|linkedinbot|telegrambot|slackbot|slack-imgproxy|discordbot|pinterestbot"
                    + "|^whatsapp/|skypeuripreview|embedly|vkshare|redditbot|applebot|googlebot"
                    + "|google-inspectiontool|bingbot|baiduspider|duckduckbot|petalbot|ahrefsbot"
                    + "|semrushbot|mj12bot|bytespider|gptbot|claudebot|ccbot|headlesschrome"
                    + "|python-requests|python-urllib|^curl/|^wget/|go-http-client|okhttp|^java/|libwww"
                    + "|httpclient|crawler|spider|(?<!cu)bot\\b",
            Pattern.CASE_INSENSITIVE);

    private SocialSourceClassifier() {
    }

    private static UaRule rule(SocialSource source, String regex) {
        return new UaRule(source, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** As {@link #classify(String, String, Set, String, String, String)}, with no query parameters. */
    public static SocialSource classify(String src, String utmSource, String userAgent,
                                        String referer, String ownHost) {
        return classify(src, utmSource, Set.of(), userAgent, referer, ownHost);
    }

    /**
     * @param params  the names of the request's query parameters, for the networks' click ids
     * @param ownHost the host this request was addressed to ({@code request.getServerName()},
     *                which honours {@code X-Forwarded-Host} behind Caddy), for recognising a
     *                referrer from this site on any of the names it answers to
     */
    public static SocialSource classify(String src, String utmSource, Set<String> params, String userAgent,
                                        String referer, String ownHost) {
        SocialSource tagged = SocialSource.of(src);
        if (tagged != null) {
            return tagged;
        }
        SocialSource campaign = SocialSource.of(utmSource);
        if (campaign != null) {
            return campaign;
        }

        if (userAgent != null && !userAgent.isBlank()) {
            for (UaRule rule : UA_RULES) {
                if (rule.pattern().matcher(userAgent).find()) {
                    return rule.source();
                }
            }
        }

        String host = hostOf(referer);
        boolean ownReferrer = host != null && !isAndroidApp(referer) && isOwnHost(host, ownHost);
        SocialSource fromReferrer = host == null || ownReferrer ? null : referrerNetwork(host, referer);
        if (fromReferrer != null) {
            return fromReferrer;
        }

        // The click id a network appends to every outbound link -- Facebook's fbclid above all.
        // It is what is left when the app opened the link in the phone's own browser and the
        // referrer was stripped on the way, which would otherwise count as a direct visit.
        // Below the User-Agent and a recognised referrer, because Instagram, being Meta, appends
        // fbclid too: its in-app browser or its l.instagram.com shim is the better witness.
        for (Map.Entry<String, SocialSource> clickId : CLICK_IDS) {
            for (String param : params == null ? Set.<String>of() : params) {
                if (clickId.getKey().equalsIgnoreCase(param)) {
                    return clickId.getValue();
                }
            }
        }

        if (host == null) {
            return SocialSource.DIRECT;
        }
        return ownReferrer ? SocialSource.SITE : SocialSource.OTHER;
    }

    /** The network a referrer names, or {@code null} -- an unknown site, or an unknown app. */
    private static SocialSource referrerNetwork(String host, String referer) {
        if (isAndroidApp(referer)) {
            // A package name, never a web host: one this table does not know must not fall
            // through to the suffix match, where "com.example.site" could look like a domain.
            return ANDROID_PACKAGES.get(host);
        }
        if (PINTEREST_HOST.matcher(host).matches()) {
            return SocialSource.PINTEREST;
        }
        for (Map.Entry<SocialSource, List<String>> entry : REFERRER_HOSTS) {
            for (String suffix : entry.getValue()) {
                if (host.equals(suffix) || host.endsWith("." + suffix)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** Whether nothing about this request should be counted -- see the class javadoc. */
    public static boolean isBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            // A browser always sends one. A request with none is a script.
            return true;
        }
        return BOT.matcher(userAgent.strip()).find();
    }

    /**
     * Whether the referrer is {@code /social} itself on this site: the language switcher, or a
     * reload. Such a render is not a new visit, and counting it would also lower the page's
     * click-through rate for every reader who switched language before choosing a link.
     */
    public static boolean isOwnSocialPage(String referer, String ownHost) {
        String host = hostOf(referer);
        if (host == null || !isOwnHost(host, ownHost)) {
            return false;
        }
        try {
            String path = URI.create(referer.strip()).getPath();
            return "/social".equals(path) || "/social/".equals(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isAndroidApp(String referer) {
        return referer.strip().regionMatches(true, 0, "android-app://", 0, "android-app://".length());
    }

    private static boolean isOwnHost(String host, String ownHost) {
        String own = stripWww(ownHost == null ? null : ownHost.toLowerCase(Locale.ROOT));
        String candidate = stripWww(host);
        // The Host the request arrived on is the website's own: on a multi-site platform it is
        // what identified the website in the first place. www. and the bare domain are one site.
        return own != null && !own.isBlank() && candidate.equals(own);
    }

    /** The referrer's host, lowercase, or {@code null} when absent or unparseable. */
    static String hostOf(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referer.strip()).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripWww(String host) {
        if (host == null) {
            return null;
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
