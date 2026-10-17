package io.pacod.cms.site.it;

import com.mongodb.client.MongoClient;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.SiteRetirement;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.services.SiteMetricCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A website from creation to deletion, against a real MongoDB: created, it answers at its host;
 * deleted, it answers nothing, its database and files are gone -- and stay gone when the counts it
 * collected in its last moments are flushed, which is the write that would bring the database back.
 */
class SiteLifecycleIT extends TwoWebsitesIT {

    private static final String GAMMA_HOST = "gamma.test";

    @Autowired
    SiteRetirement retirement;

    @Autowired
    SiteMetricCollector collector;

    @Autowired
    MongoClient mongo;

    @Test
    @DisplayName("create, serve, delete: the database and files go, and a late flush does not bring them back")
    void createServeDelete() throws Exception {
        provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "gamma", "Gamma Gazette", "en", "UTC",
                List.of(GAMMA_HOST), "usr_it"));
        Tenant gamma = directory.bySite("gamma").orElseThrow();
        TenantContext.runAs(gamma, () -> {
            Article article = new Article();
            article.setId(1);
            article.setTitle("Gamma -- the only story");
            article.setText("<p>Only in Gamma.</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);
        });
        Path files = Files.createDirectories(STORAGE.resolve("sites/gamma/assets/img/articles"));
        Files.writeString(files.resolve("cover.jpg"), "not really a picture");

        assertThat(get(GAMMA_HOST, "/article/1").statusCode()).isEqualTo(200);
        assertThat(get(GAMMA_HOST, "/article/1").body()).contains("Only in Gamma.");
        assertThat(databases()).contains("site_gamma");

        // Counted in its last moment, flushed after it is gone.
        TenantContext.runAs(gamma, () -> collector.recordPageView(BannerPage.ARTICLE, reader()));

        retirement.retire("gamma");
        assertThat(get(GAMMA_HOST, "/article/1").statusCode()).isEqualTo(404);
        retirement.erase("gamma", "usr_it");

        assertThat(databases()).doesNotContain("site_gamma");
        assertThat(STORAGE.resolve("sites/gamma")).doesNotExist();
        assertThat(controlPlane.getCollection("website").countDocuments(new org.bson.Document("_id", "gamma"))).isZero();
        assertThat(controlPlane.getCollection("domain").countDocuments(new org.bson.Document("websiteId", "gamma"))).isZero();

        offThread(collector::flush);
        assertThat(databases()).as("the flush must not recreate the deleted database").doesNotContain("site_gamma");

        // The id is refused while its database may still be written back, and swept meanwhile.
        assertThatThrownBy(() -> provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "gamma", "Again",
                "en", "UTC", List.of("gamma2.test"))))
                .isInstanceOf(IllegalArgumentException.class);
        retirement.sweep();
        assertThat(databases()).doesNotContain("site_gamma");

        // The neighbours never noticed.
        assertThat(get(ALPHA_HOST, "/article/1").statusCode()).isEqualTo(200);
        assertThat(databases()).contains(dbOf(alpha), dbOf(beta));
    }

    private List<String> databases() {
        return mongo.listDatabaseNames().into(new ArrayList<>());
    }

    private static MockHttpServletRequest reader() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article/1");
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("User-Agent", "Mozilla/5.0 (Macintosh) SiteLifecycleIT");
        return request;
    }

    private static void offThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }
}
