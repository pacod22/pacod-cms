package io.pacod.cms.site.it;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two websites on two template sets, through the real dispatcher, view resolution and error page:
 * each renders with its own set, however the requests interleave. The dispatching resolver caches
 * nothing itself; one that did would serve the first website's {@code article} to both.
 */
class TemplateSetIsolationIT extends TwoWebsitesIT {

    private static final String DELTA_HOST = "delta.test";

    @Test
    @DisplayName("each website renders its own set's views, interleaved, errors included")
    void setsStayApart() throws Exception {
        if (directory.bySite("delta").isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "delta", "Delta Digest", "en", "UTC",
                    List.of(DELTA_HOST)));
            controlPlane.updateFirst(Query.query(Criteria.where("_id").is("delta")),
                    Update.update("templateSet", "probe"), Website.class);
            directory.evictAll();
        }
        Tenant delta = directory.bySite("delta").orElseThrow();
        assertThat(delta.templateSet()).isEqualTo("probe");
        TenantContext.runAs(delta, () -> {
            Article article = new Article();
            article.setId(1);
            article.setTitle("Delta -- the probe's story");
            article.setText("<p>Only in Delta.</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);
        });

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                boolean editorial = i % 2 == 0;
                results.add(pool.submit(() -> {
                    HttpResponse<String> r = get(editorial ? ALPHA_HOST : DELTA_HOST, "/article/1");
                    return r.statusCode() == 200 && (editorial
                            ? r.body().contains("Alpha Daily") && !r.body().contains("probe-article")
                            : r.body().contains("id=\"probe-article\"") && r.body().contains("Delta -- the probe"));
                }));
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        HttpResponse<String> deltaMissing = get(DELTA_HOST, "/p/nothing-here");
        assertThat(deltaMissing.statusCode()).isEqualTo(404);
        assertThat(deltaMissing.body()).contains("id=\"probe-error\"");
        HttpResponse<String> alphaMissing = get(ALPHA_HOST, "/p/nothing-here");
        assertThat(alphaMissing.statusCode()).isEqualTo(404);
        assertThat(alphaMissing.body()).doesNotContain("probe-error").contains("Alpha Daily");
    }

    @Test
    @DisplayName("a set the installation does not have renders with the default")
    void unknownSetFallsBack() {
        assertThat(get(BETA_HOST, "/article/1").body()).contains("Beta Weekly").doesNotContain("probe-article");
    }
}
