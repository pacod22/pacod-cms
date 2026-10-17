package io.pacod.cms.core.seed;

import tools.jackson.databind.ObjectMapper;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.repository.PageRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the standing pages from {@code seed/pages.json}, and only while the collection is empty.
 *
 * <p>Without it a new website would have no about, contact or legal pages, and the seeded menus
 * that link them would lead nowhere. Guarded on the collection being empty, so it writes once and
 * never overwrites an operator's edit. Run by {@code SiteProvisioner} when a website is created
 * (and again when it is re-provisioned, which writes nothing while the website has pages).
 *
 * <h2>The content is a resource, not Java</h2>
 * Pages of HTML in several languages are content, and content in a string literal is content
 * nobody will ever edit. It is also why this is JSON on the classpath rather than a migration
 * script: the seed has to survive being read by a person deciding what to translate next.
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(20)
public class PageBootstrap implements SiteSeedStep {

    @Override
    public String name() {
        return "standing pages";
    }


    private static final String SEED = "seed/pages.json";

    private final PageRepository pageRepository;
    private final ObjectMapper objectMapper;

    public PageBootstrap(PageRepository pageRepository, ObjectMapper objectMapper) {
        this.pageRepository = pageRepository;
        this.objectMapper = objectMapper;
    }

    /** Only while the website has no pages: see {@link SiteSeedStep}. */
    @Override
    public void seed(Website website) {
        try {
            if (pageRepository.count() > 0) {
                log.debug("Standing pages already present; not seeding");
                return;
            }
        } catch (DataAccessException e) {
            // A seed is not worth failing startup for, and the site renders without these -- the
            // footer links simply 404 until the database is reachable again.
            log.error("Could not check the page collection; skipping the seed: {}", e.getMessage());
            return;
        }

        List<Seed> seeds;
        try (InputStream in = new ClassPathResource(SEED).getInputStream()) {
            seeds = objectMapper.readerForListOf(Seed.class).readValue(in);
        } catch (IOException | RuntimeException e) {
            // RuntimeException as well as IOException: Spring Boot 4.1 ships Jackson 3
            // (tools.jackson.*), whose parse failures are UNCHECKED -- catching IOException alone
            // compiles and then lets a malformed seed abort startup.
            log.error("Could not read {}; the standing pages will be missing: {}", SEED, e.getMessage());
            return;
        }

        int written = 0;
        for (Seed seed : seeds) {
            try {
                pageRepository.save(toPage(seed));
                written++;
            } catch (DataAccessException e) {
                // One bad page is not a reason to lose the others.
                log.error("Could not seed page '{}': {}", seed.getSlug(), e.getMessage());
            }
        }

        log.info("Seeded {} standing page(s) from {}", written, SEED);
    }

    private Page toPage(Seed seed) {
        Page page = new Page(seed.getSlug());
        page.setSortOrder(seed.getSortOrder());
        page.setPublished(seed.isPublished());
        page.setOfficialInDefaultLanguageOnly(seed.isOfficialInDefaultLanguageOnly());

        // put() drops a blank value rather than storing "", which is what keeps "not translated"
        // distinguishable from "deliberately empty" everywhere downstream.
        seed.getTitles().forEach(page::putTitle);
        seed.getBodies().forEach(page::putBody);
        seed.getDescriptions().forEach(page::putDescription);

        LocalDateTime now = LocalDateTime.now();
        page.setCreatedOn(now);
        page.setLastModifiedOn(now);
        return page;
    }

    /** The JSON shape. Language-keyed maps here, {@code TextItem} lists in the document. */
    @Data
    public static class Seed {
        private String slug;
        private Integer sortOrder = 0;
        private boolean published = true;
        private boolean officialInDefaultLanguageOnly;
        private Map<String, String> titles = new LinkedHashMap<>();
        private Map<String, String> bodies = new LinkedHashMap<>();
        private Map<String, String> descriptions = new LinkedHashMap<>();
    }
}
