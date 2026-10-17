package io.pacod.cms.site.it;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.services.SiteMetricCollector;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traffic recorded under one website reaches that website's buckets, through the scheduled flush
 * and the shutdown flush alike -- both of which run with no website bound of their own.
 */
class MetricsIsolationIT extends TwoWebsitesIT {

    @Autowired
    SiteMetricCollector collector;

    @Test
    @DisplayName("interleaved views land in each website's own hour bucket")
    void interleavedViewsStayApart() throws InterruptedException {
        // Pages the other tests requested are still buffered; write them before the baseline.
        offThread(collector::flush);
        long alphaBefore = views(alpha);
        long betaBefore = views(beta);

        for (int i = 0; i < 3; i++) {
            TenantContext.runAs(alpha, () -> collector.recordPageView(BannerPage.ARTICLE, reader("198.51.100.1")));
            if (i < 2) {
                TenantContext.runAs(beta, () -> collector.recordPageView(BannerPage.ARTICLE, reader("198.51.100.2")));
            }
        }
        offThread(collector::flush);

        assertThat(views(alpha) - alphaBefore).isEqualTo(3);
        assertThat(views(beta) - betaBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("the shutdown flush writes each website's pending counts too")
    void shutdownFlushIsPerWebsite() throws InterruptedException {
        // Pages the other tests requested are still buffered; write them before the baseline.
        offThread(collector::flush);
        long alphaBefore = views(alpha);
        long betaBefore = views(beta);

        TenantContext.runAs(beta, () -> collector.recordPageView(BannerPage.LIST, reader("198.51.100.3")));
        offThread(collector::flushOnShutdown);

        assertThat(views(alpha)).isEqualTo(alphaBefore);
        assertThat(views(beta) - betaBefore).isEqualTo(1);
    }

    private long views(io.pacod.cms.core.tenant.Tenant tenant) {
        return TenantContext.callAs(tenant, () -> {
            Document bucket = siteTemplate.getCollection(SiteMetricHour.COLLECTION)
                    .find(new Document("_id", SiteMetricHour.idFor(LocalDateTime.now()))).first();
            Object views = bucket == null ? null : bucket.get("views");
            return views == null ? 0L : ((Number) views).longValue();
        });
    }

    private static MockHttpServletRequest reader(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article/1");
        request.setRemoteAddr(address);
        request.addHeader("User-Agent", "Mozilla/5.0 (Macintosh) MetricsIsolationIT");
        return request;
    }

    private static void offThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }
}
