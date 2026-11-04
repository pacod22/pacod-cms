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
 * organisation offers nothing more. The organisation's token comes from {@link BrokerProviders}.
 */
class BrokerAnnouncerTest {

    record Seen(String method, String path, String auth, String body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String base;

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
                providers, Duration.ofSeconds(60), 60);
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
