package io.pacod.cms.studio.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MgmtAssetsConfiguration implements WebMvcConfigurer {

    /**
     * Serves the console's own CSS and JavaScript from the jar.
     *
     * <p>An explicit handler at a prefix of its own, so these two files have exactly one owner and
     * are never confused with a website's {@code /assets/**}, which {@code StudioMediaController}
     * answers (raster images only) for the screens' previews.
     *
     * <p>The prefix is {@code /mgmt-assets/} rather than {@code /mgmt/assets/} on purpose: it
     * falls outside {@code MgmtSecurityConfig}'s {@code /mgmt/**} matcher and therefore outside
     * the console's authentication, which is what lets the <b>sign-in page style itself</b>.
     * These are two static files with no secrets in them. It also keeps them clear of
     * {@code MgmtHomeController}'s {@code /mgmt/**} catch-all, which would otherwise shadow the
     * resource handler.
     *
     * <p>The studio has a host of its own, which Caddy sends here whole, so no separate proxy
     * location is needed; {@code make smoke} still probes {@code mgmt.css} and {@code tinymce.min.js},
     * because a status code on {@code /mgmt/login} cannot see an unstyled page.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/mgmt-assets/**")
                .addResourceLocations("classpath:/static/mgmt-assets/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // None: MgmtHomeController owns "/mgmt/", and errors go through Spring Boot's default
        // error handling. An "/error" view controller naming "/error.html" would be turned by
        // Thymeleaf's configured suffix into "templates//error.html.html" -- a template that does
        // not exist, so the mapping would only ever produce a resolution failure.
    }
}
