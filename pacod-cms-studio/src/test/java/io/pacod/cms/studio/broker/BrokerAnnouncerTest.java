package io.pacod.cms.studio.broker;

import com.sun.net.httpserver.HttpServer;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFileSetting;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.SiteFilesRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.studio.site.StudioSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The announcer against a stand-in broker: what an offered website becomes, an update under the same
 * lease when it changed, nothing sent when it did not, heartbeats when due, and a goodbye when the
 * organisation offers nothing more; the platform screen's round now, and its interval applying on
 * the next tick. The organisation's token comes from {@link BrokerProviders}.
 */
class BrokerAnnouncerTest {

    record Seen(String method, String path, String auth, String body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String base;
    /** What the stand-in answers a heartbeat; 404 is a lease it forgot. */
    private volatile int heartbeatStatus = 204;
    private volatile Duration interval = Duration.ofSeconds(60);

    private final WebsiteRepository websites = mock(WebsiteRepository.class);
    private final OrganisationRepository organisations = mock(OrganisationRepository.class);
    private final SiteSettingsRepository settings = mock(SiteSettingsRepository.class);
    private final SiteFilesRepository files = mock(SiteFilesRepository.class);
    private final StudioSites sites = mock(StudioSites.class);
    private final BrokerProviders providers = mock(BrokerProviders.class);
    private Website site;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (exchange.getRequestURI().getPath().endsWith("/heartbeat") && heartbeatStatus != 204) {
                byte[] problem = "{\"title\":\"Not Found\",\"detail\":\"no such lease\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(heartbeatStatus, problem.length);
                exchange.getResponseBody().write(problem);
                exchange.close();
                return;
            }
            String body = exchange.getRequestURI().getPath().equals("/v1/announce")
                    ? "{\"lease_id\":\"lse_1\",\"provider_id\":\"prv_1\",\"ttl_s\":60,\"heartbeat_every_s\":20,"
                    + "\"resources\":{\"journal\":\"res_0a0b0c0d\"}}"
                    : "";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body.isEmpty() ? 204 : 200, body.isEmpty() ? -1 : bytes.length);
            if (!body.isEmpty()) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        site = new Website("journal", "org_a", "Demo Journal");
        site.setStatus(WebsiteStatus.ACTIVE);
        site.setBrokerOffer("organisation");
        when(websites.findByStatus(WebsiteStatus.ACTIVE)).thenAnswer(i -> List.of(site));
        Organisation org = new Organisation();
        org.setName("Acme");
        when(organisations.findById("org_a")).thenReturn(Optional.of(org));
        SiteSettings s = new SiteSettings();
        s.setBaseUrl("https://journal.example/");
        when(settings.findById(SiteSettings.ID)).thenReturn(Optional.of(s));
        SiteFiles f = new SiteFiles();
        SiteFileSetting off = new SiteFileSetting();
        off.setMode(SiteFileMode.OFF);
        f.put(SiteFileKind.LLMS_FULL, off);
        when(files.findById(SiteFiles.ID)).thenReturn(Optional.of(f));
        when(providers.token(any())).thenReturn(Optional.of("pidt_org_a"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private BrokerAnnouncer announcer() {
        return new BrokerAnnouncer(websites, organisations, settings, files, sites, new BrokerClient(base, json),
                providers, () -> interval, 60);
    }

    @Test
    @DisplayName("an offered website is a dataset of its organisation's own provider, with the files it leaves on")
    void announcesAWebsite() {
        BrokerAnnouncer a = announcer();
        a.refreshAll(Instant.parse("2026-11-01T10:00:00Z"));
        assertThat(seen).hasSize(1);
        Seen put = seen.getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.auth()).isEqualTo("Bearer pidt_org_a");
        JsonNode body = json.readTree(put.body());
        assertThat(body.path("provider").path("name").asString()).isEqualTo("cms-org_a");
        assertThat(body.path("provider").path("title").asString()).isEqualTo("Acme");
        JsonNode r = body.path("resources").get(0);
        assertThat(r.path("key").asString()).isEqualTo("journal");
        assertThat(r.path("kind").asString()).isEqualTo("dataset");
        assertThat(r.path("access").path("visibility").asString()).isEqualTo("organisation:org_a");
        List<String> protocols = new java.util.ArrayList<>();
        r.path("interfaces").forEach(i -> protocols.add(i.path("protocol").asString() + " " + i.path("url").asString()));
        assertThat(protocols).containsExactly(
                "other:website https://journal.example/",
                "other:llms-txt https://journal.example/llms.txt",
                "other:atom https://journal.example/feed.xml",
                "other:sitemap https://journal.example/sitemap.xml");
        assertThat(a.announced()).containsEntry("journal", "res_0a0b0c0d");
    }

    @Test
    @DisplayName("unchanged, nothing is sent but the heartbeat when due; changed, an update under the same lease; withdrawn, a goodbye")
    void updatesHeartbeatsAndSaysGoodbye() {
        BrokerAnnouncer a = announcer();
        Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
        a.refreshAll(t0);
        a.refreshAll(t0.plusSeconds(5));
        assertThat(seen).hasSize(1);

        a.tick(t0.plusSeconds(21)); // the first tick after start rebuilds: still the same announcement
        a.tick(t0.plusSeconds(42));
        assertThat(seen.stream().filter(s -> s.path().endsWith("/heartbeat")).count()).isGreaterThanOrEqualTo(1);

        site.setBrokerSummary("Contracts, explained");
        a.refreshAll(t0.plusSeconds(60));
        Seen update = seen.stream().filter(s -> s.method().equals("PUT")).toList().getLast();
        JsonNode body = json.readTree(update.body());
        assertThat(body.path("lease_id").asString()).isEqualTo("lse_1");
        assertThat(body.path("resources").get(0).path("summary").asString()).isEqualTo("Contracts, explained");

        site.setBrokerOffer(null);
        a.refreshAll(t0.plusSeconds(120));
        assertThat(seen.getLast().method()).isEqualTo("DELETE");
        assertThat(seen.getLast().path()).isEqualTo("/v1/leases/lse_1");
        assertThat(a.announced()).isEmpty();
    }

    @Test
    @DisplayName("a round now sends nothing new, but heartbeats every unchanged lease at once and says what holds")
    void refreshNowConfirmsTheLeases() {
        BrokerAnnouncer a = announcer();
        a.tick(Instant.now());
        assertThat(seen).hasSize(1);
        Instant first = a.lastRefresh();

        BrokerAnnouncer.Round round = a.refreshNow();
        assertThat(round).isEqualTo(new BrokerAnnouncer.Round(1, 0));
        assertThat(seen).hasSize(2);
        assertThat(seen.getLast().method()).isEqualTo("POST");
        assertThat(seen.getLast().path()).isEqualTo("/v1/leases/lse_1/heartbeat");
        assertThat(a.lastRefresh()).isAfterOrEqualTo(first);
        assertThat(a.state()).singleElement().satisfies(st -> {
            assertThat(st.provider()).isEqualTo("cms-org_a");
            assertThat(st.resources()).containsEntry("journal", "res_0a0b0c0d");
            assertThat(st.error()).isNull();
        });
    }

    @Test
    @DisplayName("a round now announces afresh, in the same round, a lease the broker forgot")
    void refreshNowReannouncesAForgottenLease() {
        BrokerAnnouncer a = announcer();
        a.refreshAll(Instant.now());
        heartbeatStatus = 404;

        BrokerAnnouncer.Round round = a.refreshNow();
        assertThat(round.announced()).isEqualTo(1);
        List<Seen> puts = seen.stream().filter(x -> x.method().equals("PUT")).toList();
        assertThat(puts).hasSize(2);
        assertThat(json.readTree(puts.getLast().body()).has("lease_id")).isFalse();
        assertThat(a.announced()).containsEntry("journal", "res_0a0b0c0d");
    }

    @Test
    @DisplayName("a heartbeat the broker refuses otherwise, in a round now, is that organisation's failure")
    void refreshNowReportsAFailedHeartbeat() {
        BrokerAnnouncer a = announcer();
        a.refreshAll(Instant.now());
        heartbeatStatus = 503;

        BrokerAnnouncer.Round round = a.refreshNow();
        // The lease lives on, but this round could not confirm it: one organisation, counted once.
        assertThat(round).isEqualTo(new BrokerAnnouncer.Round(0, 1));
        assertThat(a.announced()).containsEntry("journal", "res_0a0b0c0d");
        assertThat(a.errors()).containsKey("org_a");
        assertThat(a.state()).singleElement().satisfies(st -> assertThat(st.error()).isNotNull());
    }

    @Test
    @DisplayName("the interval is read on every tick: a longer one waits, a shorter one applies at once")
    void theIntervalAppliesOnTheNextTick() {
        BrokerAnnouncer a = announcer();
        Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
        a.tick(t0);
        assertThat(a.lastRefresh()).isEqualTo(t0);
        assertThat(a.nextRefresh()).isEqualTo(t0.plusSeconds(60));

        interval = Duration.ofHours(1);
        a.tick(t0.plusSeconds(65));
        assertThat(a.lastRefresh()).isEqualTo(t0);
        assertThat(a.nextRefresh()).isEqualTo(t0.plusSeconds(3600));

        interval = Duration.ofSeconds(30);
        a.tick(t0.plusSeconds(70));
        assertThat(a.lastRefresh()).isEqualTo(t0.plusSeconds(70));
    }

    @Test
    @DisplayName("a lease the broker forgot on a tick's heartbeat makes the next tick a round, whatever the interval")
    void aForgottenLeaseIsAnnouncedAtTheNextTick() {
        BrokerAnnouncer a = announcer();
        Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
        a.tick(t0);
        heartbeatStatus = 404;
        a.tick(t0.plusSeconds(21));
        assertThat(a.announced()).isEmpty();
        // The screen says the next tick, not the last round plus the interval.
        assertThat(a.nextRefresh()).isNull();
        heartbeatStatus = 204;
        a.tick(t0.plusSeconds(26));
        assertThat(a.lastRefresh()).isEqualTo(t0.plusSeconds(26));
        assertThat(a.announced()).containsEntry("journal", "res_0a0b0c0d");
    }

    @Test
    @DisplayName("an organisation that stops offering leaves no failure behind")
    void aWithdrawnOrganisationForgetsItsFailure() {
        when(providers.token(any())).thenReturn(Optional.empty());
        BrokerAnnouncer a = announcer();
        a.refreshAll(Instant.now());
        assertThat(a.errors()).containsKey("org_a");
        site.setBrokerOffer(null);
        a.refreshAll(Instant.now());
        assertThat(a.errors()).isEmpty();
        assertThat(a.state()).isEmpty();
    }

    @Test
    @DisplayName("an organisation without a provider token is not announced, and says why")
    void noTokenNoAnnouncement() {
        when(providers.token(any())).thenReturn(Optional.empty());
        BrokerAnnouncer a = announcer();
        a.refreshAll(Instant.now());
        assertThat(seen).isEmpty();
        assertThat(a.errors()).containsKey("org_a");
    }

    @Test
    @DisplayName("open mode: no token, the client names itself and the provider on lease calls")
    void openModeLeaseCalls() {
        assertThat(BrokerClient.leasePath("lse_1", "/heartbeat", "", "cms-org_a"))
                .isEqualTo("/v1/leases/lse_1/heartbeat?provider=cms-org_a");
        assertThat(BrokerClient.leasePath("lse_1", "", "pidt_x", "cms-org_a")).isEqualTo("/v1/leases/lse_1");
    }
}
