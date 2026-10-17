package io.pacod.cms.core.seed;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.repository.LanguageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Seeds a new website's languages from {@code seed/languages.json}, and only while the collection
 * is empty.
 *
 * <p>Without them the studio would have no languages to offer and the public switcher would be
 * empty -- the pages would still render, because {@code LanguageChain} resolves against codes
 * rather than against this collection, but nobody could reach a translation.
 *
 * <p>A seed step: run by {@code SiteProvisioner} with the new website bound.
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(10)
public class LanguageBootstrap implements SiteSeedStep {

    @Override
    public String name() {
        return "languages";
    }


    private static final String SEED = "seed/languages.json";

    private final LanguageRepository languageRepository;
    private final ObjectMapper objectMapper;

    public LanguageBootstrap(LanguageRepository languageRepository, ObjectMapper objectMapper) {
        this.languageRepository = languageRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void seed(Website website) {
        try {
            if (languageRepository.count() > 0) {
                log.debug("Languages already present; not seeding");
                return;
            }
        } catch (DataAccessException e) {
            log.error("Could not check the language collection; skipping the seed: {}", e.getMessage());
            return;
        }

        List<Language> seeds;
        try (InputStream in = new ClassPathResource(SEED).getInputStream()) {
            // The seed shape IS the document here -- unlike pages, which key their texts by
            // language and need a translation step. A separate Seed class would be two
            // definitions of one thing.
            seeds = objectMapper.readerForListOf(Language.class).readValue(in);
        } catch (IOException | RuntimeException e) {
            // RuntimeException as well as IOException: Boot 4.1 ships Jackson 3 (tools.jackson.*),
            // whose parse failures are UNCHECKED -- catching IOException alone compiles and then
            // lets a malformed seed abort startup.
            log.error("Could not read {}; the console will offer no languages: {}", SEED, e.getMessage());
            return;
        }

        int written = 0;
        for (Language language : seeds) {
            try {
                languageRepository.save(language);
                written++;
            } catch (DataAccessException e) {
                log.error("Could not seed language '{}': {}", language.getCode(), e.getMessage());
            }
        }

        log.info("Seeded {} language(s) from {}", written, SEED);
    }
}
