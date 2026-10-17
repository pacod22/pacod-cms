package io.pacod.cms.site.services;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.SiteMetricDay;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.data.SiteVisitor;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.repository.SiteVisitorRepository;
import io.pacod.cms.core.util.CrawlerClassifier;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The traffic collector's write behaviour.
 *
 * <p>Same shape as {@code ArticleViewCounterTest}: the driver's collection is a mock, so what is
 * asserted is the exact update document that would be sent. That matters more here than usual --
 * the whole class exists to avoid a write per page render, and a regression to one would be
 * invisible from any screen.
 */
class SiteMetricCollectorTest {

    private MongoTemplate mongoTemplate;
    private MongoCollection<Document> hourCollection;
    private MongoCollection<Document> dayCollection;
    private MongoCollection<Document> visitorCollection;
    private SiteVisitorRepository visitorRepository;
    private SiteMetricCollector collector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        hourCollection = mock(MongoCollection.class);
        dayCollection = mock(MongoCollection.class);
        visitorCollection = mock(MongoCollection.class);
        visitorRepository = mock(SiteVisitorRepository.class);

        when(mongoTemplate.getCollection(SiteMetricHour.COLLECTION)).thenReturn(hourCollection);
        when(mongoTemplate.getCollection(SiteMetricDay.COLLECTION)).thenReturn(dayCollection);
        when(mongoTemplate.getCollection(SiteVisitor.COLLECTION)).thenReturn(visitorCollection);

        collector = newCollector(true);
    }

    private SiteMetricCollector newCollector(boolean enabled) {
        return new SiteMetricCollector(mongoTemplate, visitorRepository,
                new VisitorIdentity("test-salt"), enabled);
    }

    @Test
    @DisplayName("many page views cost one bucket write, not one write each")
    void viewsAreBuffered() {
        for (int i = 0; i < 50; i++) {
            collector.recordPageView(BannerPage.ARTICLE, request("1.2.3." + i));
        }

        // Nothing may reach MongoDB from the request thread: the whole point of the class.
        verify(hourCollection, never()).updateOne(any(Document.class), any(Document.class), any());

        collector.flush();

        // One counter write for fifty renders. The reader count rides along as a separate $max on
        // the same bucket, which is why this counts $inc writes rather than updateOne calls.
        assertThat(incrementUpdatesOf(hourCollection)).hasSize(1);
        assertThat(incrementsOf(hourCollection).getLong("views")).isEqualTo(50L);
    }

    @Test
    @DisplayName("the same counts go into the hour bucket and the day bucket in one flush")
    void bothGrainsAreWrittenTogether() {
        collector.recordPageView(BannerPage.LANDING, request("1.2.3.4"));
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.5"));
        collector.flush();

        // This is why there is no nightly rollup job -- and why a day can never disagree with the
        // sum of its hours.
        assertThat(incrementsOf(hourCollection)).isEqualTo(incrementsOf(dayCollection));
        assertThat(incrementsOf(dayCollection).getLong("views")).isEqualTo(2L);
    }

    @Test
    @DisplayName("per-page counts are a dotted $inc into the map, not a whole-document write")
    void perPageCountsUseADottedIncrement() {
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.5"));
        collector.recordPageView(BannerPage.LIST, request("1.2.3.6"));
        collector.flush();

        Document increments = incrementsOf(hourCollection);

        // Written through the raw driver precisely so this key reaches MongoDB verbatim rather
        // than going through Spring Data's property-path mapping.
        assertThat(increments.getLong("viewsByPage.ARTICLE")).isEqualTo(2L);
        assertThat(increments.getLong("viewsByPage.LIST")).isEqualTo(1L);
        assertThat(increments).doesNotContainKey("viewsByPage.LANDING");
    }

    @Test
    @DisplayName("a second flush carries only what arrived since the first")
    void countersResetOnFlush() {
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flush();
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.5"));
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.6"));
        collector.flush();

        List<Document> updates = incrementUpdatesOf(hourCollection);
        assertThat(((Document) updates.get(0).get("$inc")).getLong("views")).isEqualTo(1L);
        assertThat(((Document) updates.get(1).get("$inc")).getLong("views")).isEqualTo(2L);
    }

    @Test
    @DisplayName("start is only ever written on insert, so a bucket's TTL cannot be pushed forward")
    void startIsSetOnInsertOnly() {
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flush();

        Document update = incrementUpdatesOf(hourCollection).get(0);

        // $set here instead would refresh the TTL anchor every flush, and thirty days of hourly
        // buckets would quietly become forever.
        assertThat(update).containsKey("$setOnInsert");
        assertThat(((Document) update.get("$setOnInsert"))).containsKey("start");
        assertThat(update.get("$set")).isNull();
    }

    @Test
    @DisplayName("a failed bucket write returns the counts to the buffer instead of losing them")
    void aFailedFlushIsRetried() {
        when(hourCollection.updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class)))
                .thenThrow(new IllegalStateException("no server"))
                .thenReturn(null);

        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flush();
        collector.flush();

        // A failed flush must cost a retry, not the traffic. Same contract as
        // BannerImpressionCounter.flushPending. Two attempts reach the captor -- the throwing one
        // and the retry -- and the retry must still carry the view the first attempt lost.
        List<Document> attempts = incrementUpdatesOf(hourCollection);
        assertThat(attempts).hasSize(2);
        assertThat(((Document) attempts.get(1).get("$inc")).getLong("views")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a grain that failed retries alone; the one that succeeded is not written twice")
    void grainsRetryIndependently() {
        // Two collections cannot share a bulk write, so a flush can succeed on one and fail on
        // the other. With a single shared buffer, restoring the counts would re-apply them to the
        // grain that had already taken them -- an inflated hour bucket, forever, with nothing on
        // any screen to show for it. This is the case that pins the two-buffer design.
        when(dayCollection.updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class)))
                .thenThrow(new IllegalStateException("no server"))
                .thenReturn(null);

        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flush();
        collector.flush();

        // The hour took its one view on the first flush and must not see it again.
        assertThat(incrementUpdatesOf(hourCollection)).singleElement()
                .satisfies(u -> assertThat(((Document) u.get("$inc")).getLong("views")).isEqualTo(1L));

        // The day retried and still carries it.
        List<Document> dayAttempts = incrementUpdatesOf(dayCollection);
        assertThat(dayAttempts).hasSize(2);
        assertThat(((Document) dayAttempts.get(1).get("$inc")).getLong("views")).isEqualTo(1L);
    }

    @Test
    @DisplayName("readers are heartbeated in one bulk write, and the TTL anchor is set on insert")
    void visitorsAreBulkWritten() {
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.5"));
        // Same reader again: one row, not two.
        collector.recordPageView(BannerPage.LIST, request("1.2.3.4"));
        collector.flush();

        ArgumentCaptor<List<WriteModel<Document>>> writes = ArgumentCaptor.forClass(List.class);
        verify(visitorCollection).bulkWrite(writes.capture());

        assertThat(writes.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("the hourly reader count is a $max, so a restart undercounts rather than overwrites")
    void hourlyReadersUseMax() {
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.5"));
        collector.flush();

        Document max = updatesOf(hourCollection).stream()
                .map(u -> (Document) u.get("$max"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();

        // $set would let a mid-hour restart overwrite a real count with the handful of readers
        // seen since. The hash set lives in memory; the daily figure, which does not, is a $set.
        assertThat(max.getLong("visitors")).isEqualTo(2L);
    }

    @Test
    @DisplayName("the daily reader count is counted off the collection, not summed from the hours")
    void dailyReadersAreCounted() {
        when(visitorRepository.countByDay(any())).thenReturn(37L);

        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flush();

        Document set = updatesOf(dayCollection).stream()
                .map(u -> (Document) u.get("$set"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();

        // Summing twenty-four hourly figures would count anyone who came back twice.
        assertThat(set.getLong("visitors")).isEqualTo(37L);
    }

    @Test
    @DisplayName("banner impressions and clicks land on the same buckets")
    void bannerActivityIsCollected() {
        collector.recordBannerImpression(12);
        collector.recordBannerImpression(12);
        collector.recordBannerImpression(3);
        collector.recordBannerViewable(12);
        collector.recordBannerClick(12);
        collector.flush();

        for (MongoCollection<Document> grain : List.of(hourCollection, dayCollection)) {
            Document increments = incrementsOf(grain);
            assertThat(increments.getLong("bannerImpressions")).isEqualTo(3L);
            assertThat(increments.getLong("bannerClicks")).isEqualTo(1L);
            // Dotted paths, one per booking that moved -- the viewsByPage mechanism. The id is a
            // key inside an embedded document; SiteMetricMappingTest reads that shape back.
            assertThat(increments.getLong("bannerImpressionsById.12")).isEqualTo(2L);
            assertThat(increments.getLong("bannerImpressionsById.3")).isEqualTo(1L);
            assertThat(increments.getLong("bannerViewableById.12")).isEqualTo(1L);
            assertThat(increments.getLong("bannerClicksById.12")).isEqualTo(1L);
            // Viewable has no site-wide scalar, and a booking with nothing to report is absent.
            assertThat(increments).doesNotContainKeys("bannerViewable", "bannerViewableById.3",
                    "bannerClicksById.3");
        }
    }

    @Test
    @DisplayName("a failed bucket write returns the per-booking counts too")
    void aFailedFlushKeepsPerBannerCounts() {
        when(hourCollection.updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class)))
                .thenThrow(new IllegalStateException("no server"))
                .thenReturn(null);

        collector.recordBannerImpression(12);
        collector.recordBannerViewable(12);
        collector.recordBannerClick(12);
        collector.flush();
        collector.recordBannerImpression(12);
        collector.flush();

        Document retry = (Document) incrementUpdatesOf(hourCollection).get(1).get("$inc");
        assertThat(retry.getLong("bannerImpressionsById.12")).isEqualTo(2L);
        assertThat(retry.getLong("bannerViewableById.12")).isEqualTo(1L);
        assertThat(retry.getLong("bannerClicksById.12")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a viewable impression alone still reaches the bucket")
    void viewableAloneIsWritten() {
        collector.recordBannerViewable(5);
        collector.flush();

        assertThat(incrementsOf(hourCollection)).containsOnlyKeys("bannerViewableById.5");
    }

    @Test
    @DisplayName("a flush with nothing buffered writes nothing at all")
    void anIdleFlushIsSilent() {
        collector.flush();

        verify(hourCollection, never()).updateOne(any(Document.class), any(Document.class), any());
        verify(visitorCollection, never()).bulkWrite(anyList());
    }

    @Test
    @DisplayName("the property switches the whole thing off, recording included")
    void disabledCollectsNothing() {
        SiteMetricCollector disabled = newCollector(false);

        disabled.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        disabled.recordBannerImpression(1);
        disabled.recordBannerViewable(1);
        disabled.recordBannerClick(1);
        disabled.flush();

        // The test profile relies on this: the scheduled flush would otherwise spend the suite
        // failing to reach a MongoDB that is not there.
        verifyNoInteractions(hourCollection, dayCollection, visitorCollection);
    }

    @Test
    @DisplayName("shutdown flushes, so an orderly restart does not drop the last interval")
    void shutdownFlushes() {
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flushOnShutdown();

        verify(hourCollection, atLeastOnce()).updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("a /social view is a page view, a SOCIAL page view and a count under its source")
    void socialViewsAreCountedBySource() {
        collector.recordSocialView(SocialSource.INSTAGRAM, request("1.2.3.4"));
        collector.recordSocialView(SocialSource.INSTAGRAM, request("1.2.3.5"));
        collector.recordSocialView(SocialSource.FACEBOOK, request("1.2.3.4"));
        collector.flush();

        Document increments = incrementsOf(hourCollection);
        // The three agree by construction: one call writes all of them.
        assertThat(increments.getLong("views")).isEqualTo(3L);
        assertThat(increments.getLong("viewsByPage.SOCIAL")).isEqualTo(3L);
        assertThat(increments.getLong("socialViewsBySource.INSTAGRAM")).isEqualTo(2L);
        assertThat(increments.getLong("socialViewsBySource.FACEBOOK")).isEqualTo(1L);
        assertThat(incrementsOf(dayCollection)).isEqualTo(increments);
    }

    @Test
    @DisplayName("/social readers are a $max on both grains, counted per page, not per site")
    void socialReadersUseMaxOnBothGrains() {
        collector.recordSocialView(SocialSource.INSTAGRAM, request("1.2.3.4"));
        collector.recordSocialView(SocialSource.DIRECT, request("1.2.3.4"));
        collector.recordSocialView(SocialSource.DIRECT, request("1.2.3.5"));
        // A reader of another page is a site reader, not a /social one.
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.6"));
        collector.flush();

        for (MongoCollection<Document> grain : List.of(hourCollection, dayCollection)) {
            Document max = updatesOf(grain).stream()
                    .map(u -> (Document) u.get("$max"))
                    .filter(m -> m != null && m.containsKey("socialVisitors"))
                    .findFirst()
                    .orElseThrow();
            assertThat(max.getLong("socialVisitors")).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("/social clicks are a nested dotted $inc: link, then source")
    void socialClicksAreANestedIncrement() {
        collector.recordSocialClick("k3x9a2bq", SocialSource.INSTAGRAM);
        collector.recordSocialClick("k3x9a2bq", SocialSource.INSTAGRAM);
        collector.recordSocialClick("k3x9a2bq", SocialSource.FACEBOOK);
        collector.recordSocialArticleClick(3150, SocialSource.TIKTOK);
        collector.flush();

        Document increments = incrementsOf(hourCollection);
        assertThat(increments.getLong("socialClicks.k3x9a2bq.INSTAGRAM")).isEqualTo(2L);
        assertThat(increments.getLong("socialClicks.k3x9a2bq.FACEBOOK")).isEqualTo(1L);
        assertThat(increments.getLong("socialArticleClicks.3150.TIKTOK")).isEqualTo(1L);
        // A click is not a page view.
        assertThat(increments).doesNotContainKey("views");
        assertThat(incrementsOf(dayCollection)).isEqualTo(increments);
    }

    @Test
    @DisplayName("a link id that is not the generated shape never becomes a key")
    void malformedLinkIdsAreDropped() {
        collector.recordSocialClick("a.b.c.d.", SocialSource.INSTAGRAM);
        collector.recordSocialClick("$where", SocialSource.INSTAGRAM);
        collector.recordSocialClick(null, SocialSource.INSTAGRAM);
        collector.flush();

        verify(hourCollection, never()).updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("/social counts retry per grain, like everything else in the bucket")
    void socialCountsRetryIndependently() {
        when(dayCollection.updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class)))
                .thenThrow(new IllegalStateException("no server"))
                .thenReturn(null);

        collector.recordSocialClick("k3x9a2bq", SocialSource.INSTAGRAM);
        collector.recordSocialView(SocialSource.INSTAGRAM, request("1.2.3.4"));
        collector.flush();
        collector.flush();

        assertThat(incrementUpdatesOf(hourCollection)).singleElement().satisfies(u -> {
            Document inc = (Document) u.get("$inc");
            assertThat(inc.getLong("socialClicks.k3x9a2bq.INSTAGRAM")).isEqualTo(1L);
            assertThat(inc.getLong("socialViewsBySource.INSTAGRAM")).isEqualTo(1L);
        });

        List<Document> dayAttempts = incrementUpdatesOf(dayCollection);
        assertThat(dayAttempts).hasSize(2);
        Document retried = (Document) dayAttempts.get(1).get("$inc");
        assertThat(retried.getLong("socialClicks.k3x9a2bq.INSTAGRAM")).isEqualTo(1L);
        assertThat(retried.getLong("socialViewsBySource.INSTAGRAM")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a request the crawler filter marked counts no view and no reader")
    void automatedRequestsAreNotReaders() {
        MockHttpServletRequest crawler = request("66.249.66.1");
        crawler.setAttribute(CrawlerTraffic.REQUEST_ATTRIBUTE,
                CrawlerClassifier.classify("Mozilla/5.0 (compatible; Googlebot/2.1)"));

        collector.recordPageView(BannerPage.ARTICLE, crawler);
        collector.recordPageView(BannerPage.ARTICLE, request("1.2.3.4"));
        collector.flush();

        assertThat(incrementsOf(hourCollection).getLong("views")).isEqualTo(1L);
        // One heartbeat row: the reader's. The crawler never reaches siteVisitor, so the live
        // count and the daily readers do not see it either.
        ArgumentCaptor<List<WriteModel<Document>>> writes = ArgumentCaptor.forClass(List.class);
        verify(visitorCollection).bulkWrite(writes.capture());
        assertThat(writes.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("crawler hits are dotted $incs by bot id, with errors and verification beside them")
    void crawlerHitsAreCountedByBot() {
        collector.recordCrawlerHit("googlebot", 200, CrawlerVerification.VERIFIED);
        collector.recordCrawlerHit("googlebot", 404, CrawlerVerification.UNVERIFIED);
        collector.recordCrawlerHit("other", 200, CrawlerVerification.UNKNOWN);
        collector.flush();

        Document inc = incrementsOf(hourCollection);
        assertThat(inc.getLong("crawlerHitsByBot.googlebot")).isEqualTo(2L);
        assertThat(inc.getLong("crawlerHitsByBot.other")).isEqualTo(1L);
        assertThat(inc.getLong("crawlerErrorsByBot.googlebot")).isEqualTo(1L);
        assertThat(inc.getLong("crawlerVerifiedByBot.googlebot")).isEqualTo(1L);
        assertThat(inc.getLong("crawlerUnverifiedByBot.googlebot")).isEqualTo(1L);
        assertThat(inc).doesNotContainKey("crawlerErrorsByBot.other")
                .doesNotContainKey("crawlerVerifiedByBot.other")
                .doesNotContainKey("views");
        assertThat(incrementsOf(dayCollection)).isEqualTo(inc);
    }

    @Test
    @DisplayName("a bot id that would not be a safe key is dropped")
    void malformedBotIdsAreDropped() {
        collector.recordCrawlerHit("evil.bot", 200, CrawlerVerification.UNKNOWN);
        collector.recordCrawlerHit("$where", 200, CrawlerVerification.UNKNOWN);
        collector.recordCrawlerHit(null, 200, CrawlerVerification.UNKNOWN);
        collector.flush();

        verifyNoInteractions(hourCollection, dayCollection);
    }

    @Test
    @DisplayName("crawler counts retry per grain, like everything else in the bucket")
    void crawlerCountsRetryIndependently() {
        when(dayCollection.updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class)))
                .thenThrow(new IllegalStateException("no server"))
                .thenReturn(null);

        collector.recordCrawlerHit("bingbot", 500, CrawlerVerification.VERIFIED);
        collector.flush();
        collector.flush();

        assertThat(incrementUpdatesOf(hourCollection)).singleElement()
                .satisfies(u -> assertThat(((Document) u.get("$inc")).getLong("crawlerHitsByBot.bingbot"))
                        .isEqualTo(1L));

        List<Document> dayAttempts = incrementUpdatesOf(dayCollection);
        assertThat(dayAttempts).hasSize(2);
        Document retried = (Document) dayAttempts.get(1).get("$inc");
        assertThat(retried.getLong("crawlerHitsByBot.bingbot")).isEqualTo(1L);
        assertThat(retried.getLong("crawlerErrorsByBot.bingbot")).isEqualTo(1L);
        assertThat(retried.getLong("crawlerVerifiedByBot.bingbot")).isEqualTo(1L);
    }

    private Document incrementsOf(MongoCollection<Document> collection) {
        return incrementUpdatesOf(collection).stream()
                .map(u -> (Document) u.get("$inc"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no $inc reached " + collection));
    }

    /**
     * The counter writes only.
     *
     * <p>A flush touches a bucket more than once -- the additive {@code $inc}, then the reader
     * count as a {@code $max} or a {@code $set} -- so an assertion by call index would be reading
     * whichever happened to come first.
     */
    private List<Document> incrementUpdatesOf(MongoCollection<Document> collection) {
        return updatesOf(collection).stream().filter(u -> u.get("$inc") != null).toList();
    }

    private List<Document> updatesOf(MongoCollection<Document> collection) {
        ArgumentCaptor<Document> update = ArgumentCaptor.forClass(Document.class);
        verify(collection, atLeastOnce())
                .updateOne(any(Document.class), update.capture(), any(UpdateOptions.class));
        return update.getAllValues();
    }

    private static MockHttpServletRequest request(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        request.addHeader("User-Agent", "Firefox");
        return request;
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
    @DisplayName("each website's counts are flushed with that website bound, never mixed")
    void websitesAreFlushedSeparately() throws Exception {
        List<String> writes = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(hourCollection.updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class)))
                .thenAnswer(invocation -> {
                    Document increments = (Document) ((Document) invocation.getArgument(1)).get("$inc");
                    if (increments != null) {
                        writes.add(io.pacod.cms.core.tenant.TenantContext.current().siteId()
                                + "=" + increments.get("views"));
                    }
                    return null;
                });

        io.pacod.cms.core.tenant.TenantContext.runAs(SITE_A, () -> {
            collector.recordPageView(BannerPage.ARTICLE, request("1.1.1.1"));
            collector.recordPageView(BannerPage.ARTICLE, request("1.1.1.2"));
        });
        io.pacod.cms.core.tenant.TenantContext.runAs(SITE_B,
                () -> collector.recordPageView(BannerPage.ARTICLE, request("2.2.2.2")));

        offRequestThread(collector::flush);

        assertThat(writes).containsExactlyInAnyOrder("site-a=2", "site-b=1");
    }

    @Test
    @DisplayName("with no website bound nothing is recorded, and nothing is written anywhere")
    void noWebsiteRecordsNothing() throws Exception {
        offRequestThread(() -> collector.recordPageView(BannerPage.ARTICLE, request("1.1.1.1")));
        offRequestThread(collector::flush);

        verify(hourCollection, never()).updateOne(any(Document.class), any(Document.class), any());
    }

    @Test
    @DisplayName("one reader of two websites is two unrelated reader keys")
    void readerKeysAreSaltedWithTheWebsite() {
        VisitorIdentity identity = new VisitorIdentity("test-salt");
        MockHttpServletRequest reader = request("1.2.3.4");
        String a = io.pacod.cms.core.tenant.TenantContext.callAs(SITE_A, () -> identity.hashOf(reader));
        String b = io.pacod.cms.core.tenant.TenantContext.callAs(SITE_B, () -> identity.hashOf(reader));

        assertThat(a).isNotNull().isNotEqualTo(b);
    }
}
