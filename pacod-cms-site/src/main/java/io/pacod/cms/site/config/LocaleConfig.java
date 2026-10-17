package io.pacod.cms.site.config;

import io.pacod.cms.core.data.LanguageChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;

/**
 * Which language the reader is in.
 *
 * <p>Two facts, kept in step. The <em>content</em> language is what each controller resolves --
 * path prefix, then {@code ?lang}, then the {@code lang} cookie -- and puts in the model as
 * {@code lang}. The <em>chrome</em> ({@code #{…}} messages) is read through the request's
 * {@code Locale}. {@link #chromeFollowsContent()} makes the second the first after the handler
 * has run, so {@code /de/article/5} with an English cookie renders German chrome around German
 * content rather than English chrome around it.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("lang");
        // The website's own language when the reader has said nothing -- read per request, since
        // it is configuration, not a constant.
        resolver.setDefaultLocaleFunction(request -> Locale.forLanguageTag(LanguageChain.original()));
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookieHttpOnly(true);
        resolver.setCookiePath("/");
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
        lci.setParamName("lang");
        return lci;
    }

    /**
     * After the handler, the request's locale becomes the model's {@code lang}. Only the request
     * attribute is set -- no cookie is written -- so a prefixed URL still leaves the reader's
     * stored preference alone.
     */
    @Bean
    public HandlerInterceptor chromeFollowsContent() {
        return new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                                   ModelAndView modelAndView) {
                if (modelAndView == null) {
                    return;
                }
                Object lang = modelAndView.getModel().get("lang");
                String code = lang instanceof String s ? LanguageChain.normalise(s) : null;
                if (code != null && code.matches("[a-z]{2,3}")) {
                    request.setAttribute(CookieLocaleResolver.LOCALE_REQUEST_ATTRIBUTE_NAME,
                            Locale.forLanguageTag(code));
                }
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(chromeFollowsContent());
    }
}
