package io.pacod.cms.core.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Which website a host, or a site id, belongs to -- read from the control plane and cached.
 *
 * <p>Asked on every request, so both answers are cached: a website for 60 seconds, the 60-second
 * bound every other cache on the platform keeps, and the absence of one for five -- short, so a
 * domain added to the control plane answers within seconds, but long enough that a scanner walking
 * random hosts costs one lookup per host rather than one per request.
 *
 * <p>Only {@link WebsiteStatus#ACTIVE} websites answer at their hosts. By id the studio can reach
 * any, provisioning ones included -- it is where they are finished.
 */
@Slf4j
@Component
public class TenantDirectory {

    private static final Duration FOUND_TTL = Duration.ofSeconds(60);
    private static final Duration MISSING_TTL = Duration.ofSeconds(5);

    private final WebsiteRepository websites;
    private final DomainRepository domains;

    private final Cache<String, Tenant> byHost = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(FOUND_TTL).build();
    private final Cache<String, Boolean> unknownHosts = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(MISSING_TTL).build();
    private final Cache<String, Tenant> bySite = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(FOUND_TTL).build();

    public TenantDirectory(WebsiteRepository websites, DomainRepository domains) {
        this.websites = websites;
        this.domains = domains;
    }

    /** The active website answering at {@code host}, if any. The port is ignored. */
    public Optional<Tenant> byHost(String host) {
        String key = Domain.normalise(host);
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        Tenant cached = byHost.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (unknownHosts.getIfPresent(key) != null) {
            return Optional.empty();
        }
        Optional<Tenant> found = domains.findById(key)
                .flatMap(domain -> websites.findById(domain.getWebsiteId()))
                .filter(website -> website.getStatus() == WebsiteStatus.ACTIVE)
                .map(TenantDirectory::tenantOf);
        found.ifPresentOrElse(t -> byHost.put(key, t), () -> unknownHosts.put(key, Boolean.TRUE));
        return found;
    }

    /** The website with this id, whatever its status. */
    public Optional<Tenant> bySite(String siteId) {
        if (siteId == null || !Website.ID.matcher(siteId).matches()) {
            return Optional.empty();
        }
        Tenant cached = bySite.getIfPresent(siteId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Tenant> found = websites.findById(siteId).map(TenantDirectory::tenantOf);
        found.ifPresent(t -> bySite.put(siteId, t));
        return found;
    }

    /**
     * Every active website, read fresh -- for background work that has to visit each one. An
     * unreadable control plane is an empty list and an ERROR log, never an exception: the caller
     * is a scheduled task, and the next run tries again.
     */
    public List<Tenant> active() {
        try {
            return websites.findByStatus(WebsiteStatus.ACTIVE).stream().map(TenantDirectory::tenantOf).toList();
        } catch (DataAccessException e) {
            log.error("Could not list the active websites: {}", e.getMessage());
            return List.of();
        }
    }

    /** Forgets everything cached, after the studio has changed a website or its domains. */
    public void evictAll() {
        byHost.invalidateAll();
        unknownHosts.invalidateAll();
        bySite.invalidateAll();
    }

    public static Tenant tenantOf(Website website) {
        ZoneId zone;
        try {
            zone = ZoneId.of(website.getTimezone() == null ? "UTC" : website.getTimezone());
        } catch (RuntimeException e) {
            zone = ZoneId.of("UTC");
        }
        return new Tenant(website.getId(), website.getOrganisationId(), website.getDbName(),
                website.getDefaultLanguage(), zone, website.getTemplateSet());
    }
}
