package io.pacod.cms.site.it;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.TextItems;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.site.WebApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Two websites, {@code alpha} at {@code alpha.test} and {@code beta} at {@code beta.test}, in one
 * real MongoDB and one running public site.
 *
 * <p>The unit suite mocks the database, so it can only show that the code asks for the right thing.
 * These show what MongoDB and the servlet container actually do with it: that two databases hold
 * the same ids apart, that a cached value does not cross from one host to the other, that the
 * error dispatch still knows the website. Run with {@code make it}; they need Docker.
 *
 * <p>Both websites are created through {@link SiteProvisioner} -- the path the studio takes -- and
 * share one container and one application context across every subclass.
 */
@SpringBootTest(classes = WebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TwoWebsitesIT {

    static {
        // The Host header picks the website, and the JDK client refuses to set it otherwise.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    /**
     * Started once for every subclass and never stopped by them -- Testcontainers' reaper removes
     * it when the JVM exits. Not {@code @Container}: that stops it after each test class, while
     * Spring keeps the one cached application context pointed at the port it no longer listens on.
     */
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    static {
        MONGO.start();
    }

    static final Path STORAGE = tempDir();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("pacod.storage.root", STORAGE::toString);
        // Where the studio writes a website's pictures, so one written here is one the site serves.
        registry.add("app.image.folder", () -> STORAGE + "/sites/{site}/assets/img/articles");
        // The test profile switches the traffic counters off; these tests are about them. The
        // scheduled flush is pushed out of the way so only the tests' own flushes write.
        registry.add("app.metrics.enabled", () -> "true");
        registry.add("app.metrics.flush.ms", () -> "3600000");
        registry.add("pacod.studio.origin", () -> STUDIO_ORIGIN);
        registry.add("pacod.preview.secret", () -> "an-integration-test-secret-0123456789abcdef");
        // As compose runs the site (SERVER_FORWARD_HEADERS_STRATEGY): the PDF renderer names the
        // website in X-Forwarded-Host, which only the framework's filter applies.
        registry.add("server.forward-headers-strategy", () -> "framework");
    }

    static final String ALPHA_HOST = "alpha.test";
    static final String BETA_HOST = "beta.test";
    static final String STUDIO_ORIGIN = "http://studio.test:8000";

    @Autowired
    SiteProvisioner provisioner;

    @Autowired
    TenantDirectory directory;

    @Autowired
    ArticleRepository articles;

    @Autowired
    PageRepository pages;

    @Autowired
    SiteSettingsRepository settings;

    @Autowired
    MongoTemplate siteTemplate;

    @Autowired
    @Qualifier("controlPlaneMongoTemplate")
    MongoTemplate controlPlane;

    @LocalServerPort
    int port;

    /** HTTP/1.1: the default h2c upgrade is refused by the embedded Tomcat, which has no HTTP/2. */
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    Tenant alpha;
    Tenant beta;

    @BeforeAll
    void twoWebsites() {
        // An explicit organisation must exist: the provisioner refuses to invent one under an id it
        // was given, which would be an id pacod-id never generated.
        if (controlPlane.findById("org_it", io.pacod.cms.core.control.data.Organisation.class) == null) {
            controlPlane.save(new io.pacod.cms.core.control.data.Organisation("org_it", "IT"));
        }
        alpha = website("alpha", "Alpha Daily", ALPHA_HOST);
        beta = website("beta", "Beta Weekly", BETA_HOST);
    }

    /** Creates the website once -- the context, and so the database, is shared by every subclass. */
    private Tenant website(String id, String name, String host) {
        if (directory.bySite(id).isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", id, name, "en", "UTC", List.of(host)));
        }
        Tenant tenant = directory.bySite(id).orElseThrow();
        TenantContext.runAs(tenant, () -> {
            SiteSettings site = new SiteSettings();
            site.setNames(TextItems.put(List.of(), "en", name));
            settings.save(site);

            Page about = new Page("about");
            about.putTitle("en", "About " + name);
            about.putBody("en", "<p>We are " + name + ".</p>");
            about.setCreatedOn(LocalDateTime.now());
            about.setLastModifiedOn(LocalDateTime.now());
            pages.save(about);

            // The same id in both websites, with different content: the whole point.
            Article article = new Article();
            article.setId(1);
            article.setTitle(name + " -- first story");
            article.setText("<p>Only in " + name + ".</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);
        });
        return tenant;
    }

    HttpResponse<String> get(String host, String path) {
        return get(host, path, "text/html");
    }

    HttpResponse<String> get(String host, String path, String accept) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Host", host)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh) TwoWebsitesIT")
                    .header("Accept", accept)
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String dbOf(Tenant tenant) {
        return Website.dbNameFor(tenant.siteId());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("pacod-cms-it-storage");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
