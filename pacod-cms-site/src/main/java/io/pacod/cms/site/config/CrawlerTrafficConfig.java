package io.pacod.cms.site.config;

import io.pacod.cms.site.services.CrawlerRecorder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CrawlerTrafficFilter} for every path, ahead of Spring Security.
 *
 * <p>The filter is deliberately not a {@code @Component}: Spring Boot would register a component
 * filter on its own, at the lowest precedence -- behind the security chain, where a request the
 * firewall refuses never reaches it. -110 puts it before {@code springSecurityFilterChain} (-100)
 * and {@code RequestContextFilter} (-105). The attribute it sets is on the request object every
 * later wrapper delegates to, so {@code RequestContextHolder} still sees it downstream.
 *
 * <p>It lives in the public site only. The studio does not scan this package, and has no public
 * traffic to classify.
 */
@Configuration
public class CrawlerTrafficConfig {

    static final int ORDER = -110;

    @Bean
    public FilterRegistrationBean<CrawlerTrafficFilter> crawlerTrafficFilter(CrawlerRecorder recorder) {
        FilterRegistrationBean<CrawlerTrafficFilter> registration =
                new FilterRegistrationBean<>(new CrawlerTrafficFilter(recorder));
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER);
        registration.setName("crawlerTrafficFilter");
        return registration;
    }
}
