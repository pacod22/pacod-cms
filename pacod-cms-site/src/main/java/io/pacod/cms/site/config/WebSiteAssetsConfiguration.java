package io.pacod.cms.site.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The public site's static paths.
 *
 * <p>One registration: {@code /site-assets/**} -&gt; the jar, the site's own stylesheet and
 * script, the same for every website.
 *
 * <p>The prefix is deliberately <b>not</b> {@code /assets/}: that is a website's own files, served
 * per website out of its storage folder by {@code MediaController}. Sharing the prefix would make
 * a website's upload able to shadow the platform's stylesheet.
 *
 * <p>The studio's {@code /mgmt-assets/**} is registered in the studio's own application: the two
 * serve unrelated surfaces, and nothing here should acquire a {@code /mgmt-assets/**}
 * handler.
 */
@Configuration
public class WebSiteAssetsConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/site-assets/**")
                .addResourceLocations("classpath:/static/site-assets/");
    }
}
