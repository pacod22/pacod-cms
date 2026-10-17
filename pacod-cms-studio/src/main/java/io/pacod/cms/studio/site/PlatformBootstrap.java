package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.tenant.SiteIndexes;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.SiteRetirement;
import io.pacod.cms.studio.demo.StudioDemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Brings the platform up to date when the studio starts: the control plane's indexes, every website
 * whose database predates this release, the databases of websites deleted moments before a restart
 * ({@link SiteRetirement#sweep}), and -- on a platform with no websites at all -- the demo website,
 * so a fresh install has something to open.
 *
 * <p>In the studio because the studio is where websites are created; the public site only ever
 * serves websites that already exist. Each step fails on its own and is logged at ERROR: the
 * studio still starts, and the next start tries again.
 *
 * <p>The demo websites are created only while the {@code website} collection is empty, so deleting
 * them later does not bring them back: {@code demo} on the default template set and, in the same
 * organisation, {@code minimal} on the {@code minimal} set ({@code pacod.bootstrap.demo.minimal.*};
 * {@code enabled=false} leaves it out), so a fresh install shows both. Each starts with the demo
 * content ({@link StudioDemo}) unless {@code pacod.bootstrap.demo.content=false}, and each is created
 * on its own: one failing does not keep the other away. {@code pacod.bootstrap.enabled=false} turns all of this off, as
 * the test profile does.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pacod.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformBootstrap {

    private final SiteIndexes indexes;
    private final SiteProvisioner provisioner;
    private final SiteRetirement retirement;
    private final WebsiteRepository websites;
    private final boolean demoEnabled;
    private final String demoId;
    private final String demoName;
    private final String demoLanguage;
    private final List<String> demoHosts;
    private final boolean demoContent;
    private final StudioDemo demo;
    private final boolean minimalEnabled;
    private final String minimalId;
    private final String minimalName;
    private final List<String> minimalHosts;

    /** The template set of the second demo website. */
    static final String MINIMAL_SET = "minimal";

    public PlatformBootstrap(SiteIndexes indexes, SiteProvisioner provisioner, SiteRetirement retirement,
                             WebsiteRepository websites, StudioDemo demo,
                             @Value("${pacod.bootstrap.demo.content:true}") boolean demoContent,
                             @Value("${pacod.bootstrap.demo.enabled:true}") boolean demoEnabled,
                             @Value("${pacod.bootstrap.demo.id:demo}") String demoId,
                             @Value("${pacod.bootstrap.demo.name:Demo Journal}") String demoName,
                             @Value("${pacod.bootstrap.demo.language:en}") String demoLanguage,
                             @Value("${pacod.bootstrap.demo.hosts:demo.localhost,localhost}") String demoHosts,
                             @Value("${pacod.bootstrap.demo.minimal.enabled:true}") boolean minimalEnabled,
                             @Value("${pacod.bootstrap.demo.minimal.id:minimal}") String minimalId,
                             @Value("${pacod.bootstrap.demo.minimal.name:Demo Notes}") String minimalName,
                             @Value("${pacod.bootstrap.demo.minimal.hosts:minimal.localhost}") String minimalHosts) {
        this.indexes = indexes;
        this.provisioner = provisioner;
        this.retirement = retirement;
        this.websites = websites;
        this.demoEnabled = demoEnabled;
        this.demoId = demoId;
        this.demoName = demoName;
        this.demoLanguage = demoLanguage;
        this.demoHosts = hosts(demoHosts);
        this.demo = demo;
        this.demoContent = demoContent;
        this.minimalEnabled = minimalEnabled;
        this.minimalId = minimalId;
        this.minimalName = minimalName;
        this.minimalHosts = hosts(minimalHosts);
    }

    private static List<String> hosts(String list) {
        return Arrays.stream(list.split(",")).map(String::strip).filter(h -> !h.isEmpty()).toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void start() {
        try {
            SiteIndexes.Result result = indexes.ensureControlPlaneIndexes();
            log.info("Control-plane indexes: {} ensured, {} failed", result.created(), result.failed());
        } catch (RuntimeException e) {
            log.error("Could not create the control-plane indexes: {}", e.getMessage());
        }

        try {
            int brought = provisioner.reconcile();
            if (brought > 0) {
                log.info("Brought {} website(s) up to schema version {}", brought, SiteProvisioner.SCHEMA_VERSION);
            }
        } catch (RuntimeException e) {
            log.error("Could not reconcile the websites: {}", e.getMessage());
        }

        try {
            // A studio stopped within minutes of deleting a website never ran that deletion's
            // second drop; this is it.
            int dropped = retirement.sweep();
            if (dropped > 0) {
                log.info("Dropped the databases of {} recently deleted website(s) once more", dropped);
            }
        } catch (RuntimeException e) {
            log.error("Could not sweep the deleted websites: {}", e.getMessage());
        }

        if (demoEnabled) {
            createDemo();
        }
    }

    private void createDemo() {
        try {
            if (websites.count() > 0) {
                return;
            }
        } catch (DataAccessException e) {
            log.error("Could not count the websites; no demo website: {}", e.getMessage());
            return;
        }
        Website first = createDemo(null, demoId, demoName, demoHosts, null);
        if (minimalEnabled && first != null) {
            // In the first one's organisation: one demo organisation holding both.
            createDemo(first.getOrganisationId(), minimalId, minimalName, minimalHosts, MINIMAL_SET);
        }
    }

    /** One demo website, with its content; null when it could not be created. */
    private Website createDemo(String organisationId, String id, String name, List<String> hosts, String templateSet) {
        Website website;
        try {
            website = provisioner.create(new SiteProvisioner.NewWebsite(organisationId, "Demo organisation", id,
                    name, demoLanguage, "UTC", hosts, null, templateSet));
            log.info("Created the demo website '{}' at {}", id, hosts);
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException e) {
            log.error("Could not create the demo website {}: {}", id, e.getMessage());
            return null;
        }
        if (demoContent) {
            if (demo.supports(demoLanguage)) {
                SeedDemoCommand.install(demo, website);
            } else {
                log.warn("The demo website {} is in {}; demo content is written in {}, so it starts empty",
                        id, demoLanguage, demo.languages());
            }
        }
        return website;
    }
}
