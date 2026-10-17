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
}
