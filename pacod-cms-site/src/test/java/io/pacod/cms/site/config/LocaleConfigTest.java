package io.pacod.cms.site.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.Cookie;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The chrome's language follows the content's, and the default is the website's own. */
class LocaleConfigTest {

    private final LocaleConfig config = new LocaleConfig();

    @Test
    @DisplayName("after the handler, the request's locale is the model's lang -- whatever the cookie says")
    void chromeFollowsTheContentLanguage() throws Exception {
        LocaleResolver resolver = config.localeResolver();
        HandlerInterceptor interceptor = config.chromeFollowsContent();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/de/article/5");
        request.setCookies(new Cookie("lang", "en"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView mav = new ModelAndView("article");
        mav.addObject("lang", "de");

        interceptor.postHandle(request, response, new Object(), mav);

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.GERMAN);
        // The stored preference is not rewritten by a prefixed URL.
        assertThat(response.getCookie("lang")).isNull();
    }

    @Test
    @DisplayName("a hostile or missing lang leaves the locale alone")
    void ignoresUnusableLanguages() throws Exception {
        LocaleResolver resolver = config.localeResolver();
        HandlerInterceptor interceptor = config.chromeFollowsContent();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        ModelAndView mav = new ModelAndView("landing");
        mav.addObject("lang", "\"><script>");
        interceptor.postHandle(request, new MockHttpServletResponse(), new Object(), mav);
        interceptor.postHandle(request, new MockHttpServletResponse(), new Object(), null);

        // No cookie, no usable lang: the website's own language -- Bulgarian under these tests.
        assertThat(resolver.resolveLocale(request).getLanguage()).isEqualTo("bg");
    }

    /** The registry's interceptors, which only a subclass may read. */
    private static final class Registry extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
        java.util.List<Object> all() {
            return getInterceptors();
        }
    }

    /**
     * {@code ?lang} on a feed picks the feed's language; the interceptor that would also store it in
     * a cookie -- on a response every cache may hand to everyone -- is kept off the feeds' paths,
     * and only theirs.
     */
    @Test
    @DisplayName("the ?lang interceptor, which writes the cookie, runs on every page but the feeds")
    void feedsSetNoLanguageCookie() {
        Registry registry = new Registry();
        config.addInterceptors(registry);

        for (String feed : java.util.List.of("/feed.xml", "/list/9/feed.xml")) {
            assertThat(changesLanguageOn(registry, feed)).as(feed).isFalse();
        }
        for (String page : java.util.List.of("/", "/article/5", "/de/list/9", "/list/9", "/sitemap.xml", "/p/about")) {
            assertThat(changesLanguageOn(registry, page)).as(page).isTrue();
        }
    }

    /** Whether a {@code LocaleChangeInterceptor} runs on {@code path}: everywhere, unless it is mapped. */
    private static boolean changesLanguageOn(Registry registry, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        org.springframework.web.util.ServletRequestPathUtils.parseAndCache(request);
        return registry.all().stream().anyMatch(interceptor -> switch (interceptor) {
            case org.springframework.web.servlet.i18n.LocaleChangeInterceptor any -> true;
            case org.springframework.web.servlet.handler.MappedInterceptor mapped ->
                    mapped.getInterceptor() instanceof org.springframework.web.servlet.i18n.LocaleChangeInterceptor
                            && mapped.matches(request);
            default -> false;
        });
    }
}
