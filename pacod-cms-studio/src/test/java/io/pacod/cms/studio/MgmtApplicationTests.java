package io.pacod.cms.studio;

import io.pacod.cms.studio.site.StudioSiteConfig;
import io.pacod.cms.studio.site.StudioSiteFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.stereotype.Controller;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the console's application context wires up, and pins the three things the split could
 * silently undo.
 *
 * <p>The mirror of {@code WebApplicationTests} in the site module. Every assertion here exists
 * because the corresponding failure produces a running application that looks fine.
 *
 * <p>No MongoDB is required: the driver connects lazily, and both {@code ApplicationReadyEvent}
 * bootstraps are off in the test profile.
 */
@SpringBootTest
@ActiveProfiles("test")
class MgmtApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the application context loads")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("the studio keeps no accounts: sign-in is pacod-id's, with PKCE")
    void signInIsPacodIds() {
        // No UserDetailsService at all: Boot would otherwise invent an in-memory user and log its
        // password at startup.
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();

        var registration = context.getBean(org.springframework.security.oauth2.client.registration
                .ClientRegistrationRepository.class).findByRegistrationId("pacod-id");
        assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
        // Absolute, from configuration -- never {baseUrl}, which inside a website carries its prefix.
        assertThat(registration.getRedirectUri()).isEqualTo("http://studio.test/mgmt/oauth2/code/pacod-id");
        // Browser endpoints on the public issuer, server-to-server ones on the internal listener.
        assertThat(registration.getProviderDetails().getAuthorizationUri()).isEqualTo("http://id.test/oidc/authorize");
        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("http://id-internal.test/oidc/token");
        assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo("http://id-internal.test/oidc/jwks");
        assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri()).isNull();
    }

    @Test
    @DisplayName("exactly two security chains")
    void securityChainsArePresent() {
        List<SecurityFilterChain> chains = context.getBeansOfType(SecurityFilterChain.class)
                .values().stream().toList();

        // Two: the /mgmt/** form-login chain and the permit-all chain covering /mgmt-assets/**,
        // /mgmt-health and /error.
        //
        // Exactly, not "at least". A chain with no securityMatcher arriving from a scanned
        // package would match EVERY request ahead of the permit-all one, and nothing logs a
        // warning when that happens, which is the entire reason this assertion is exact.
        assertThat(chains).hasSize(2);
    }

    @Test
    @DisplayName("every controller in the studio is the studio's own")
    void onlyStudioControllers() {
        // MgmtApplication enumerates the core packages it needs instead of naming
        // io.pacod.cms.core wholesale, so that a controller added to core cannot appear here --
        // where anything outside /mgmt/** would fall to the permit-all chain, unauthenticated.
        List<String> ours = context.getBeansWithAnnotation(Controller.class).values().stream()
                .map(bean -> org.springframework.aop.support.AopUtils.getTargetClass(bean).getName())
                .filter(name -> name.startsWith("io.pacod.cms."))
                .toList();
        assertThat(ours).isNotEmpty().allMatch(name -> name.startsWith("io.pacod.cms.studio."));
    }

    @Test
    @DisplayName("the website filter runs before Spring Security, on the error dispatch too")
    void siteFilterIsOrderedAheadOfSecurity() {
        // After it, every matcher would see /sites/<id>/mgmt/... and none would match: the
        // request would reach the permit-all chain with no website bound.
        @SuppressWarnings("unchecked")
        FilterRegistrationBean<StudioSiteFilter> registration =
                context.getBean("studioSiteFilter", FilterRegistrationBean.class);
        assertThat(registration.getOrder()).isEqualTo(StudioSiteConfig.ORDER).isLessThan(-100);
        assertThat(registration.determineDispatcherTypes())
                .contains(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ERROR);
    }

    @Test
    @DisplayName("the console has no cache manager, so a stray @CacheEvict cannot look like it works")
    void consoleDoesNotCache() {
        // Deliberate: the content caches live in the site's JVM. Adding caffeine and a
        // CacheConfig here would make an @CacheEvict resolve and do nothing useful, which is
        // worse than not having one.
        assertThat(context.getBeansOfType(CacheManager.class)).isEmpty();
    }

    @Test
    @DisplayName("CORS is defined once, by the application, with no separate CorsFilter alongside it")
    void singleApplicationCorsSource() {
        // Spring MVC's own mvcHandlerMappingIntrospector also implements
        // CorsConfigurationSource, so count only the application-defined bean.
        assertThat(context.getBeansOfType(CorsConfigurationSource.class))
                .containsKey("corsConfigurationSource");

        assertThat(context.getBeansOfType(jakarta.servlet.Filter.class).values())
                .noneMatch(f -> f.getClass().getName().endsWith("CorsFilter"));
    }
}
