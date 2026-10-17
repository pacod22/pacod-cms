package io.pacod.cms.studio.config;

import io.pacod.cms.studio.MgmtPresence;
import io.pacod.cms.studio.i18n.MgmtLocaleResolver;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.pacod.cms.studio.access.IdentityGuardInterceptor;
import io.pacod.cms.studio.access.PermissionInterceptor;
import io.pacod.cms.studio.identity.AuthzFeed;
import io.pacod.cms.studio.identity.IdProperties;
import org.springframework.beans.factory.ObjectProvider;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.site.StudioSites;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The console's languages. Both beans are named for what Spring looks them up by: the
 * DispatcherServlet finds {@code localeResolver} by name, and Boot's MessageSource and validation
 * auto-configuration back off for a bean called {@code messageSource}.
 *
 * <p>The MessageSource is declared here rather than through {@code spring.messages.*} so that
 * {@link MgmtText#newMessageSource()} is its only definition -- the English text behind every
 * exception's {@code getMessage()} is built by the same method and cannot disagree with it.
 *
 * <p>{@link MgmtText} reaches the templates as the request attribute {@code mgmtText}, set by an
 * interceptor on every handler -- not as {@code ${@mgmtText...}}. Thymeleaf 3.1 evaluates most
 * attribute expressions in restricted mode, where a bean reference counts as external access and
 * the template fails to parse; and not as a model attribute from {@code MgmtControllerAdvice},
 * which is scoped to the controller package and would miss the error pages.
 *
 * <p>The bundles live in this module only. Core is scanned by the public site too, which has no
 * business resolving console text.
 */
@Configuration
public class MgmtLocaleConfig implements WebMvcConfigurer {

    public static final String TEXT_ATTRIBUTE = "mgmtText";

    /** The website a screen is editing, or null on a platform screen. See {@link StudioSites}. */
    public static final String SITE_ATTRIBUTE = "currentSite";

    /** What the chrome may offer the signed-in person: {@link io.pacod.cms.studio.access.StudioCan}. */
    public static final String CAN_ATTRIBUTE = "can";

    private final MgmtText mgmtText;
    private final MgmtPresence presence;
    private final StudioSites sites;

    private final StudioAccess access;
    private final ObjectProvider<AuthzFeed> feed;
    private final IdProperties id;

    public MgmtLocaleConfig(@Lazy MgmtText mgmtText, MgmtPresence presence, @Lazy StudioSites sites,
                            @Lazy StudioAccess access, ObjectProvider<AuthzFeed> feed, IdProperties id) {
        this.mgmtText = mgmtText;
        this.presence = presence;
        this.sites = sites;
        this.access = access;
        this.feed = feed;
        this.id = id;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                request.setAttribute(TEXT_ATTRIBUTE, mgmtText);
                request.setAttribute(CAN_ATTRIBUTE, new io.pacod.cms.studio.access.StudioCan(access));
                return true;
            }

            /**
             * Only for a response that renders a view -- the chrome is what reads it -- so the
             * stylesheet and script requests a screen makes do not each cost a control-plane read.
             */
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                                   ModelAndView modelAndView) {
                if (modelAndView != null && modelAndView.hasView() && !modelAndView.wasCleared()
                        && request.getAttribute(SITE_ATTRIBUTE) == null) {
                    sites.current().ifPresent(site -> request.setAttribute(SITE_ATTRIBUTE, site));
                }
            }
        });
        // Here rather than in a configurer of its own only because this is where the console's
        // interceptors are registered; see MgmtPresenceInterceptor for what it skips and why.
        registry.addInterceptor(new MgmtPresenceInterceptor(presence));
        // Before the permission check: a person pacod-id signed out holds no permission to check,
        // and a copy too old to trust decides nothing.
        registry.addInterceptor(new IdentityGuardInterceptor(feed, id));
        // Last: by the time it runs, the website is bound and the text resolver is in place for
        // the error page a refusal renders.
        registry.addInterceptor(new PermissionInterceptor(access));
    }

    @Bean
    public LocaleResolver localeResolver(
            @org.springframework.beans.factory.annotation.Value("${pacod.studio.default-language:en}") String defaultLanguage) {
        // Configuration, so it is checked here: a language the studio is not offered in fails the
        // start rather than quietly rendering the base language under another's <html lang>.
        MgmtText.useDefaultLanguage(defaultLanguage);
        return new MgmtLocaleResolver();
    }

    @Bean
    public MessageSource messageSource() {
        return MgmtText.newMessageSource();
    }
}
