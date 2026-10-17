package io.pacod.cms.core.util;

import io.pacod.cms.core.data.CrawlerCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Names the crawler behind a User-Agent: a stable bot id, its category, and the DNS suffixes its
 * operator verifies from.
 *
 * <p>A lowercase <b>substring</b> table, checked <b>in order</b>, first match wins -- so the order
 * is load-bearing and the tests pin it:
 * <ul>
 *   <li>{@code googlebot-image}/{@code -news}/{@code -video} and {@code adsbot-google-mobile} come
 *       before the shorter {@code googlebot}/{@code adsbot-google} they contain;</li>
 *   <li>{@code applebot-extended} (AI) comes before {@code applebot} (search);</li>
 *   <li>OpenAI's three agents stay three rows: GPTBot trains, OAI-SearchBot indexes, ChatGPT-User
 *       fetches live for one reader. Merging them would hide which one a site is feeding.</li>
 * </ul>
 *
 * <p>No pattern may match a <em>reader</em>, which rules out the obvious spelling of three:
 * <ul>
 *   <li>{@code pinterestbot} and {@code pinterest.com/bot}, never a bare {@code pinterest} -- the
 *       bare word is in the Pinterest app's own in-app browser;</li>
 *   <li>{@code whatsapp/}, the preview fetcher's {@code WhatsApp/2.x} shape, never a bare
 *       {@code whatsapp} -- which is what {@code SocialSourceClassifier}'s {@code ^whatsapp/}
 *       says too;</li>
 *   <li>{@code yeti/}, never {@code naver} -- Naver's crawler announces itself as Yeti, and
 *       "naver" is in the Naver app's browser.</li>
 * </ul>
 * {@code google-inspectiontool} and {@code googleother} are listed with Google's suffixes: both
 * are Google crawlers that would otherwise land in {@link CrawlerCategory#OTHER} unverified.
 * The browser User-Agents in {@code SocialSourceClassifierTest} -- every in-app browser that
 * {@code /social} exists for, and a Cubot handset -- are run through this table by
 * {@code CrawlerClassifierTest}, which is what keeps a reader's browser out of it.
 *
 * <p><b>Bot ids are stored as keys of dotted {@code $inc}s</b> in the traffic buckets
 * ({@code crawlerHitsByBot.googlebot}), so an id must be {@code [a-z0-9-]+}: a {@code .} would nest
 * and a leading {@code $} would be refused. Day buckets never expire, so an id written once is
 * read forever -- which is why {@link #categoryOf(String)} is total rather than a lookup that can
 * miss.
 *
 * <p>This lives in core because both halves need it: the public site classifies, the console
 * maps the stored ids back to categories. There is deliberately no generic {@code bot|spider}
 * fallback here; the site layers {@code SocialSourceClassifier.isBot} under this table and
 * files what only that matches as {@link #OTHER_BOT}.
 */
public final class CrawlerClassifier {

    /** The id under which automated clients this table does not name are counted. */
    public static final String OTHER_BOT = "other";

    /**
     * Who a crawler claims to be.
     *
     * @param verifyHosts reverse-DNS suffixes its operator crawls from. Empty means the operator
     *                    publishes no such scheme, and the outcome is "unknown", <b>not</b>
     *                    "unverified" -- see {@code CrawlerVerification}.
     */
    public record Identity(String bot, CrawlerCategory category, List<String> verifyHosts) {

        public Identity {
            verifyHosts = verifyHosts == null ? List.of() : List.copyOf(verifyHosts);
        }

        public boolean isVerifiable() {
            return !verifyHosts.isEmpty();
        }
    }

    private record Rule(String match, Identity identity) {
    }

    private static final Identity OTHER = new Identity(OTHER_BOT, CrawlerCategory.OTHER, List.of());

    // Google verifies from *.googlebot.com; AdsBot, Google-Extended and the other fetchers come
    // from *.google.com instead, so both suffixes are accepted for every Google property.
    private static final List<String> GOOGLE = List.of("googlebot.com", "google.com");
    private static final List<String> BING = List.of("search.msn.com");
    private static final List<String> YANDEX = List.of("yandex.ru", "yandex.net", "yandex.com");
    private static final List<String> BAIDU = List.of("baidu.com", "baidu.jp");
    private static final List<String> APPLE = List.of("applebot.apple.com");

    private static final List<Rule> RULES = List.of(
            // --- Search engines ---------------------------------------------------------------
            rule("googlebot-image", "googlebot-image", CrawlerCategory.SEARCH, GOOGLE),
            rule("googlebot-news", "googlebot-news", CrawlerCategory.SEARCH, GOOGLE),
            rule("googlebot-video", "googlebot-video", CrawlerCategory.SEARCH, GOOGLE),
            rule("adsbot-google-mobile", "adsbot-google-mobile", CrawlerCategory.SEARCH, GOOGLE),
            rule("adsbot-google", "adsbot-google", CrawlerCategory.SEARCH, GOOGLE),
            rule("storebot-google", "storebot-google", CrawlerCategory.SEARCH, GOOGLE),
            rule("google-inspectiontool", "google-inspectiontool", CrawlerCategory.SEARCH, GOOGLE),
            rule("googleother", "googleother", CrawlerCategory.SEARCH, GOOGLE),
            rule("googlebot", "googlebot", CrawlerCategory.SEARCH, GOOGLE),

            rule("bingpreview", "bingpreview", CrawlerCategory.SEARCH, BING),
            rule("adidxbot", "adidxbot", CrawlerCategory.SEARCH, BING),
            rule("bingbot", "bingbot", CrawlerCategory.SEARCH, BING),

            rule("yandexbot", "yandexbot", CrawlerCategory.SEARCH, YANDEX),
            rule("yandeximages", "yandeximages", CrawlerCategory.SEARCH, YANDEX),
            rule("duckduckbot", "duckduckbot", CrawlerCategory.SEARCH, null),
            rule("duckduckgo-favicons-bot", "duckduckbot", CrawlerCategory.SEARCH, null),
            rule("baiduspider", "baiduspider", CrawlerCategory.SEARCH, BAIDU),
            // Applebot-Extended and Google-Extended are, by their operators' documentation,
            // robots.txt tokens rather than User-Agents anything sends. Kept so that if either ever
            // does appear it lands in the right category rather than under its parent.
            rule("applebot-extended", "applebot-extended", CrawlerCategory.AI, APPLE),
            rule("applebot", "applebot", CrawlerCategory.SEARCH, APPLE),
            rule("slurp", "yahoo-slurp", CrawlerCategory.SEARCH, null),
            rule("seznambot", "seznambot", CrawlerCategory.SEARCH, null),
            rule("petalbot", "petalbot", CrawlerCategory.SEARCH, null),
            rule("yeti/", "naverbot", CrawlerCategory.SEARCH, null),

            // --- AI / LLM crawlers -----------------------------------------------------------------
            rule("oai-searchbot", "oai-searchbot", CrawlerCategory.AI, null),
            rule("chatgpt-user", "chatgpt-user", CrawlerCategory.AI, null),
            rule("gptbot", "gptbot", CrawlerCategory.AI, null),
            rule("claudebot", "claudebot", CrawlerCategory.AI, null),
            rule("claude-web", "claude-web", CrawlerCategory.AI, null),
            rule("anthropic-ai", "anthropic-ai", CrawlerCategory.AI, null),
            rule("perplexitybot", "perplexitybot", CrawlerCategory.AI, null),
            rule("perplexity-user", "perplexity-user", CrawlerCategory.AI, null),
            rule("google-extended", "google-extended", CrawlerCategory.AI, GOOGLE),
            rule("ccbot", "ccbot", CrawlerCategory.AI, null),
            rule("bytespider", "bytespider", CrawlerCategory.AI, null),
            rule("amazonbot", "amazonbot", CrawlerCategory.AI, null),
            rule("meta-externalagent", "meta-externalagent", CrawlerCategory.AI, null),
            rule("meta-externalfetcher", "meta-externalfetcher", CrawlerCategory.AI, null),
            rule("cohere-ai", "cohere-ai", CrawlerCategory.AI, null),
            rule("diffbot", "diffbot", CrawlerCategory.AI, null),
            rule("timpibot", "timpibot", CrawlerCategory.AI, null),
            rule("youbot", "youbot", CrawlerCategory.AI, null),

            // --- SEO / backlink tools ----------------------------------------------------------------
            rule("ahrefsbot", "ahrefsbot", CrawlerCategory.SEO, null),
            rule("ahrefssiteaudit", "ahrefs-site-audit", CrawlerCategory.SEO, null),
            rule("semrushbot", "semrushbot", CrawlerCategory.SEO, null),
            rule("mj12bot", "mj12bot", CrawlerCategory.SEO, null),
            rule("dotbot", "dotbot", CrawlerCategory.SEO, null),
            rule("rogerbot", "rogerbot", CrawlerCategory.SEO, null),
            rule("dataforseobot", "dataforseobot", CrawlerCategory.SEO, null),
            rule("screaming frog", "screaming-frog", CrawlerCategory.SEO, null),
            rule("blexbot", "blexbot", CrawlerCategory.SEO, null),
            rule("serpstatbot", "serpstatbot", CrawlerCategory.SEO, null),
            rule("sitecheckerbotcrawler", "sitechecker", CrawlerCategory.SEO, null),

            // --- Social / link-preview fetchers ------------------------------------------------------
            // These read the Open Graph tags, so this category doubles as evidence that the share
            // previews are actually being requested.
            rule("facebookexternalhit", "facebook", CrawlerCategory.SOCIAL, null),
            rule("facebookcatalog", "facebook", CrawlerCategory.SOCIAL, null),
            rule("meta-externalhit", "facebook", CrawlerCategory.SOCIAL, null),
            rule("whatsapp/", "whatsapp", CrawlerCategory.SOCIAL, null),
            rule("twitterbot", "twitterbot", CrawlerCategory.SOCIAL, null),
            rule("linkedinbot", "linkedinbot", CrawlerCategory.SOCIAL, null),
            rule("slackbot", "slackbot", CrawlerCategory.SOCIAL, null),
            rule("telegrambot", "telegrambot", CrawlerCategory.SOCIAL, null),
            rule("discordbot", "discordbot", CrawlerCategory.SOCIAL, null),
            rule("pinterestbot", "pinterestbot", CrawlerCategory.SOCIAL, null),
            rule("pinterest.com/bot", "pinterestbot", CrawlerCategory.SOCIAL, null),
            rule("redditbot", "redditbot", CrawlerCategory.SOCIAL, null),
            rule("embedly", "embedly", CrawlerCategory.SOCIAL, null),
            rule("skypeuripreview", "skype-preview", CrawlerCategory.SOCIAL, null)
    );

    private static final Map<String, CrawlerCategory> CATEGORY_BY_BOT;

    static {
        Map<String, CrawlerCategory> byBot = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            byBot.putIfAbsent(rule.identity().bot(), rule.identity().category());
        }
        byBot.put(OTHER_BOT, CrawlerCategory.OTHER);
        CATEGORY_BY_BOT = Map.copyOf(byBot);
    }

    private CrawlerClassifier() {
    }

    private static Rule rule(String match, String bot, CrawlerCategory category, List<String> verifyHosts) {
        return new Rule(match, new Identity(bot, category, verifyHosts));
    }

    /** The crawler this User-Agent names, or {@code null} for anything the table does not know. */
    public static Identity classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String needle = userAgent.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            if (needle.contains(rule.match())) {
                return rule.identity();
            }
        }
        return null;
    }

    /** The identity automated clients the table does not name are filed under. */
    public static Identity other() {
        return OTHER;
    }

    /**
     * The category a stored bot id belongs to. <b>Total</b>: {@link #OTHER_BOT}, an id since
     * removed from the table, or anything else unrecognised is {@link CrawlerCategory#OTHER} --
     * never {@code null}, because a twelve-month range reads day buckets written under every
     * version of this table there has ever been.
     */
    public static CrawlerCategory categoryOf(String bot) {
        if (bot == null) {
            return CrawlerCategory.OTHER;
        }
        return CATEGORY_BY_BOT.getOrDefault(bot, CrawlerCategory.OTHER);
    }

    /** Every bot id the table can produce, sorted, without {@link #OTHER_BOT}. */
    public static List<String> knownBots() {
        TreeSet<String> bots = new TreeSet<>();
        for (Rule rule : RULES) {
            bots.add(rule.identity().bot());
        }
        return new ArrayList<>(bots);
    }
}
