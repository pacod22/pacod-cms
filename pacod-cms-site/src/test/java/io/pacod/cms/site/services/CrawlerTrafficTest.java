package io.pacod.cms.site.services;

import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.util.CrawlerClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static io.pacod.cms.site.services.SocialSourceClassifierTest.CHROME;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.CUBOT;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.FACEBOOK_ANDROID;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.FACEBOOK_IOS;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.INSTAGRAM_ANDROID;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.INSTAGRAM_IOS;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.LINKEDIN_IOS;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.MESSENGER_ANDROID;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.MESSENGER_IOS;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.SAFARI;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.THREADS_IOS;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.TIKTOK_ANDROID;
import static io.pacod.cms.site.services.SocialSourceClassifierTest.TIKTOK_IOS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site's one definition of "not a reader": the crawler table first, {@code isBot} under it.
 */
class CrawlerTrafficTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * The strings {@code SocialSourceClassifierTest} already holds for every in-app browser
     * {@code /social} exists for, and a Cubot handset. A crawler is not a reader, so a table entry
     * matching any of these would silently stop counting real people -- in the
     * advertising numbers as well as the views.
     */
    @Test
    @DisplayName("no real browser, in-app or otherwise, is classified as automated")
    void browsersAreReaders() {
        for (String ua : List.of(INSTAGRAM_IOS, INSTAGRAM_ANDROID, FACEBOOK_IOS, FACEBOOK_ANDROID,
                MESSENGER_ANDROID, MESSENGER_IOS, TIKTOK_IOS, TIKTOK_ANDROID, THREADS_IOS, LINKEDIN_IOS,
                SAFARI, CHROME, CUBOT)) {
            assertThat(CrawlerTraffic.classify(ua)).as(ua).isNull();
        }
    }

    @Test
    @DisplayName("a named crawler keeps its own identity")
    void namedCrawlersKeepTheirName() {
        assertThat(CrawlerTraffic.classify("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .bot()).isEqualTo("googlebot");
    }

    @Test
    @DisplayName("scripts, unnamed bots and an empty User-Agent are filed as other")
    void unnamedAutomationIsOther() {
        for (String ua : new String[]{"curl/8.7.1", "python-requests/2.32.3", "Go-http-client/2.0",
                "SomeNewBot/1.0 (+https://example.com)", "", null}) {
            CrawlerClassifier.Identity identity = CrawlerTraffic.classify(ua);
            assertThat(identity).as(String.valueOf(ua)).isNotNull();
            assertThat(identity.bot()).isEqualTo(CrawlerClassifier.OTHER_BOT);
            assertThat(identity.category()).isEqualTo(CrawlerCategory.OTHER);
        }
    }

    @Test
    @DisplayName("only the filter's mark makes a request automated -- a missing one is a reader")
    void onlyTheMarkCounts() {
        MockHttpServletRequest unmarked = new MockHttpServletRequest();
        unmarked.addHeader("User-Agent", "curl/8.7.1");
        assertThat(CrawlerTraffic.isAutomated(unmarked)).isFalse();
        assertThat(CrawlerTraffic.isAutomated(null)).isFalse();

        MockHttpServletRequest marked = new MockHttpServletRequest();
        marked.setAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE, CrawlerClassifier.other());
        assertThat(CrawlerTraffic.isAutomated(marked)).isTrue();
    }

    @Test
    @DisplayName("off a request thread, and on an unmarked request, the current request is a reader")
    void currentRequest() {
        assertThat(CrawlerTraffic.currentIsAutomated()).isFalse();

        MockHttpServletRequest marked = new MockHttpServletRequest();
        marked.setAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE, CrawlerClassifier.other());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(marked));
        assertThat(CrawlerTraffic.currentIsAutomated()).isTrue();

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(CrawlerTraffic.currentIsAutomated()).isFalse();
    }
}
