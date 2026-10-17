package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.SiteRetirement;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.TemplateSetManifest;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.access.StudioForbiddenException;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.demo.StudioDemo;
import io.pacod.cms.studio.identity.IdClient;
import io.pacod.cms.studio.site.StudioSites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Websites: creating one in an organisation, its settings and domains, deleting it.
 *
 * <p>Every check here is repeated from the screen on purpose. The new-website form offers only the
 * organisations the person may create in, but the organisation arrives as a posted value, so
 * {@code website.create} is checked again against the one posted.
 *
 * <h2>Deleting</h2>
 * {@link SiteRetirement} takes the website offline and releases its domains; then its pacod-id
 * scope goes, with every role and invitation there; then its database, files and document. If
 * pacod-id cannot be reached the website stays offline as {@code DELETING} and deleting it again
 * finishes the job -- its roles must not wait for a new website of the same id. Three minutes later
 * the database is dropped once more ({@link SiteRetirement#sweep}), after whatever the public site
 * still wrote into it from its caches.
 */
@Slf4j
@Service
public class PortalWebsites {

    public static final int NAME_MAX = 100;
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    private final WebsiteRepository websites;
    private final DomainRepository domains;
    private final SiteProvisioner provisioner;
    private final SiteRetirement retirement;
    private final TenantDirectory directory;
    private final StudioSites studioSites;
    private final PortalOrganisations organisations;
    private final PortalSettings settings;
    private final PortalIdentity identity;
    private final PortalProperties properties;
    private final DomainRules domainRules;
    private final StudioAccess access;
    private io.pacod.cms.core.jobs.JobQueue jobs;
    private StudioDemo demo;
    private TemplateSetCatalog templateSets;

    public PortalWebsites(WebsiteRepository websites, DomainRepository domains, SiteProvisioner provisioner,
                          SiteRetirement retirement, TenantDirectory directory, StudioSites studioSites,
                          PortalOrganisations organisations, PortalSettings settings, PortalIdentity identity,
                          PortalProperties properties, DomainRules domainRules, StudioAccess access) {
        this.websites = websites;
        this.domains = domains;
        this.provisioner = provisioner;
        this.retirement = retirement;
        this.directory = directory;
        this.studioSites = studioSites;
        this.organisations = organisations;
        this.settings = settings;
        this.identity = identity;
        this.properties = properties;
        this.domainRules = domainRules;
        this.access = access;
    }

    /**
     * What the new-website form posts.
     *
     * @param demo        whether to start it with the demo content ({@link StudioDemo})
     * @param templateSet the template set it renders with; blank for the default
     */
    public record NewSite(String organisationId, String id, String name, String language, String timezone,
                          List<String> extraHosts, boolean demo, String templateSet) {

        public NewSite(String organisationId, String id, String name, String language, String timezone,
                       List<String> extraHosts) {
            this(organisationId, id, name, language, timezone, extraHosts, false, null);
        }

        public NewSite(String organisationId, String id, String name, String language, String timezone,
                       List<String> extraHosts, boolean demo) {
            this(organisationId, id, name, language, timezone, extraHosts, demo, null);
        }
    }

    /** The address a website with this id gets on its own, or null when none is configured. */
    public String automaticHost(String id) {
        return properties.siteDomain().isEmpty() || id == null || id.isBlank()
                ? null : id + "." + properties.siteDomain();
    }

    public String siteDomain() {
        return properties.siteDomain();
    }

    public Website require(String siteId) {
        return websites.findById(siteId).orElseThrow(() -> new MgmtNotFoundException("portal.site.notFound", siteId));
    }

    public List<Website> inOrganisation(String organisationId) {
        return websites.findByOrganisationIdOrderByNameAsc(organisationId);
    }

    public List<Domain> domainsOf(String siteId) {
        return domains.findByWebsiteId(siteId).stream()
                .sorted(Comparator.comparing(Domain::isPrimary).reversed().thenComparing(Domain::getHost))
                .toList();
    }

    public Website create(NewSite request) {
        Organisation organisation = organisations.require(request.organisationId());
        if (!access.allowedAt("website.create", StudioAccess.pathOfOrganisation(organisation.getId()))) {
            throw new StudioForbiddenException("Not allowed: website.create");
        }
        if (!access.isPlatformAdmin()) {
            int limit = settings.current().getMaxSitesPerOrg();
            if (websites.countByOrganisationId(organisation.getId()) >= limit) {
                throw new MgmtOperationException("portal.site.refused.limit", limit);
            }
        }
        String id = request.id() == null ? "" : request.id().strip().toLowerCase(java.util.Locale.ROOT);
        if (!Website.ID.matcher(id).matches()) {
            throw new MgmtOperationException("portal.site.error.id");
        }
        if (websites.existsById(id)) {
            throw new MgmtOperationException("portal.site.error.idTaken", id);
        }
        String name = cleanName(request.name());
        String language = cleanLanguage(request.language());
        String timezone = cleanTimezone(request.timezone());
        String templateSet = request.templateSet() == null || request.templateSet().isBlank()
                ? null : request.templateSet().strip();
        if (templateSet != null && (templateSets == null || templateSets.find(templateSet).isEmpty())) {
            throw new MgmtOperationException("portal.site.error.templateSet", templateSet);
        }
        // Before anything exists: a refused demo must not leave a website half-made.
        if (request.demo() && (demo == null || !demo.supports(language))) {
            throw new MgmtOperationException("portal.site.error.demoLanguage", demoLanguages());
        }

        Set<String> hosts = new LinkedHashSet<>();
        String automatic = automaticHost(id);
        if (automatic != null) {
            hosts.add(domainRules.check(automatic));
        }
        for (String host : request.extraHosts() == null ? List.<String>of() : request.extraHosts()) {
            if (host != null && !host.isBlank()) {
                hosts.add(domainRules.check(host));
            }
        }
        for (String host : hosts) {
            domains.findById(host).ifPresent(taken -> {
                throw new MgmtOperationException("portal.domain.taken", host);
            });
        }

        Website website;
        try {
            website = provisioner.create(new SiteProvisioner.NewWebsite(organisation.getId(), null, id, name,
                    language, timezone, new ArrayList<>(hosts), StudioPrincipals.currentSubject(), templateSet));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new MgmtOperationException("portal.site.error.create", e.getMessage());
        }
        register(website);
        studioSites.evict();
        log.info("Website {} created in organisation {} by {}", id, organisation.getId(), StudioPrincipals.currentSubject());
        return website;
    }

    /** The general settings. Only active and suspended can be chosen here. */
    public Website update(String siteId, String name, String language, String timezone, WebsiteStatus status) {
        return update(siteId, name, language, timezone, status, null);
    }

    /** The same, and the website's AI switch; {@code ai} null leaves it as it is. */
    public Website update(String siteId, String name, String language, String timezone, WebsiteStatus status,
                          Boolean ai) {
        Website website = require(siteId);
        if (website.getStatus() == WebsiteStatus.DELETING) {
            throw new MgmtOperationException("portal.site.error.deleting");
        }
        website.setName(cleanName(name));
        website.setDefaultLanguage(cleanLanguage(language));
        website.setTimezone(cleanTimezone(timezone));
        if (status == WebsiteStatus.ACTIVE || status == WebsiteStatus.SUSPENDED) {
            website.setStatus(status);
        }
        if (ai != null) {
            website.setAiDisabled(!ai);
        }
        website = websites.save(website);
        changed();
        register(website);
        return website;
    }

    public void addDomain(String siteId, String host) {
        Website website = require(siteId);
        String h = domainRules.check(host);
        domains.findById(h).ifPresent(taken -> {
            throw new MgmtOperationException("portal.domain.taken", h);
        });
        boolean first = domains.findByWebsiteId(website.getId()).isEmpty();
        domains.save(new Domain(h, website.getId(), first));
        changed();
    }

    public void makePrimary(String siteId, String host) {
        List<Domain> mine = domains.findByWebsiteId(siteId);
        String h = Domain.normalise(host);
        if (mine.stream().noneMatch(d -> d.getHost().equals(h))) {
            throw new MgmtOperationException("portal.domain.notOwn", host);
        }
        for (Domain domain : mine) {
            boolean primary = domain.getHost().equals(h);
            if (domain.isPrimary() != primary) {
                domain.setPrimary(primary);
                domains.save(domain);
            }
        }
        changed();
    }

    public void removeDomain(String siteId, String host) {
        List<Domain> mine = domains.findByWebsiteId(siteId);
        String h = Domain.normalise(host);
        Domain domain = mine.stream().filter(d -> d.getHost().equals(h)).findFirst()
                .orElseThrow(() -> new MgmtOperationException("portal.domain.notOwn", host));
        domains.delete(domain);
        if (domain.isPrimary()) {
            mine.stream().filter(d -> !d.getHost().equals(h)).findFirst().ifPresent(next -> {
                next.setPrimary(true);
                domains.save(next);
            });
        }
        changed();
    }

    /** The installation's template sets, which a new website may choose from; optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useTemplateSets(TemplateSetCatalog templateSets) {
        this.templateSets = templateSets;
    }

    /** What the new-website form offers. */
    public List<TemplateSetManifest> templateSets() {
        return templateSets == null ? List.of() : templateSets.all();
    }

    /** The demo content a new website may start with; optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useDemo(StudioDemo demo) {
        this.demo = demo;
    }

    /** The languages a website can have demo content in, named in the UI language; "-" where there is none. */
    public String demoLanguages() {
        return demo == null ? "-" : demo.languageNames(org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }

    /**
     * Gives a website just created the demo content it was asked with. Its failure is the demo's
     * alone -- the website exists and works -- so the caller says it rather than undoing anything.
     *
     * @throws DemoContent.Refused   (not for a new website, which has no articles and whose language
     *                               {@link #create} checked)
     * @throws IllegalStateException when a picture could not be stored
     */
    public StudioDemo.Outcome addDemo(Website website) {
        if (demo == null) {
            throw new IllegalStateException("Demo content is not available here");
        }
        return demo.install(website);
    }

    /** The jobs queue, whose unfinished jobs a deleted website must not keep; optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useJobs(io.pacod.cms.core.jobs.JobQueue jobs) {
        this.jobs = jobs;
    }

    /**
     * Deletes the website, its content, its files and its roles.
     *
     * @param confirmation what the person typed; it must be the website's id
     */
    public void delete(String siteId, String confirmation) {
        Website website = require(siteId);
        if (confirmation == null || !confirmation.strip().equals(website.getId())) {
            throw new MgmtOperationException("portal.site.error.confirm", website.getId());
        }
        retirement.retire(siteId);
        changed();
        // Its queued jobs go now, its running ones stop at their next checkpoint: a job that wrote
        // after the drop would recreate the database. The runner checks the website again anyway.
        if (jobs != null) {
            try {
                jobs.cancelAll(siteId);
            } catch (RuntimeException e) {
                log.warn("Could not cancel the jobs of website {} being deleted: {}", siteId, e.getMessage());
            }
        }
        if (identity.enabled()) {
            try {
                identity.deleteWebsite(siteId);
            } catch (IdClient.IdException e) {
                log.error("Website {} is offline but its pacod-id scope could not be removed: {}", siteId, e.getMessage());
                throw new MgmtOperationException("portal.site.error.deleteIdentity", e.getMessage());
            }
        }
        retirement.erase(siteId, StudioPrincipals.currentSubject());
        changed();
        Thread.ofVirtual().name("sweep-" + siteId).start(() -> {
            try {
                Thread.sleep(SiteRetirement.SETTLE);
                retirement.sweep();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Sweeping deleted websites failed: {}", e.getMessage());
            }
        });
    }

    private void register(Website website) {
        if (!identity.enabled()) {
            return;
        }
        try {
            identity.await(identity.registerWebsite(website));
        } catch (IdClient.IdException e) {
            // ScopeSync registers it on its next round; until then roles at the website itself
            // wait (the organisation's apply already).
            log.warn("Could not register website {} with pacod-id yet: {}", website.getId(), e.getMessage());
        }
    }

    private void changed() {
        directory.evictAll();
        studioSites.evict();
    }

    static String cleanName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > NAME_MAX) {
            throw new MgmtOperationException("portal.site.error.name", NAME_MAX);
        }
        return clean;
    }

    static String cleanLanguage(String language) {
        String clean = language == null ? "" : language.strip().toLowerCase(java.util.Locale.ROOT);
        if (!LANGUAGE.matcher(clean).matches()) {
            throw new MgmtOperationException("portal.site.error.language");
        }
        return clean;
    }

    static String cleanTimezone(String timezone) {
        String clean = timezone == null || timezone.isBlank() ? "UTC" : timezone.strip();
        try {
            return ZoneId.of(clean).getId();
        } catch (DateTimeException e) {
            throw new MgmtOperationException("portal.site.error.timezone", clean);
        }
    }
}
