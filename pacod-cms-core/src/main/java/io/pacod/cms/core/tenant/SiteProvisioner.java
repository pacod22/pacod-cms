package io.pacod.cms.core.tenant;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DeletedWebsiteRepository;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.seed.SiteSeedStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates websites, and brings an existing website's database up to the layout this release
 * expects.
 *
 * <p>Provisioning a website, with it bound in {@link TenantContext}:
 * <ol>
 *   <li>creates the indexes its documents declare, in its own database ({@link SiteIndexes});</li>
 *   <li>runs every {@link SiteSeedStep} in order -- languages, pages, settings and menus, and
 *       whatever the application adds -- each writing only what is absent;</li>
 *   <li>records {@link #SCHEMA_VERSION} on the website and makes it {@link WebsiteStatus#ACTIVE}.</li>
 * </ol>
 * MongoDB creates a database on its first write, so there is no separate "create database" step.
 * All of it is safe to repeat, which is what {@link #reconcile()} relies on.
 */
@Slf4j
@Component
public class SiteProvisioner {

    /**
     * The website-database layout this release expects. Raise it when a release needs every
     * existing website re-provisioned -- a new index, a new seed -- and {@link #reconcile()} will.
     */
    public static final int SCHEMA_VERSION = 1;

    private final OrganisationRepository organisations;
    private final WebsiteRepository websites;
    private final DomainRepository domains;
    private final SiteIndexes indexes;
    private final List<SiteSeedStep> seeds;
    private final TenantDirectory directory;
    private final DeletedWebsiteRepository deleted;

    public SiteProvisioner(OrganisationRepository organisations, WebsiteRepository websites,
                           DomainRepository domains, SiteIndexes indexes, List<SiteSeedStep> seeds,
                           TenantDirectory directory, DeletedWebsiteRepository deleted) {
        this.deleted = deleted;
        this.organisations = organisations;
        this.websites = websites;
        this.domains = domains;
        this.indexes = indexes;
        this.seeds = seeds;
        this.directory = directory;
    }

    /**
     * What a new website is: who owns it, what it is called, and where it answers. A null
     * {@code organisationId} creates a new organisation called {@code organisationName}.
     *
     * @param createdBy   the pacod-id account creating it, or null for the platform itself
     * @param templateSet the template set it renders with, or null for the default; the caller checks
     *                    it against the installation's catalog
     */
    public record NewWebsite(String organisationId, String organisationName, String siteId, String name,
                             String defaultLanguage, String timezone, List<String> hosts, String createdBy,
                             String templateSet) {

        public NewWebsite(String organisationId, String organisationName, String siteId, String name,
                          String defaultLanguage, String timezone, List<String> hosts) {
            this(organisationId, organisationName, siteId, name, defaultLanguage, timezone, hosts, null, null);
        }

        public NewWebsite(String organisationId, String organisationName, String siteId, String name,
                          String defaultLanguage, String timezone, List<String> hosts, String createdBy) {
            this(organisationId, organisationName, siteId, name, defaultLanguage, timezone, hosts, createdBy, null);
        }
    }

    /**
     * Creates the website -- and its organisation when that does not exist yet -- claims its hosts
     * and provisions it.
     *
     * @throws IllegalArgumentException for an id that is not a valid website id, or that exists
     * @throws IllegalStateException    when a host already belongs to another website
     */
    public Website create(NewWebsite request) {
        if (websites.existsById(request.siteId())) {
            throw new IllegalArgumentException("A website with the id " + request.siteId() + " already exists");
        }
        if (deleted.existsById(request.siteId())) {
            // Its database is dropped once more when the public site's caches have expired
            // (SiteRetirement.sweep); a new website of that id would lose its first minutes.
            throw new IllegalArgumentException("A website with the id " + request.siteId()
                    + " was deleted a moment ago; the id can be used again in a few minutes");
        }
        for (String host : request.hosts()) {
            domains.findById(Domain.normalise(host)).ifPresent(existing -> {
                throw new IllegalStateException("The host " + existing.getHost()
                        + " already belongs to the website " + existing.getWebsiteId());
            });
        }

        String organisationId = request.organisationId();
        if (organisationId == null || organisationId.isBlank()) {
            organisationId = Organisation.newId();
            Organisation organisation = new Organisation(organisationId, request.organisationName());
            organisation.setCreatedBy(request.createdBy());
            organisations.save(organisation);
        } else if (!organisations.existsById(organisationId)) {
            throw new IllegalArgumentException("No organisation " + organisationId);
        }

        Website website = new Website(request.siteId(), organisationId, request.name());
        website.setCreatedBy(request.createdBy());
        if (request.defaultLanguage() != null && !request.defaultLanguage().isBlank()) {
            website.setDefaultLanguage(request.defaultLanguage());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            website.setTimezone(request.timezone());
        }
        if (request.templateSet() != null && !request.templateSet().isBlank()) {
            website.setTemplateSet(request.templateSet());
        }
        website = websites.save(website);

        boolean primary = true;
        for (String host : request.hosts()) {
            domains.save(new Domain(host, website.getId(), primary));
            primary = false;
        }

        provision(website);
        return websites.findById(website.getId()).orElse(website);
    }

    /** Applies indexes and seeds to one website, then marks it current and active. */
    public void provision(Website website) {
        Tenant tenant = TenantDirectory.tenantOf(website);
        log.info("Provisioning website {} (database {})", website.getId(), website.getDbName());
        SiteSeedStep.Website about = new SiteSeedStep.Website(website.getId(), website.getName(),
                website.getDefaultLanguage());
        SiteIndexes.Result built = TenantContext.callAs(tenant, () -> {
            SiteIndexes.Result result = indexes.ensureSiteIndexes();
            for (SiteSeedStep step : seeds) {
                try {
                    step.seed(about);
                } catch (RuntimeException e) {
                    // A seed is a starting point, not a precondition: a website without its
                    // sample pages still works, and the next reconcile tries again.
                    log.error("Seeding {} into website {} failed: {}", step.name(), website.getId(), e.getMessage());
                }
            }
            return result;
        });
        // A website missing an index still works -- its queries scan -- so it is made active either
        // way; but it is only recorded as current once every index exists, so the next reconcile
        // tries the missing ones again rather than never.
        if (built.ok()) {
            website.setSchemaVersion(SCHEMA_VERSION);
        } else {
            log.warn("Website {} is missing {} index(es); it will be provisioned again on the next start",
                    website.getId(), built.failed());
        }
        if (website.getStatus() == WebsiteStatus.PROVISIONING) {
            website.setStatus(WebsiteStatus.ACTIVE);
        }
        websites.save(website);
        directory.evictAll();
    }

    /** Re-provisions every website whose database predates {@link #SCHEMA_VERSION}. */
    public int reconcile() {
        int count = 0;
        for (Website website : websites.findAll()) {
            if (website.getStatus() == WebsiteStatus.DELETING) {
                continue;
            }
            if (website.getSchemaVersion() < SCHEMA_VERSION || website.getStatus() == WebsiteStatus.PROVISIONING) {
                try {
                    provision(website);
                    count++;
                } catch (RuntimeException e) {
                    log.error("Could not bring website {} up to date: {}", website.getId(), e.getMessage());
                }
            }
        }
        return count;
    }
}
