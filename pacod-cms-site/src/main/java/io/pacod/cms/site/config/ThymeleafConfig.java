package io.pacod.cms.site.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The public site's chrome messages, {@code messages*.properties}, which templates read as
 * {@code #{key}}.
 */
@Configuration
public class ThymeleafConfig {

    @Bean
    public ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        // A language with no bundle falls back to the English base -- never to whatever bundle
        // matches the JVM's own locale, which would make the chrome a property of the machine.
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
