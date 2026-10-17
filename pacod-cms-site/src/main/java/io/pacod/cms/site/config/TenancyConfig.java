package io.pacod.cms.site.config;

import io.pacod.cms.core.tenant.TenantDirectory;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link HostSiteResolverFilter}: ahead of Spring Security (-100) and
 * {@code CrawlerTrafficFilter} ({@value CrawlerTrafficConfig#ORDER}), behind only the forwarded-header
 * filter, and on the REQUEST, ERROR and ASYNC dispatches.
 */
@Configuration
public class TenancyConfig {

    static final int ORDER = -120;

    @Bean
    public FilterRegistrationBean<HostSiteResolverFilter> hostSiteResolverFilter(TenantDirectory directory) {
        FilterRegistrationBean<HostSiteResolverFilter> registration =
                new FilterRegistrationBean<>(new HostSiteResolverFilter(directory));
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        registration.setName("hostSiteResolverFilter");
        return registration;
    }
}
