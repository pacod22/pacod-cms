package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.access.StudioAccess;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Whether AI may be used for a website, and by whom: a gateway set up
 * ({@link AiGateway#configured()}), the organisation switched on by a platform admin
 * ({@code Organisation.aiEnabled}, off by default -- every call spends the installation's one key),
 * the website not switched off by its own admin ({@code Website.aiDisabled}), and the person holding
 * {@code ai.use} there. Read fresh, never cached: switching AI off must stop the next job.
 */
@Component
public class AiAccess {

    private final AiGateway gateway;
    private final OrganisationRepository organisations;
    private final WebsiteRepository websites;
    private final StudioAccess access;

    public AiAccess(AiGateway gateway, OrganisationRepository organisations, WebsiteRepository websites,
                    StudioAccess access) {
        this.gateway = gateway;
        this.organisations = organisations;
        this.websites = websites;
        this.access = access;
    }

    /** For a job: the gateway and both switches; no person (the runner is none). */
    public Optional<AiRefusal> refusalFor(String siteId) {
        if (!gateway.configured()) {
            return Optional.of(AiRefusal.NOT_SET_UP);
        }
        try {
            Optional<Website> website = websites.findById(siteId);
            if (website.isEmpty()) {
                return Optional.of(AiRefusal.WEBSITE_OFF);
            }
            boolean organisationOn = organisations.findById(website.get().getOrganisationId())
                    .map(Organisation::isAiEnabled).orElse(false);
            if (!organisationOn) {
                return Optional.of(AiRefusal.ORGANISATION_OFF);
            }
            return website.get().isAiDisabled() ? Optional.of(AiRefusal.WEBSITE_OFF) : Optional.empty();
        } catch (DataAccessException e) {
            return Optional.of(AiRefusal.NOT_SET_UP);
        }
    }

    /** For a screen: all of the above at the bound website, and the person's {@code ai.use}. */
    public Optional<AiRefusal> refusalHere() {
        Optional<String> site = TenantContext.find().map(t -> t.siteId());
        if (site.isEmpty()) {
            return Optional.of(AiRefusal.WEBSITE_OFF);
        }
        Optional<AiRefusal> refusal = refusalFor(site.get());
        if (refusal.isPresent()) {
            return refusal;
        }
        return access.allowed("ai.use") ? Optional.empty() : Optional.of(AiRefusal.NOT_ALLOWED);
    }

    public boolean availableHere() {
        return refusalHere().isEmpty();
    }
}
