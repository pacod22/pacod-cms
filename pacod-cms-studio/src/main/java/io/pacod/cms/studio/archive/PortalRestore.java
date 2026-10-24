package io.pacod.cms.studio.archive;

import io.pacod.cms.core.archive.ArchiveException;
import io.pacod.cms.core.archive.SiteArchives;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.portal.PortalOrganisations;
import io.pacod.cms.studio.portal.PortalSettings;
import io.pacod.cms.studio.portal.PortalWebsites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Restoring an archive ({@link SiteArchives}): always into a <b>new</b> website, never over one.
 *
 * <ul>
 *   <li><b>A website</b> goes into an organisation the person may create websites in, under the id
 *       and name they choose (by default the source's, or the next free id), through
 *       {@link PortalWebsites#restore} -- every check of creating one, its automatic host, its
 *       pacod-id scope; the source's hosts are never claimed.</li>
 *   <li><b>An organisation</b> becomes a new organisation, made exactly as the portal makes one
 *       ({@link PortalOrganisations#create}: who may, the creator its admin), with each website
 *       under its own id where free, else the next free one. One website failing does not undo the
 *       others: each is said.</li>
 * </ul>
 * Members and roles are not in an archive; the restorer holds what creating gave them. A template
 * set this installation lacks becomes the default, said in the outcome.
 */
@Slf4j
@Service
public class PortalRestore {

    private final SiteArchives archives;
    private final PortalWebsites websites;
    private final PortalOrganisations organisations;
    private final PortalSettings settings;
    private final StudioAccess access;
    private final WebsiteRepository websiteRepository;
    private TemplateSetCatalog templateSets;
    private io.pacod.cms.core.repository.SiteSettingsRepository siteSettings;

    public PortalRestore(SiteArchives archives, PortalWebsites websites, PortalOrganisations organisations,
                         PortalSettings settings, StudioAccess access, WebsiteRepository websiteRepository) {
        this.archives = archives;
        this.websites = websites;
        this.organisations = organisations;
        this.settings = settings;
        this.access = access;
        this.websiteRepository = websiteRepository;
    }

    @Autowired(required = false)
    void useTemplateSets(TemplateSetCatalog templateSets) {
        this.templateSets = templateSets;
    }

    /** Where a renamed website's name is written for its readers; optional for hand-built tests. */
    @Autowired(required = false)
    void useSiteSettings(io.pacod.cms.core.repository.SiteSettingsRepository siteSettings) {
        this.siteSettings = siteSettings;
    }

    /** Something said about a restore that worked: a message key and its arguments. */
    public record Note(String key, Object... args) {
    }

    /** A website of an organisation's archive that could not be restored, and why. */
    public record Failure(String sourceId, MgmtOperationException error) {
    }

    public record RestoredSite(Website website, List<Note> notes) {
    }

    public record RestoredOrganisation(Organisation organisation, boolean settled, List<Website> websites,
                                       List<Failure> failures, List<Note> notes) {
    }

    /**
     * One website's archive into a new website of {@code organisationId}. A blank {@code id}
     * takes the source's, or the next free one; a blank {@code name} the source's.
     */
    public RestoredSite restoreSite(Path file, String organisationId, String id, String name) {
        SiteArchives.Archive archive = open(file);
        if (archive.organisation()) {
            throw new MgmtOperationException("portal.archive.error.isOrganisation");
        }
        SiteArchives.SiteEntry site = archive.sites().getFirst();
        List<Note> notes = new ArrayList<>();
        String targetId = id == null || id.isBlank() ? websites.freeId(site.sourceId()) : id;
        Website website = restoreOne(archive, site, organisationId, targetId,
                name == null || name.isBlank() ? site.name() : name, false, notes);
        log.info("Website {} restored as {} by {}", site.sourceId(), website.getId(), StudioPrincipals.currentSubject());
        return new RestoredSite(website, notes);
    }

    /** An organisation's archive into a new organisation; a blank {@code name} takes the source's. */
    public RestoredOrganisation restoreOrganisation(Path file, String name) {
        SiteArchives.Archive archive = open(file);
        if (!archive.organisation()) {
            throw new MgmtOperationException("portal.archive.error.isWebsite");
        }
        // Refused before the organisation exists: half an organisation helps nobody.
        if (!access.isPlatformAdmin() && archive.sites().size() > settings.current().getMaxSitesPerOrg()) {
            throw new MgmtOperationException("portal.site.refused.limit", settings.current().getMaxSitesPerOrg());
        }
        String orgName = name == null || name.isBlank() ? archive.organisationName() : name;
        PortalOrganisations.Created created = organisations.create(
                cap(orgName == null || orgName.isBlank() ? "Restored organisation" : orgName, PortalOrganisations.NAME_MAX));
        Organisation organisation = created.organisation();

        List<Website> restored = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
        for (SiteArchives.SiteEntry site : archive.sites()) {
            try {
                restored.add(restoreOne(archive, site, organisation.getId(), websites.freeId(site.sourceId()),
                        site.name(), true, notes));
            } catch (MgmtOperationException e) {
                log.error("Restoring website {} into organisation {} failed: {}", site.sourceId(), organisation.getId(),
                        e.getMessage());
                failures.add(new Failure(site.sourceId(), e));
            }
        }
        log.info("Organisation restored as {} with {} website(s), {} failed, by {}", organisation.getId(),
                restored.size(), failures.size(), StudioPrincipals.currentSubject());
        return new RestoredOrganisation(organisation, created.settled(), restored, failures, notes);
    }

    private Website restoreOne(SiteArchives.Archive archive, SiteArchives.SiteEntry site, String organisationId,
                               String id, String name, boolean ownOrganisation, List<Note> notes) {
        String templateSet = site.templateSet();
        if (templateSet != null && (templateSets == null || templateSets.find(templateSet).isEmpty())) {
            notes.add(new Note("portal.archive.note.templateSet", templateSet));
            templateSet = null;
        }
        String newName = cap(name, PortalWebsites.NAME_MAX);
        Website website = websites.restore(new PortalWebsites.NewSite(organisationId, id, newName,
                        site.defaultLanguage(), site.timezone(), List.of(), false, templateSet),
                created -> {
                    try {
                        archives.fill(archive, site);
                    } catch (ArchiveException e) {
                        throw coded(e);
                    }
                    rename(site, newName);
                }, ownOrganisation);
        if (site.aiDisabled()) {
            website.setAiDisabled(true);
            website = websiteRepository.save(website);
        }
        if (!id.equals(site.sourceId())) {
            notes.add(new Note("portal.archive.note.renamed", site.sourceId(), website.getId()));
        }
        return website;
    }

    /**
     * The name readers see is the website's settings', which came with the archive: a website
     * restored under another name says that name in its original language, its others kept.
     */
    private void rename(SiteArchives.SiteEntry site, String name) {
        if (siteSettings == null || name.equals(site.name())) {
            return;
        }
        io.pacod.cms.core.data.SiteSettings settings = siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID)
                .orElseGet(io.pacod.cms.core.data.SiteSettings::new);
        settings.setNames(io.pacod.cms.core.data.TextItems.put(settings.getNames(), site.defaultLanguage(), name));
        siteSettings.save(settings);
    }

    private SiteArchives.Archive open(Path file) {
        try {
            return archives.open(file);
        } catch (ArchiveException e) {
            throw coded(e);
        }
    }

    /** The core's refusal as the studio's, which its screens know how to say. */
    public static MgmtOperationException coded(ArchiveException e) {
        return new MgmtOperationException(e.code(), e.args());
    }

    private static String cap(String value, int max) {
        String v = value.strip().replaceAll("\\s+", " ");
        return v.length() > max ? v.substring(0, max).strip() : v;
    }
}
