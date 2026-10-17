package io.pacod.cms.site.config;

import io.pacod.cms.core.design.PreviewTokens;
import io.pacod.cms.site.render.TemplateSetViewResolver;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PreviewFilter} between {@link HostSiteResolverFilter} ({@value TenancyConfig#ORDER}),
 * whose website a token must name, and {@code CrawlerTrafficFilter}
 * ({@value CrawlerTrafficConfig#ORDER}) and Spring Security, which must find the request already
 * marked -- on REQUEST, ERROR and ASYNC, like the website's own binding.
 */
@Configuration
public class PreviewConfig {

    static final int ORDER = -115;

    @Bean
    public FilterRegistrationBean<PreviewFilter> previewFilter(PreviewTokens tokens, TemplateSetViewResolver views) {
        FilterRegistrationBean<PreviewFilter> registration = new FilterRegistrationBean<>(new PreviewFilter(tokens, views.sets()));
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        registration.setName("previewFilter");
        return registration;
    }

    /** {@link PrintFilter}, just after the preview: the same place, for the same reasons. */
    @Bean
    public FilterRegistrationBean<PrintFilter> printFilter(io.pacod.cms.core.design.PrintTokens tokens) {
        FilterRegistrationBean<PrintFilter> registration = new FilterRegistrationBean<>(new PrintFilter(tokens));
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER + 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setName("printFilter");
        return registration;
    }
}
