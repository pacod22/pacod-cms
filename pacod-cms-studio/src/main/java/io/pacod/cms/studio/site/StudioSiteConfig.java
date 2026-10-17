package io.pacod.cms.studio.site;

import io.pacod.cms.core.tenant.TenantDirectory;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link StudioSiteFilter}.
 *
 * <p>At -120: after {@code ForwardedHeaderFilter} (highest precedence), whose view of the host and
 * scheme the Referer check needs, and before Spring Security's chain (-100), whose matchers must
 * already see the path within the website. Registered by hand rather than as a {@code @Component},
 * which Boot would register on its own at the lowest precedence -- after security, where every
 * matcher would compare against {@code /sites/<id>/mgmt/…} and none would match.
 *
 * <p>{@code ERROR} as well as {@code REQUEST}: see the filter for why the error dispatch needs it.
 */
@Configuration
public class StudioSiteConfig {

    public static final int ORDER = -120;

    @Bean
    public FilterRegistrationBean<StudioSiteFilter> studioSiteFilter(TenantDirectory directory) {
        FilterRegistrationBean<StudioSiteFilter> registration =
                new FilterRegistrationBean<>(new StudioSiteFilter(directory));
        registration.setOrder(ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
