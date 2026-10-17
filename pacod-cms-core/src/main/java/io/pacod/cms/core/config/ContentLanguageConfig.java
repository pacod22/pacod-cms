package io.pacod.cms.core.config;

import io.pacod.cms.core.data.LanguageChain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Sets the language the website's content is written in, {@link LanguageChain#original()}.
 *
 * <p>Read once, at startup, from {@code pacod.content.default-language}; the default is English.
 * An unusable value fails the start rather than quietly resolving every page against a language
 * nothing is written in.
 */
@Slf4j
@Configuration
public class ContentLanguageConfig {

    public ContentLanguageConfig(@Value("${pacod.content.default-language:en}") String language) {
        LanguageChain.useOriginal(language);
        log.info("Content default language: {}", LanguageChain.original());
    }
}
