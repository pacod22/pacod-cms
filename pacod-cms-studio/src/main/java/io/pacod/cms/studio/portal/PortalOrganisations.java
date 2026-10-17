package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.OrgCreation;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.identity.IdClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Organisations: who may create one, creating it, renaming, deleting.
 *
 * <h2>Creating one makes its creator its admin</h2>
 * <ol>
 *   <li>the organisation document is written, {@code createdBy} the creator's account;</li>
 *   <li>pacod-id is told of it, at once ({@link PortalIdentity#registerOrganisation});</li>
 *   <li>the studio's provisioner binds the creator {@code cms.org_admin} there;</li>
 *   <li>the screen waits for the feed to carry that binding, so the next page already shows the
 *       creator as admin.</li>
 * </ol>
 * If pacod-id refuses step 2 or 3 the organisation is removed again: an organisation nobody
 * administers would sit in the list, counted against its creator's limit, with nobody able to
 * open it.
 *
 * <h2>Who may</h2>
 * Platform admins always, and without limits. Anyone else while the policy is self-service
 * ({@link PortalSettings#orgCreation()}), up to {@code maxOrgsPerUser} each, and only with a
 * provisioner configured -- without one nobody could make them the new organisation's admin.
 */
@Slf4j
@Service
public class PortalOrganisations {

    public static final int NAME_MAX = 100;

    private final OrganisationRepository organisations;
    private final WebsiteRepository websites;
    private final PortalSettings settings;
    private final PortalIdentity identity;
    private final StudioAccess access;

    public PortalOrganisations(OrganisationRepository organisations, WebsiteRepository websites,
                               PortalSettings settings, PortalIdentity identity, StudioAccess access) {
        this.organisations = organisations;
        this.websites = websites;
        this.settings = settings;
        this.identity = identity;
        this.access = access;
    }

    /** An organisation on a screen, with how many websites it has. */
    public record OrgView(Organisation organisation, long websites) {

        public String id() {
            return organisation.getId();
        }

        public String name() {
            return organisation.getName() == null || organisation.getName().isBlank()
                    ? organisation.getId() : organisation.getName();
        }
    }

    /**
     * Whether the signed-in person may create an organisation now.
     *
     * @param refusal the message key saying why not, or null when they may
     */
    public record Allowance(String refusal, Object[] args) {

        public boolean allowed() {
            return refusal == null;
        }

        static Allowance yes() {
            return new Allowance(null, new Object[0]);
        }
    }

    /** The organisations the signed-in person may see: every one for a platform admin. */
    public List<OrgView> visible() {
        return organisations.findAllByOrderByNameAsc().stream()
                .filter(org -> access.allowedAt("org.read", StudioAccess.pathOfOrganisation(org.getId())))
                .map(this::view)
                .toList();
    }

    /** Every organisation, for the platform screen. */
    public List<OrgView> all() {
        return organisations.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    /** The organisations the signed-in person may create websites in. */
    public List<OrgView> creatable() {
        return organisations.findAllByOrderByNameAsc().stream()
                .filter(org -> access.allowedAt("website.create", StudioAccess.pathOfOrganisation(org.getId())))
                .map(this::view)
                .toList();
    }

    public Optional<Organisation> find(String id) {
        return id == null ? Optional.empty() : organisations.findById(id);
    }

    public Organisation require(String id) {
        return find(id).orElseThrow(() -> new MgmtNotFoundException("portal.org.notFound", id));
    }

    public OrgView view(Organisation organisation) {
        return new OrgView(organisation, websites.countByOrganisationId(organisation.getId()));
    }

    public Allowance allowance() {
        if (access.isPlatformAdmin()) {
            return Allowance.yes();
        }
        if (settings.orgCreation() == OrgCreation.PLATFORM_ADMINS) {
            return new Allowance("portal.org.refused.policy", new Object[0]);
        }
        if (!identity.canProvision()) {
            return new Allowance("portal.org.refused.provisioner", new Object[0]);
        }
        String sub = StudioPrincipals.currentSubject();
        int limit = settings.current().getMaxOrgsPerUser();
        long made = sub == null ? 0 : organisations.countByCreatedBy(sub);
        if (made >= limit) {
            return new Allowance("portal.org.refused.limit", new Object[]{limit});
        }
        return Allowance.yes();
    }

    /**
     * The result of {@link #create}.
     *
     * @param settled whether the feed already shows the creator as admin; when not, the next
     *                screen may refuse for a few seconds
     */
    public record Created(Organisation organisation, boolean settled) {
    }

    public Created create(String name) {
        Allowance allowance = allowance();
        if (!allowance.allowed()) {
            throw new MgmtOperationException(allowance.refusal(), allowance.args());
        }
        String clean = cleanName(name);
        String sub = StudioPrincipals.currentSubject();

        Organisation organisation = new Organisation(Organisation.newId(), clean);
        organisation.setCreatedBy(sub);
        organisation.setAiEnabled(settings.current().isAiEnabledForNewOrgs());
        organisation = organisations.save(organisation);

        boolean settled = true;
        if (identity.enabled()) {
            try {
                long seq = identity.registerOrganisation(organisation);
                if (identity.canProvision() && sub != null) {
                    seq = Math.max(seq, identity.makeOrgAdmin(sub, organisation.getId()));
                }
                settled = identity.await(seq);
            } catch (IdClient.IdException | IllegalStateException e) {
                log.error("Creating organisation {} failed in pacod-id; removing it again: {}",
                        organisation.getId(), e.getMessage());
                undo(organisation.getId());
                throw new MgmtOperationException("portal.org.error.identity", e.getMessage());
            }
        }
        log.info("Organisation {} ('{}') created by {}", organisation.getId(), clean, sub);
        return new Created(organisation, settled);
    }

    public Organisation rename(String id, String name) {
        Organisation organisation = require(id);
        organisation.setName(cleanName(name));
        organisation = organisations.save(organisation);
        if (identity.enabled()) {
            try {
                identity.registerOrganisation(organisation);
            } catch (IdClient.IdException e) {
                // ScopeSync carries the new name over on its next round.
                log.warn("Could not rename organisation {} in pacod-id yet: {}", id, e.getMessage());
            }
        }
        return organisation;
    }

    /** Deletes an organisation that has no websites left, and its roles in pacod-id. */
    public void delete(String id) {
        Organisation organisation = require(id);
        long count = websites.countByOrganisationId(id);
        if (count > 0) {
            throw new MgmtOperationException("portal.org.error.hasWebsites", count);
        }
        if (identity.enabled()) {
            try {
                identity.deleteOrganisation(id);
            } catch (IdClient.IdException e) {
                throw new MgmtOperationException("portal.org.error.identity", e.getMessage());
            }
        }
        organisations.delete(organisation);
        log.info("Organisation {} deleted by {}", id, StudioPrincipals.currentSubject());
    }

    public Organisation setAiEnabled(String id, boolean enabled) {
        Organisation organisation = require(id);
        organisation.setAiEnabled(enabled);
        return organisations.save(organisation);
    }

    private void undo(String id) {
        try {
            organisations.deleteById(id);
        } catch (RuntimeException e) {
            log.error("Could not remove organisation {} after a failed creation: {}", id, e.getMessage());
        }
        try {
            identity.deleteOrganisation(id);
        } catch (RuntimeException e) {
            log.warn("Could not remove organisation {} from pacod-id after a failed creation: {}", id, e.getMessage());
        }
    }

    static String cleanName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > NAME_MAX) {
            throw new MgmtOperationException("portal.org.error.name", NAME_MAX);
        }
        return clean;
    }
}
