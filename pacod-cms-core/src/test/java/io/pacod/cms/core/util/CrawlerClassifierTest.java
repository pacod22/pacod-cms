package io.pacod.cms.core.util;

import io.pacod.cms.core.data.CrawlerCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table against the real User-Agent strings each operator sends. The browser side of the
 * table -- that no in-app browser {@code /social} exists for is mistaken for a crawler -- is
 * checked in the site module's {@code CrawlerTrafficTest}, against the very strings
 * {@code SocialSourceClassifierTest} holds.
 */
class CrawlerClassifierTest {

    static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
    static final String GOOGLEBOT_IMAGE = "Googlebot-Image/1.0";
    static final String GOOGLEBOT_NEWS = "Googlebot-News";
    static final String GOOGLE_EXTENDED = "Mozilla/5.0 (compatible; Google-Extended/1.0)";
    static final String GOOGLE_INSPECTION = "Mozilla/5.0 (compatible; Google-InspectionTool/1.0;)";
    static final String ADSBOT_MOBILE = "Mozilla/5.0 (Linux; Android 6.0.1;) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/41 Mobile Safari/537.36 (compatible; AdsBot-Google-Mobile; +http://www.google.com/mobile/adsbot.html)";
    static final String BINGBOT = "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)";
    static final String YANDEX = "Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)";
    static final String DUCKDUCK = "DuckDuckBot/1.1; (+http://duckduckgo.com/duckduckbot.html)";
    static final String BAIDU = "Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)";
    static final String APPLEBOT = "Mozilla/5.0 (compatible; Applebot/0.1; +http://www.apple.com/go/applebot)";
    static final String APPLEBOT_EXTENDED = "Mozilla/5.0 (compatible; Applebot-Extended/0.1)";
    static final String NAVER_YETI = "Mozilla/5.0 (compatible; Yeti/1.1; +https://naver.me/spd)";
    static final String GPTBOT = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; GPTBot/1.1; "
            + "+https://openai.com/gptbot";
    static final String CHATGPT_USER = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; "
            + "ChatGPT-User/1.0; +https://openai.com/bot";
    static final String OAI_SEARCH = "Mozilla/5.0 AppleWebKit/537.36 (compatible; OAI-SearchBot/1.0; "
            + "+https://openai.com/searchbot)";
    static final String CLAUDEBOT = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ClaudeBot/1.0; "
            + "+claudebot@anthropic.com";
    static final String PERPLEXITY = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) PerplexityBot/1.0";
    static final String CCBOT = "CCBot/2.0 (https://commoncrawl.org/faq/)";
    static final String BYTESPIDER = "Mozilla/5.0 (compatible; Bytespider; spider-feedback@bytedance.com)";
    static final String AHREFS = "Mozilla/5.0 (compatible; AhrefsBot/7.0; +http://ahrefs.com/robot/)";
    static final String SEMRUSH = "Mozilla/5.0 (compatible; SemrushBot/7~bl; +http://www.semrush.com/bot.html)";
    static final String FACEBOOK = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    static final String WHATSAPP = "WhatsApp/2.23.20.0 A";
    static final String TWITTERBOT = "Twitterbot/1.0";
    static final String LINKEDIN = "LinkedInBot/1.0 (compatible; Mozilla/5.0; Apache-HttpClient +http://www.linkedin.com)";
    static final String SLACK = "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)";
    static final String PINTEREST_OLD = "Pinterest/0.2 (+http://www.pinterest.com/bot.html)";
    static final String CHROME = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    // In-app browsers that a bare substring (pinterest, naver) would match.
    static final String PINTEREST_APP = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Mobile/15E148 [Pinterest/iOS]";
    static final String NAVER_APP = "Mozilla/5.0 (Linux; Android 14; SM-S911N Build/UP1A.231005.007; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.0.0.0 Mobile Safari/537.36 "
            + "NAVER(inapp; search; 2000; 12.6.3)";

    @Nested
    @DisplayName("ordering traps")
    class Ordering {

        @Test
        @DisplayName("Googlebot's variants stay distinct from plain Googlebot")
        void googlebotVariants() {
            assertThat(bot(GOOGLEBOT)).isEqualTo("googlebot");
            assertThat(bot(GOOGLEBOT_IMAGE)).isEqualTo("googlebot-image");
            assertThat(bot(GOOGLEBOT_NEWS)).isEqualTo("googlebot-news");
            assertThat(bot(ADSBOT_MOBILE)).isEqualTo("adsbot-google-mobile");
            assertThat(bot(GOOGLE_INSPECTION)).isEqualTo("google-inspectiontool");
        }

        @Test
        @DisplayName("Google-Extended is an AI crawler, not Googlebot")
        void googleExtendedIsAi() {
            CrawlerClassifier.Identity identity = CrawlerClassifier.classify(GOOGLE_EXTENDED);

            assertThat(identity.bot()).isEqualTo("google-extended");
            assertThat(identity.category()).isEqualTo(CrawlerCategory.AI);
        }

        @Test
        @DisplayName("OpenAI's three agents stay three rows")
        void openAiAgentsAreSeparate() {
            assertThat(bot(GPTBOT)).isEqualTo("gptbot");
            assertThat(bot(CHATGPT_USER)).isEqualTo("chatgpt-user");
            assertThat(bot(OAI_SEARCH)).isEqualTo("oai-searchbot");
        }

        @Test
        @DisplayName("Applebot-Extended is AI, Applebot is search")
        void applebotExtendedIsAi() {
            assertThat(CrawlerClassifier.classify(APPLEBOT).category()).isEqualTo(CrawlerCategory.SEARCH);
            assertThat(CrawlerClassifier.classify(APPLEBOT_EXTENDED).category()).isEqualTo(CrawlerCategory.AI);
        }
    }

    static Stream<Arguments> categories() {
        return Stream.of(
                Arguments.of(GOOGLEBOT, CrawlerCategory.SEARCH),
                Arguments.of(BINGBOT, CrawlerCategory.SEARCH),
                Arguments.of(YANDEX, CrawlerCategory.SEARCH),
                Arguments.of(DUCKDUCK, CrawlerCategory.SEARCH),
                Arguments.of(BAIDU, CrawlerCategory.SEARCH),
                Arguments.of(NAVER_YETI, CrawlerCategory.SEARCH),
                Arguments.of(CLAUDEBOT, CrawlerCategory.AI),
                Arguments.of(PERPLEXITY, CrawlerCategory.AI),
                Arguments.of(CCBOT, CrawlerCategory.AI),
                Arguments.of(BYTESPIDER, CrawlerCategory.AI),
                Arguments.of(AHREFS, CrawlerCategory.SEO),
                Arguments.of(SEMRUSH, CrawlerCategory.SEO),
                Arguments.of(FACEBOOK, CrawlerCategory.SOCIAL),
                Arguments.of(WHATSAPP, CrawlerCategory.SOCIAL),
                Arguments.of(TWITTERBOT, CrawlerCategory.SOCIAL),
                Arguments.of(LINKEDIN, CrawlerCategory.SOCIAL),
                Arguments.of(SLACK, CrawlerCategory.SOCIAL),
                Arguments.of(PINTEREST_OLD, CrawlerCategory.SOCIAL));
    }

    @ParameterizedTest
    @MethodSource("categories")
    @DisplayName("each operator's real User-Agent lands in its category")
    void categorises(String userAgent, CrawlerCategory expected) {
        assertThat(CrawlerClassifier.classify(userAgent).category()).isEqualTo(expected);
    }

    @Test
    @DisplayName("ordinary browsers, a missing User-Agent and in-app browsers are not crawlers")
    void nonCrawlers() {
        assertThat(CrawlerClassifier.classify(CHROME)).isNull();
        assertThat(CrawlerClassifier.classify(SAFARI_IPHONE)).isNull();
        assertThat(CrawlerClassifier.classify(PINTEREST_APP)).isNull();
        assertThat(CrawlerClassifier.classify(NAVER_APP)).isNull();
        assertThat(CrawlerClassifier.classify(null)).isNull();
        assertThat(CrawlerClassifier.classify("")).isNull();
        assertThat(CrawlerClassifier.classify("   ")).isNull();
    }

    @Test
    @DisplayName("matching ignores case")
    void caseInsensitive() {
        assertThat(bot("GOOGLEBOT/2.1")).isEqualTo("googlebot");
    }

    @Test
    @DisplayName("the major search engines carry reverse-DNS suffixes")
    void majorEnginesAreVerifiable() {
        for (String ua : List.of(GOOGLEBOT, BINGBOT, YANDEX, BAIDU, APPLEBOT)) {
            assertThat(CrawlerClassifier.classify(ua).isVerifiable()).as(ua).isTrue();
        }
    }

    @Test
    @DisplayName("crawlers with no published DNS scheme carry none, so they read as unknown rather than forged")
    void unverifiableCrawlersCarryNoSuffixes() {
        for (String ua : List.of(AHREFS, SEMRUSH, CCBOT, FACEBOOK, GPTBOT)) {
            assertThat(CrawlerClassifier.classify(ua).verifyHosts()).as(ua).isEmpty();
        }
    }

    @Test
    @DisplayName("bot ids are unique, bounded and safe as dotted $inc keys")
    void botIdsAreSafeKeys() {
        List<String> bots = CrawlerClassifier.knownBots();

        assertThat(new HashSet<>(bots)).hasSameSizeAs(bots);
        assertThat(bots).hasSizeLessThan(80);
        assertThat(bots).allMatch(id -> id.matches("[a-z0-9-]+"), "matches [a-z0-9-]+");
        assertThat(bots).doesNotContain(CrawlerClassifier.OTHER_BOT);
        assertThat(CrawlerClassifier.OTHER_BOT).matches("[a-z0-9-]+");
    }

    @Test
    @DisplayName("every id maps back to the category it was classified under")
    void categoryOfAgreesWithClassify() {
        for (String ua : List.of(GOOGLEBOT, GPTBOT, AHREFS, FACEBOOK, APPLEBOT_EXTENDED)) {
            CrawlerClassifier.Identity identity = CrawlerClassifier.classify(ua);
            assertThat(CrawlerClassifier.categoryOf(identity.bot())).isEqualTo(identity.category());
        }
    }

    @Test
    @DisplayName("categoryOf is total: \"other\", a removed id and null are all OTHER, never null")
    void categoryOfIsTotal() {
        assertThat(CrawlerClassifier.categoryOf(CrawlerClassifier.OTHER_BOT)).isEqualTo(CrawlerCategory.OTHER);
        assertThat(CrawlerClassifier.categoryOf("some-bot-removed-in-2027")).isEqualTo(CrawlerCategory.OTHER);
        assertThat(CrawlerClassifier.categoryOf(null)).isEqualTo(CrawlerCategory.OTHER);
        assertThat(CrawlerClassifier.other().category()).isEqualTo(CrawlerCategory.OTHER);
    }

    private static String bot(String userAgent) {
        return CrawlerClassifier.classify(userAgent).bot();
    }
}
