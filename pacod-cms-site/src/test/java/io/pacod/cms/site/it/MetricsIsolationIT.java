package io.pacod.cms.site.it;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.ReaderVisit;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.services.ReaderLogRecorder;
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
 * and the shutdown flush alike -- both of which run with no website bound of their own. And its
 * reader log rows reach that website's {@code readerVisit}, with the address behind the proxy.
 */
class MetricsIsolationIT extends TwoWebsitesIT {

    @Autowired
    SiteMetricCollector collector;

    @Autowired
    ReaderLogRecorder readerLog;

    @Test
    @DisplayName("reader log rows land in each website's own database, with X-Real-IP's address and the splits")
    void readerRowsStayApart() throws InterruptedException {
        offThread(readerLog::flush);

        TenantContext.runAs(alpha, () -> collector.recordPageView(BannerPage.ARTICLE, "en",
                proxied("203.0.113.41")));
        TenantContext.runAs(beta, () -> collector.recordPageView(BannerPage.ARTICLE, "en",
                proxied("203.0.113.42")));
        TenantContext.runAs(beta, () -> collector.recordPageView(BannerPage.LIST, "en",
                proxied("203.0.113.42")));
        offThread(readerLog::flush);

        assertThat(readerRows(alpha, "203.0.113.41")).isEqualTo(1);
        assertThat(readerRows(alpha, "203.0.113.42")).isZero();
        assertThat(readerRows(beta, "203.0.113.42")).isEqualTo(2);
        Document row = TenantContext.callAs(beta, () -> siteTemplate.getCollection(ReaderVisit.COLLECTION)
                .find(new Document(ReaderVisit.FIELD_IP, "203.0.113.42")).first());
        assertThat(row.getString(ReaderVisit.FIELD_BROWSER)).isEqualTo("FIREFOX");
        assertThat(row.getString(ReaderVisit.FIELD_BROWSER_LANGUAGE)).isEqualTo("de");
        assertThat(row.getString(ReaderVisit.FIELD_PATH)).isEqualTo("/article/1");
        // The TTL index the provisioner built is what keeps an address from staying forever.
        assertThat(TenantContext.callAs(beta, () -> siteTemplate.getCollection(ReaderVisit.COLLECTION)
                .listIndexes().into(new java.util.ArrayList<>())))
                .anySatisfy(index -> assertThat(index.get("expireAfterSeconds")).isNotNull());
    }

    private long readerRows(io.pacod.cms.core.tenant.Tenant tenant, String ip) {
        return TenantContext.callAs(tenant, () -> siteTemplate.getCollection(ReaderVisit.COLLECTION)
                .countDocuments(new Document(ReaderVisit.FIELD_IP, ip)));
    }

    /** A reader's request as Caddy hands it on: from the compose network, the client in X-Real-IP. */
    private static MockHttpServletRequest proxied(String client) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article/1");
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Real-IP", client);
        request.addHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0");
        request.addHeader("Accept-Language", "de-DE,de;q=0.9");
        return request;
    }

    @Test
    @DisplayName("interleaved views land in each website's own hour bucket")
    void interleavedViewsStayApart() throws InterruptedException {
        // Pages the other tests requested are still buffered; write them before the baseline.
        offThread(collector::flush);
        long alphaBefore = views(alpha);
        long betaBefore = views(beta);

        for (int i = 0; i < 3; i++) {
            TenantContext.runAs(alpha, () -> collector.recordPageView(BannerPage.ARTICLE, "en", reader("198.51.100.1")));
            if (i < 2) {
                TenantContext.runAs(beta, () -> collector.recordPageView(BannerPage.ARTICLE, "en", reader("198.51.100.2")));
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

        TenantContext.runAs(beta, () -> collector.recordPageView(BannerPage.LIST, "en", reader("198.51.100.3")));
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
