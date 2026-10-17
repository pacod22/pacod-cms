package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.services.WebArticleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Repositories and templates reach the bound website's database, and no other. */
class RoutingIsolationIT extends TwoWebsitesIT {

    @Autowired
    WebArticleService webArticles;

    @Test
    @DisplayName("one id, two websites, two documents")
    void sameIdInTwoDatabases() {
        String inAlpha = TenantContext.callAs(alpha, () -> articles.findById(1).map(Article::getTitle).orElseThrow());
        String inBeta = TenantContext.callAs(beta, () -> articles.findById(1).map(Article::getTitle).orElseThrow());

        assertThat(inAlpha).startsWith("Alpha Daily");
        assertThat(inBeta).startsWith("Beta Weekly");
        assertThat(siteTemplate.getMongoDatabaseFactory()).isNotNull();
    }

    @Test
    @DisplayName("with no website bound, a repository refuses rather than picking a database")
    void noWebsiteRefuses() throws InterruptedException {
        // Its own thread: the test runner binds a website around every test method.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                articles.findById(1);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();

        assertThat(failure.get()).isInstanceOf(TenantContext.NoTenantException.class);
    }

    @Test
    @DisplayName("a view counts against the website it was read in")
    void viewCounterIsPerWebsite() {
        int alphaBefore = counter(alpha);
        int betaBefore = counter(beta);

        TenantContext.runAs(alpha, () -> webArticles.getArticleById(1));
        TenantContext.runAs(alpha, () -> webArticles.getArticleById(1));
        TenantContext.runAs(beta, () -> webArticles.getArticleById(1));

        assertThat(counter(alpha)).isEqualTo(alphaBefore + 2);
        assertThat(counter(beta)).isEqualTo(betaBefore + 1);
    }

    @Test
    @DisplayName("platform records stay in the control plane, content stays out of it")
    void controlPlaneAndContentDoNotMix() {
        Set<String> control = controlPlane.getCollectionNames();
        assertThat(control).contains("website", "domain", "organisation");
        assertThat(control).doesNotContain("article", "page", "siteSettings");

        Set<String> content = TenantContext.callAs(alpha, siteTemplate::getCollectionNames);
        assertThat(content).contains("article", "page");
        assertThat(content).doesNotContain("website", "domain", "organisation", "user");

        assertThat(TenantContext.callAs(alpha, () -> siteTemplate.getDb().getName())).isEqualTo(dbOf(alpha));
    }

    private int counter(io.pacod.cms.core.tenant.Tenant tenant) {
        return TenantContext.callAs(tenant, () -> articles.findById(1).map(Article::getCounter).orElse(0));
    }
}
