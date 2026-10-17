package io.pacod.cms.site.services;

import java.util.List;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import io.pacod.cms.core.util.CrawlerClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The single choke point every banner number passes through: what reaches the time buckets, and
 * with which id.
 *
 * <p>The buffered all-time write is {@code flushPending}'s and is not re-tested here; what is new
 * is that the id travels on to {@link SiteMetricCollector}, which is what the monitoring screen's
 * per-booking columns are summed from.
 */
class BannerImpressionCounterTest {

    private MongoCollection<Document> bannerCollection;
    private SiteMetricCollector metrics;
    private BannerImpressionCounter counter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        bannerCollection = mock(MongoCollection.class);
        metrics = mock(SiteMetricCollector.class);
        when(mongoTemplate.getCollection("banner")).thenReturn(bannerCollection);

        counter = new BannerImpressionCounter(mongoTemplate, metrics, true);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("an impression reaches the time buckets under its booking's id")
    void impressionCarriesTheId() {
        counter.recordImpression(12);
        counter.recordImpression(12);

        verify(metrics, times(2)).recordBannerImpression(12);
    }

    @Test
    @DisplayName("a viewable impression reaches the buckets only -- there is no all-time scalar")
    void viewableGoesToTheBucketsOnly() {
        counter.recordViewable(12);

        verify(metrics).recordBannerViewable(12);
        verifyNoInteractions(bannerCollection);
    }

    @Test
    @DisplayName("a click reaches the buckets once the booking's own $inc applied")
    void clickCarriesTheId() {
        when(bannerCollection.updateOne(any(Document.class), any(Document.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(counter.recordClick(12)).isTrue();

        verify(metrics).recordBannerClick(12);
    }

    /**
     * A booking deleted while a page showing it was open matches nothing. Counting it in the
     * buckets anyway would put a click on the site total that no booking owns.
     */
    @Test
    @DisplayName("a click on a booking that no longer exists reaches no metric")
    void clickOnAMissingBookingIsNotCounted() {
        when(bannerCollection.updateOne(any(Document.class), any(Document.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(counter.recordClick(12)).isFalse();

        verify(metrics, never()).recordBannerClick(anyInt());
    }

    @Test
    @DisplayName("switched off, neither impressions nor viewable impressions are recorded")
    void disabledRecordsNothing() {
        BannerImpressionCounter disabled = new BannerImpressionCounter(mock(MongoTemplate.class), metrics, false);

        disabled.recordImpression(12);
        disabled.recordViewable(12);

        verifyNoInteractions(metrics);
    }

    /**
     * A page rendered for a crawler still carries its banners, and a crawler following an image
     * banner's link still gets the redirect -- but neither is something a person saw or did. Every
     * banner number passes through this class, so this is where they stop.
     */
    @Test
    @DisplayName("on a request the crawler filter marked, no impression, viewable or click is counted")
    void crawlersAreNotCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE, CrawlerClassifier.other());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        counter.recordImpression(12);
        counter.recordViewable(12);
        assertThat(counter.recordClick(12)).isFalse();

        verifyNoInteractions(metrics, bannerCollection);
    }

    @Test
    @DisplayName("on a studio preview, which no crawler sent, nothing is counted either")
    void previewsAreNotCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CrawlerTraffic.markUncounted(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        counter.recordImpression(12);
        counter.recordViewable(12);
        assertThat(counter.recordClick(12)).isFalse();

        verifyNoInteractions(metrics, bannerCollection);
    }

    private static final io.pacod.cms.core.tenant.Tenant SITE_A =
            new io.pacod.cms.core.tenant.Tenant("site-a", "org", "site_site-a", null, java.time.ZoneId.of("UTC"));
    private static final io.pacod.cms.core.tenant.Tenant SITE_B =
            new io.pacod.cms.core.tenant.Tenant("site-b", "org", "site_site-b", null, java.time.ZoneId.of("UTC"));

    /** Runs {@code work} on a thread of its own, where no website is bound -- as a scheduled flush is. */
    private static void offRequestThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }

    @Test
    @DisplayName("impressions are flushed per website, with that website bound")
    void impressionsAreFlushedPerWebsite() throws Exception {
        List<String> writes = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(bannerCollection.bulkWrite(org.mockito.ArgumentMatchers.<List<com.mongodb.client.model.WriteModel<Document>>>any()))
                .thenAnswer(invocation -> {
                    writes.add(io.pacod.cms.core.tenant.TenantContext.current().siteId() + "="
                            + ((List<?>) invocation.getArgument(0)).size());
                    return null;
                });

        // The same banner id on both: ids are only unique inside one website.
        io.pacod.cms.core.tenant.TenantContext.runAs(SITE_A, () -> {
            counter.recordImpression(12);
            counter.recordImpression(13);
        });
        io.pacod.cms.core.tenant.TenantContext.runAs(SITE_B, () -> counter.recordImpression(12));

        offRequestThread(counter::flush);

        assertThat(writes).containsExactlyInAnyOrder("site-a=2", "site-b=1");
    }
}
