package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The websites, as the studio's screens show them: the website list, and the website a screen is
 * editing.
 *
 * <p>The list is read fresh. The current website is cached for half a minute per id -- it is read
 * for every screen rendered, a renamed website or a new domain reaches the chrome within that, and
 * a control plane that cannot be read costs the chip and the "view site" link, never the screen.
 */
@Slf4j
@Service
public class StudioSites {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final Cache<String, SiteView> current = Caffeine.newBuilder()
            .maximumSize(1_000).expireAfterWrite(TTL).build();

    private final WebsiteRepository websites;
    private final DomainRepository domains;
    private final String publicScheme;
    private final String publicPort;

    public StudioSites(WebsiteRepository websites, DomainRepository domains,
                       @Value("${pacod.site.public-scheme:https}") String publicScheme,
                       @Value("${pacod.site.public-port:}") String publicPort) {
        this.websites = websites;
        this.domains = domains;
        this.publicScheme = publicScheme;
        this.publicPort = publicPort == null ? "" : publicPort.strip();
    }

    /**
     * One website on a studio screen.
     *
     * @param publicUrl the origin its readers use, without a trailing slash -- or {@code null} when it
     *                  has no domain yet. Banner previews are framed from here, never from the
     *                  studio's own origin.
     */
    public record SiteView(String id, String organisationId, String name, WebsiteStatus status,
                           String defaultLanguage, List<String> hosts, String publicUrl) {

        /** A studio address inside this website. */
        public String screen(String path) {
            return StudioPaths.siteScreen(id, path);
        }
    }

    /** Forgets the cached websites, after the studio has changed one. */
    public void evict() {
        current.invalidateAll();
    }

    /** Every website, by name. An unreadable control plane is an empty list and an ERROR log. */
    public List<SiteView> all() {
        try {
            return websites.findAllByOrderByNameAsc().stream().map(this::view).toList();
        } catch (DataAccessException e) {
            log.error("Could not list the websites: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The website bound to this request, if any.
     *
     * <p>Present whenever a website is bound, even if its record cannot be read: the screen is
     * already inside that website, and hiding its navigation over a failed lookup would strand the
     * operator on a page with no way to the next one. The fallback names the website by its id and
     * offers no public address.
     */
    public Optional<SiteView> current() {
        return TenantContext.find().map(tenant -> current.get(tenant.siteId(), id -> {
            try {
                return websites.findById(id).map(this::view)
                        .orElseGet(() -> fallback(id, tenant.organisationId(), tenant.defaultLanguage()));
            } catch (DataAccessException e) {
                log.error("Could not read website {}: {}", id, e.getMessage());
                return fallback(id, tenant.organisationId(), tenant.defaultLanguage());
            }
        }));
    }

    private static SiteView fallback(String id, String organisationId, String language) {
        return new SiteView(id, organisationId, id, null, language, List.of(), null);
    }

    private SiteView view(Website website) {
        List<Domain> hosts = domains.findByWebsiteId(website.getId()).stream()
                .sorted(Comparator.comparing(Domain::isPrimary).reversed().thenComparing(Domain::getHost))
                .toList();
        String publicUrl = hosts.isEmpty() ? null : originOf(hosts.getFirst().getHost());
        return new SiteView(website.getId(), website.getOrganisationId(),
                website.getName() == null ? website.getId() : website.getName(), website.getStatus(), website.getDefaultLanguage(), hosts.stream().map(Domain::getHost).toList(),
                publicUrl);
    }

    String originOf(String host) {
        return publicScheme + "://" + host + (publicPort.isEmpty() ? "" : ":" + publicPort);
    }
}
