package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.studio.identity.IdProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses to start a studio that shares a registrable domain with the websites it creates.
 *
 * <p>A website runs its owner's script. On the studio's registrable domain that script could set
 * cookies the studio receives -- defeating its CSRF protection -- and the studio's own cookies would
 * travel with requests the website starts. So the studio's host (and pacod-id's, whose sign-in
 * cookies are as much at stake) must not share a registrable domain ({@link RegistrableDomains})
 * with {@code <id>.<site-domain>}. A site domain that is itself a public suffix -- {@code localhost}
 * in development, a tenant domain registered in the list's private section in production -- gives
 * every website a registrable domain of its own, and passes.
 *
 * <p>Checked when the bean is made, so a misconfigured installation fails to start, saying why,
 * rather than running exposed. Websites' own addresses are {@link DomainRules}' to refuse; one
 * stored before that rule existed is reported at ERROR once the studio is up, never a reason not
 * to start, which would take every website's studio down with it.
 *
 * <p>{@code pacod.portal.site-domain-shared} turns the refusal into a warning, and the stored
 * addresses' report from ERROR into WARN: an installation whose websites are all trusted (a demo
 * on one domain) accepts the risk, saying so in its log.
 */
@Slf4j
@Component
public class StudioDomainCheck {

    private final Map<String, String> guarded = new LinkedHashMap<>();
    private final DomainRepository domains;
    private final boolean scanStoredDomains;
    private final boolean shared;

    public StudioDomainCheck(IdProperties id, PortalProperties portal, DomainRepository domains,
                             @Value("${pacod.bootstrap.enabled:true}") boolean scanStoredDomains) {
        this.domains = domains;
        this.scanStoredDomains = scanStoredDomains;
        this.shared = portal.siteDomainShared();
        guard("the studio", id.studioOrigin());
        guard("sign-in (pacod-id)", id.issuer());
        verify(portal.siteDomain());
    }

    private void guard(String what, String url) {
        String host = hostOf(url);
        if (host == null) {
            return;
        }
        String registrable = RegistrableDomains.of(host);
        if (registrable == null) {
            log.warn("The address of {} ({}) is not a host name; whether it shares a domain with the websites "
                    + "cannot be checked", what, host);
            return;
        }
        guarded.put(what + " (" + host + ")", registrable);
    }

    /** @throws IllegalStateException when the websites' domain is the studio's or pacod-id's, unless shared */
    void verify(String siteDomain) {
        if (siteDomain == null || siteDomain.isBlank() || RegistrableDomains.isPublicSuffix(siteDomain)) {
            return;
        }
        String websites = RegistrableDomains.of(siteDomain);
        if (websites == null) {
            return;
        }
        guarded.forEach((what, registrable) -> {
            if (registrable.equals(websites) && shared) {
                log.warn("The websites (*.{}) share the registrable domain {} with {} (PACOD_SITE_DOMAIN_SHARED): "
                        + "a website's script can set cookies it receives. Run only websites you trust.",
                        siteDomain, websites, what);
            } else if (registrable.equals(websites)) {
                throw new IllegalStateException("The websites (*." + siteDomain + ") would share the registrable domain "
                        + websites + " with " + what + ": a website's script could then set cookies it receives. "
                        + "Give the websites (PACOD_SITE_DOMAIN) or the studio a domain of its own, or register "
                        + siteDomain + " in the Public Suffix List's private section.");
            }
        });
    }

    /** Stored addresses that share a guarded registrable domain, reported once the studio is up. */
    @EventListener(ApplicationReadyEvent.class)
    public void reportStoredDomains() {
        if (!scanStoredDomains) {
            return;
        }
        try {
            for (String finding : sharing(domains.findAll())) {
                if (shared) {
                    log.warn("{} -- shared on purpose (PACOD_SITE_DOMAIN_SHARED)", finding);
                } else {
                    log.error("{} -- a website's script there could set cookies the studio or sign-in receives; "
                            + "move the website to a domain of its own", finding);
                }
            }
        } catch (DataAccessException e) {
            log.error("Could not check the websites' addresses against the studio's domain: {}", e.getMessage());
        }
    }

    List<String> sharing(Iterable<Domain> stored) {
        List<String> findings = new ArrayList<>();
        for (Domain domain : stored) {
            String registrable = RegistrableDomains.of(domain.getHost());
            guarded.forEach((what, own) -> {
                if (own.equals(registrable)) {
                    findings.add("Website " + domain.getWebsiteId() + " answers at " + domain.getHost()
                            + ", on the registrable domain of " + what);
                }
            });
        }
        return findings;
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
