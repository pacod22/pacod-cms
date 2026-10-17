package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.studio.identity.AuthzFeed;
import io.pacod.cms.studio.identity.IdAdmin;
import io.pacod.cms.studio.identity.IdClient;
import io.pacod.cms.studio.identity.Provisioner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * What the portal tells pacod-id as it creates and deletes organisations and websites -- at once,
 * not on {@code ScopeSync}'s next round, because the next step (the creator's binding, an
 * invitation) is refused as {@code scope_unknown} until pacod-id knows the scope.
 *
 * <p>Absent pieces are said, not faked: with {@code pacod.id.enabled=false} there is no client and
 * {@link #enabled()} is false; without a provisioner token {@link #canProvision()} is false.
 */
@Slf4j
@Component
public class PortalIdentity {

    /** How long a screen waits for the feed to show its own write before redirecting. */
    static final Duration READ_YOUR_WRITES = Duration.ofSeconds(5);

    static final String WEBSITE_TYPE = "cms.website";

    private final ObjectProvider<IdClient> client;
    private final ObjectProvider<AuthzFeed> feed;
    private final ObjectProvider<Provisioner> provisioner;

    public PortalIdentity(ObjectProvider<IdClient> client, ObjectProvider<AuthzFeed> feed,
                          ObjectProvider<Provisioner> provisioner) {
        this.client = client;
        this.feed = feed;
        this.provisioner = provisioner;
    }

    public boolean enabled() {
        return client.getIfAvailable() != null;
    }

    public boolean canProvision() {
        return provisioner.getIfAvailable() != null;
    }

    public long registerOrganisation(Organisation organisation) {
        return IdAdmin.seqOf(require().put("/internal/v1/organisations/" + organisation.getId(),
                Map.of("slug", organisation.getId(), "display_name", nameOf(organisation.getName(), organisation.getId()))));
    }

    public long registerWebsite(Website website) {
        return IdAdmin.seqOf(require().put("/internal/v1/scopes/" + WEBSITE_TYPE + "/" + website.getId(),
                Map.of("parent", "organisation:" + website.getOrganisationId(),
                        "display_name", nameOf(website.getName(), website.getId()))));
    }

    /** Removes the website's scope -- and every role and invitation at it. Unknown is no error. */
    public void deleteWebsite(String siteId) {
        require().delete("/internal/v1/scopes/" + WEBSITE_TYPE + "/" + siteId, Map.of());
    }

    /** Removes this product's part of the organisation in pacod-id. */
    public void deleteOrganisation(String organisationId) {
        require().delete("/internal/v1/organisations/" + organisationId, Map.of("product", "cms"));
    }

    /** See {@link Provisioner#makeOrgAdmin}. */
    public long makeOrgAdmin(String userSub, String organisationId) {
        Provisioner p = provisioner.getIfAvailable();
        if (p == null) {
            throw new IllegalStateException("No provisioner is configured");
        }
        return p.makeOrgAdmin(userSub, organisationId);
    }

    /**
     * Waits for the feed to reach {@code seq}, so the next screen already knows the new role.
     * False when it did not in time -- the role arrives a moment later; nothing is lost.
     */
    public boolean await(long seq) {
        AuthzFeed f = feed.getIfAvailable();
        if (f == null || seq < 0) {
            return true;
        }
        try {
            return f.awaitSeq(seq, READ_YOUR_WRITES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private IdClient require() {
        IdClient c = client.getIfAvailable();
        if (c == null) {
            throw new IllegalStateException("pacod-id is not enabled");
        }
        return c;
    }

    private static String nameOf(String name, String id) {
        return name == null || name.isBlank() ? id : name;
    }
}
