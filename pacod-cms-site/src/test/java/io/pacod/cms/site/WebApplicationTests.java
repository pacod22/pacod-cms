package io.pacod.cms.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the whole application context wires up.
 *
 * <p>Worth having on its own: a broken bean graph compiles, and passes every test that builds
 * its objects by hand. It also pins down the wiring the site relies on -- the cache manager, the
 * security filter chain and the single CORS source -- and catches a bean-name collision such as
 * a configuration class called {@code CorsFilter} against Spring Security's expected
 * {@code corsFilter} bean.
 *
 * <p>No MongoDB is required: the driver connects lazily, and index creation is switched off
 * for tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the application context loads")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("caching is enabled with the expected caches")
    void cachingIsConfigured() {
        CacheManager cacheManager = context.getBean(CacheManager.class);

        assertThat(cacheManager.getCache("articles")).isNotNull();
        assertThat(cacheManager.getCache("tags")).isNotNull();
        assertThat(cacheManager.getCache("categories")).isNotNull();
        // Article-list sections. Nothing evicts these -- the studio is another process -- so the
        // 60s TTL in CacheConfig is the whole staleness bound.
        assertThat(cacheManager.getCache("widgets")).isNotNull();
        // /social's page document and latest-articles block, on the same 60s bound.
        assertThat(cacheManager.getCache("social")).isNotNull();
    }

    @Test
    @DisplayName("the public site has nothing to authenticate against")
    void publicSiteHasNoUserDetailsService() {
        // Nobody signs in to the public site: people sign in to the studio, through pacod-id.
        //
        // Zero, not "at most one". Spring Boot's answer to a security configuration with no
        // UserDetailsService is UserDetailsServiceAutoConfiguration, which creates an in-memory
        // user and logs a generated password at startup -- on the public site, on every deploy.
        // WebApplication excludes that auto-configuration; this assertion is what stops the
        // exclusion being dropped as apparently redundant.
        //
        // It also catches a leak: a bean here means something reached this context past
        // WebApplication's narrowed component scan.
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("exactly one security chain")
    void securityChainsArePresent() {
        List<SecurityFilterChain> chains = context.getBeansOfType(SecurityFilterChain.class)
                .values().stream().toList();

        // One: the public chain. Exactly, not "at least": a chain with no securityMatcher
        // arriving from a scanned core package would match EVERY request ahead of it.
        assertThat(chains).hasSize(1);
    }

    @Test
    @DisplayName("no /v1/** API is served by the public site")
    void v1RestApiIsNotMapped() {
        // WebApplication enumerates the core packages it scans rather than scanning
        // io.pacod.cms.core whole: a controller in core would otherwise be mapped here --
        // writes included -- behind a permit-all chain, on every website's host.
        //
        // Asserted on the handler mapping rather than on a controller bean, the way
        // MgmtApplicationTests does it: a route is what a stranger can reach, and this also
        // catches a /v1 controller added to core later, or one that reaches this context some
        // other way than the component scan.
        RequestMappingHandlerMapping mapping = context.getBean(
                "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        List<String> patterns = mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .toList();

        assertThat(patterns)
                .filteredOn(pattern -> pattern.equals("/v1") || pattern.startsWith("/v1/"))
                .isEmpty();
        // Paired with a positive, or an empty mapping would pass the line above as well. The
        // site's own JSON lives under /web/v1 and must survive the narrowed scan.
        assertThat(patterns).contains("/web/v1/health");
    }

    @Test
    @DisplayName("CORS is defined once, by the application, with no CORS servlet filter beside it")
    void singleApplicationCorsSource() {
        // Spring MVC's own mvcHandlerMappingIntrospector also implements
        // CorsConfigurationSource, so count only the application-defined bean.
        assertThat(context.getBeansOfType(CorsConfigurationSource.class))
                .containsKey("corsConfigurationSource");

        // No hand-written jakarta.servlet CORS Filter beside it: having both produces
        // contradictory Access-Control-Allow-Origin headers.
        assertThat(context.getBeansOfType(jakarta.servlet.Filter.class).values())
                .noneMatch(f -> f.getClass().getName().endsWith("CorsFilter"));
    }
}
