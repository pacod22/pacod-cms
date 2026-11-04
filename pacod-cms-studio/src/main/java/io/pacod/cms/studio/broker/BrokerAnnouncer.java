package io.pacod.cms.studio.broker;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.SiteFilesRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.site.StudioSites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Announces each organisation's offered websites on pacod-broker as {@code dataset} resources,
 * under that organisation's own provider ({@link BrokerProviders}), and keeps the leases alive.
 *
 * <p>A website is offered on its settings screen ({@link Website#getBrokerOffer()}); only
 * {@code ACTIVE} websites with a public address are announced. Its interfaces are what the site
 * already serves to machines: the home page, {@code llms.txt}, {@code llms-full.txt}, the Atom
 * feed and the sitemap -- each only while the website's own setting leaves that file on.
 *
 * <p>A virtual thread started at {@code ApplicationReadyEvent}, like {@code IdentityLifecycle}:
 * it never blocks startup, and a broker that is down costs the announcements, never the studio.
 * Every {@link #TICK} it heartbeats what is due; every {@code pacod.broker.refresh} it rebuilds the
 * announcements and sends one only when it changed, under the same lease. An organisation with
 * nothing left to offer says goodbye. Each organisation fails on its own.
 */
@Slf4j
public class BrokerAnnouncer {

    static final Duration TICK = Duration.ofSeconds(5);
    public static final Set<String> OFFERS = Set.of("organisation", "platform", "public");

    /** One organisation's lease, as the broker answered its last announcement. */
    record Lease(String leaseId, String providerName, Map<String, Object> announcement, Duration every,
                 Instant nextBeat, Map<String, String> resources) {
    }

    private final WebsiteRepository websites;
    private final OrganisationRepository organisations;
    private final SiteSettingsRepository settings;
    private final SiteFilesRepository files;
    private final StudioSites sites;
    private final BrokerClient broker;
    private final BrokerProviders providers;
    private final Duration refresh;
    private final int ttlSeconds;
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private volatile Instant nextRefresh = Instant.EPOCH;
    private volatile Thread worker;

    public BrokerAnnouncer(WebsiteRepository websites, OrganisationRepository organisations,
                           SiteSettingsRepository settings, SiteFilesRepository files, StudioSites sites,
                           BrokerClient broker, BrokerProviders providers, Duration refresh, int ttlSeconds) {
        this.websites = websites;
        this.organisations = organisations;
        this.settings = settings;
        this.files = files;
        this.sites = sites;
        this.broker = broker;
        this.providers = providers;
        this.refresh = refresh;
        this.ttlSeconds = ttlSeconds;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker = Thread.ofVirtual().name("pacod-broker").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    tick(Instant.now());
                } catch (RuntimeException e) {
                    log.warn("pacod-broker round failed: {}", e.getMessage());
                }
                try {
                    Thread.sleep(TICK);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        // Goodbye, so consumers stop finding what no longer answers for this studio.
        for (String org : List.copyOf(leases.keySet())) {
            goodbye(org);
        }
    }

    /** One pass at {@code now}: a rebuild when due, else the heartbeats that are due. */
    void tick(Instant now) {
        if (!now.isBefore(nextRefresh)) {
            nextRefresh = now.plus(refresh);
            refreshAll(now);
            return;
        }
        for (Map.Entry<String, Lease> e : leases.entrySet()) {
            if (!now.isBefore(e.getValue().nextBeat())) {
                beat(e.getKey(), e.getValue(), now);
            }
        }
    }

    /** Rebuilds every organisation's announcement and sends what changed. */
    void refreshAll(Instant now) {
        Map<String, List<Website>> offered = new TreeMap<>();
        for (Website w : websites.findByStatus(WebsiteStatus.ACTIVE)) {
            if (w.getBrokerOffer() != null && OFFERS.contains(w.getBrokerOffer()) && w.getOrganisationId() != null) {
                offered.computeIfAbsent(w.getOrganisationId(), k -> new ArrayList<>()).add(w);
            }
        }
        Set<String> orgs = new java.util.TreeSet<>(offered.keySet());
        orgs.addAll(leases.keySet());
        for (String org : orgs) {
            try {
                Map<String, Object> announcement = offered.containsKey(org) ? announcement(org, offered.get(org)) : null;
                if (announcement == null) {
                    goodbye(org);
                } else {
                    announce(org, announcement, now);
                }
                errors.remove(org);
            } catch (RuntimeException e) {
                errors.put(org, String.valueOf(e.getMessage()));
                log.warn("pacod-broker: organisation {} not announced: {}", org, e.getMessage());
            }
        }
    }

    /** What organisation {@code org} offers now, or null when no offered website has an address. */
    Map<String, Object> announcement(String org, List<Website> offered) {
        List<Map<String, Object>> resources = new ArrayList<>();
        for (Website w : offered) {
            Map<String, Object> r = resource(org, w);
            if (r != null) {
                resources.add(r);
            }
        }
        if (resources.isEmpty()) {
            return null;
        }
        String title = organisations.findById(org).map(Organisation::getName).filter(n -> !n.isBlank()).orElse(org);
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("name", providerName(org));
        provider.put("title", clip(title, 120));
        provider.put("summary", clip("The websites of " + title + ", published with pacod-cms.", 300));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ttl_s", ttlSeconds);
        a.put("provider", provider);
        a.put("resources", resources);
        return a;
    }

    /** A website as a {@code dataset} resource, read with the website bound. */
    Map<String, Object> resource(String org, Website w) {
        return TenantContext.callAs(TenantDirectory.tenantOf(w), () -> {
            SiteSettings s = settings.findById(SiteSettings.ID).orElse(null);
            SiteFiles f = files.findById(SiteFiles.ID).orElseGet(SiteFiles::new);
            String origin = s != null && !s.baseUrlOrEmpty().isBlank() ? s.baseUrlOrEmpty().replaceAll("/+$", "")
                    : sites.byId(w.getId()).map(StudioSites.SiteView::publicUrl).orElse(null);
            if (origin == null || origin.isBlank()) {
                return null;
            }
            List<String> chain = List.of(w.getDefaultLanguage() == null ? "en" : w.getDefaultLanguage());
            String name = s != null && s.name(chain) != null && !s.name(chain).isBlank() ? s.name(chain) : w.getName();
            String description = s != null && s.description(chain) != null ? s.description(chain) : "";
            String summary = w.getBrokerSummary() != null && !w.getBrokerSummary().isBlank() ? w.getBrokerSummary()
                    : s != null && s.tagline(chain) != null && !s.tagline(chain).isBlank() ? s.tagline(chain) : description;
            List<Map<String, Object>> interfaces = new ArrayList<>();
            interfaces.add(Map.of("protocol", "other:website", "url", origin + "/"));
            if (f.isOn(SiteFileKind.LLMS)) {
                interfaces.add(Map.of("protocol", "other:llms-txt", "url", origin + SiteFileKind.LLMS.path()));
            }
            if (f.isOn(SiteFileKind.LLMS_FULL)) {
                interfaces.add(Map.of("protocol", "other:llms-full-txt", "url", origin + SiteFileKind.LLMS_FULL.path()));
            }
            interfaces.add(Map.of("protocol", "other:atom", "url", origin + "/feed.xml"));
            if (f.isOn(SiteFileKind.SITEMAP)) {
                interfaces.add(Map.of("protocol", "other:sitemap", "url", origin + SiteFileKind.SITEMAP.path()));
            }
            List<String> tags = new ArrayList<>(List.of("website", "pacod-cms", chain.getFirst()));
            // What the website's robots.txt already tells AI crawlers, said where consumers look.
            if (f.isBlockAiTraining()) {
                tags.add("no-ai-training");
            }
            if (f.isBlockAiAnswering()) {
                tags.add("no-ai-answering");
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", w.getId());
            r.put("kind", "dataset");
            r.put("name", w.getId());
            r.put("title", clip(name, 120));
            r.put("summary", clip(summary.isBlank() ? name : summary, 300));
            if (!description.isBlank()) {
                r.put("description", clip(description, 4000));
            }
            r.put("tags", tags);
            r.put("interfaces", interfaces);
            String offer = w.getBrokerOffer();
            r.put("access", Map.of("visibility", "organisation".equals(offer) ? "organisation:" + org : offer));
            return r;
        });
    }

    private void announce(String org, Map<String, Object> announcement, Instant now) {
        Lease lease = leases.get(org);
        if (lease != null && lease.announcement().equals(announcement)) {
            if (!now.isBefore(lease.nextBeat())) {
                beat(org, lease, now);
            }
            return;
        }
        String token = providers.token(org).orElseThrow(() -> new IllegalStateException("no provider token"));
        Map<String, Object> body = new LinkedHashMap<>(announcement);
        if (lease != null) {
            body.put("lease_id", lease.leaseId());
        }
        JsonNode answer;
        try {
            answer = broker.announce(token, body);
        } catch (BrokerClient.BrokerException e) {
            if (e.status() == 401) {
                providers.forget(org);
            }
            if (lease != null && e.notFound()) {
                // The lease ended meanwhile: announce afresh.
                leases.remove(org);
                answer = broker.announce(token, announcement);
            } else {
                throw e;
            }
        }
        remember(org, announcement, answer, now);
        log.info("pacod-broker: organisation {} announces {} website(s)", org,
                ((List<?>) announcement.get("resources")).size());
    }

    private void remember(String org, Map<String, Object> announcement, JsonNode answer, Instant now) {
        long every = Math.max(1, answer.path("heartbeat_every_s").asLong(Math.max(1, ttlSeconds / 3)));
        Map<String, String> resources = new HashMap<>();
        answer.path("resources").properties().forEach(e -> resources.put(e.getKey(), e.getValue().asString("")));
        leases.put(org, new Lease(answer.path("lease_id").asString(""), providerName(org), announcement,
                Duration.ofSeconds(every), now.plusSeconds(every), resources));
    }

    private void beat(String org, Lease lease, Instant now) {
        try {
            String token = providers.token(org).orElseThrow(() -> new IllegalStateException("no provider token"));
            broker.heartbeat(token, lease.leaseId(), lease.providerName());
            leases.put(org, new Lease(lease.leaseId(), lease.providerName(), lease.announcement(), lease.every(),
                    now.plus(lease.every()), lease.resources()));
        } catch (BrokerClient.BrokerException e) {
            if (e.notFound()) {
                // Expired at the broker: the next round announces afresh.
                leases.remove(org);
                nextRefresh = Instant.EPOCH;
            } else {
                if (e.status() == 401) {
                    providers.forget(org);
                }
                log.warn("pacod-broker: heartbeat of organisation {} failed: {}", org, e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("pacod-broker: heartbeat of organisation {} failed: {}", org, e.getMessage());
        }
    }

    private void goodbye(String org) {
        Lease lease = leases.remove(org);
        if (lease == null) {
            return;
        }
        try {
            broker.deregister(providers.token(org).orElse(""), lease.leaseId(), lease.providerName());
            log.info("pacod-broker: organisation {} no longer offers a website", org);
        } catch (RuntimeException e) {
            log.info("pacod-broker: goodbye of organisation {} failed ({}); its lease ends by itself", org, e.getMessage());
        }
    }

    /** The broker resource id of each announced website, by website id. */
    public Map<String, String> announced() {
        Map<String, String> out = new TreeMap<>();
        leases.values().forEach(l -> out.putAll(l.resources()));
        return out;
    }

    /** The last failure per organisation, for the screens. */
    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    static String providerName(String org) {
        return ("cms-" + org).toLowerCase(java.util.Locale.ROOT);
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
