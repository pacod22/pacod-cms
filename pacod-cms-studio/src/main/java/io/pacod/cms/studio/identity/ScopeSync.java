package io.pacod.cms.studio.identity;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps pacod-id's scopes in step with the control plane: every organisation, and every website as
 * a {@code cms.website} under its organisation.
 *
 * <p>pacod-id leaves out of the feed any binding at a scope it has not been told about, so until a
 * website is registered nobody's role there counts. Rather than an outbox written beside each
 * change, this compares the desired state (the control plane) with what the feed reports and puts
 * what differs -- which also catches websites created where nothing else runs, such as the one-off
 * {@code --create-site} container, and anything a failed call left behind. Run every few seconds by
 * {@link IdentityLifecycle}, once the catalogue is in (before it, pacod-id answers
 * {@code catalog_missing}).
 *
 * <ul>
 *   <li><b>Organisations before their websites</b>: a website under an unregistered organisation is
 *       {@code scope_unknown}, and is simply retried on the next round.</li>
 *   <li><b>{@code slug} and {@code display_name} are sent every time</b>: pacod-id overwrites both
 *       on each PUT, and an omitted slug becomes the id. The slug is the id -- ids are generated
 *       ({@code org_<hex>}) and pacod-id's slugs are one namespace across products.</li>
 *   <li><b>A website being deleted is skipped</b> ({@code DELETING}): its scope is on its way out.</li>
 *   <li><b>Nothing is deleted here.</b> Deleting a scope deletes every binding under it; that
 *       belongs to deleting the website or organisation, not to a reconciliation that may be
 *       comparing against a half-read control plane.</li>
 * </ul>
 */
@Slf4j
public class ScopeSync {

    static final String WEBSITE_TYPE = "cms.website";

    private final IdClient client;
    private final AuthzFeed feed;
    private final OrganisationRepository organisations;
    private final WebsiteRepository websites;

    public ScopeSync(IdClient client, AuthzFeed feed, OrganisationRepository organisations, WebsiteRepository websites) {
        this.client = client;
        this.feed = feed;
        this.organisations = organisations;
        this.websites = websites;
    }

    /** One round; the number of PUTs it made. */
    public int reconcile() {
        if (!feed.isReady()) {
            return 0;
        }
        Map<String, AuthzFeed.Scope> known = new HashMap<>();
        for (AuthzFeed.Scope scope : feed.scopes()) {
            known.put(scope.key(), scope);
        }
        int puts = 0;
        for (Organisation organisation : organisations.findAll()) {
            AuthzFeed.Scope have = known.get("organisation:" + organisation.getId());
            String name = nameOf(organisation.getName(), organisation.getId());
            if (have == null || !have.products().contains(AuthzFeed.PRODUCT) || !name.equals(have.displayName())) {
                try {
                    client.put("/internal/v1/organisations/" + organisation.getId(),
                            Map.of("slug", organisation.getId(), "display_name", name));
                    puts++;
                } catch (IdClient.IdException e) {
                    log.warn("Could not register organisation {} with pacod-id: {}", organisation.getId(), e.getMessage());
                }
            }
        }
        for (Website website : websites.findAll()) {
            if (website.getStatus() == WebsiteStatus.DELETING) {
                // Its scope is being removed; registering it again would bring its roles back.
                continue;
            }
            AuthzFeed.Scope have = known.get(WEBSITE_TYPE + ":" + website.getId());
            String parent = "organisation:" + website.getOrganisationId();
            String name = nameOf(website.getName(), website.getId());
            if (have == null || !Objects.equals(parent, have.parent()) || !name.equals(have.displayName())) {
                try {
                    client.put("/internal/v1/scopes/" + WEBSITE_TYPE + "/" + website.getId(),
                            Map.of("parent", parent, "display_name", name));
                    puts++;
                } catch (IdClient.IdException e) {
                    if (!"scope_unknown".equals(e.code())) {
                        log.warn("Could not register website {} with pacod-id: {}", website.getId(), e.getMessage());
                    }
                }
            }
        }
        if (puts > 0) {
            log.info("Registered {} organisation(s) and website(s) with pacod-id", puts);
        }
        return puts;
    }

    private static String nameOf(String name, String id) {
        return name == null || name.isBlank() ? id : name;
    }

    /** For a test: what a round would register, without the feed. */
    static List<String> keys(List<AuthzFeed.Scope> scopes) {
        return scopes.stream().map(AuthzFeed.Scope::key).toList();
    }
}
