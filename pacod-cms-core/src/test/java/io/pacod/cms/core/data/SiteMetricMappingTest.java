package io.pacod.cms.core.data;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads a bucket in the shape MongoDB <em>sends</em>, not the shape the code expects.
 *
 * <p>{@code SiteMetricCollector} writes the per-banner maps with a dotted {@code $inc} on
 * {@code bannerImpressionsById.12}, which MongoDB stores as an embedded document whose key is the
 * string {@code "12"}. The console then reads it back through the mapper. The writer's test can
 * only see the update it sends and the console's tests build buckets in Java, so this is the one
 * place the round trip between the two is checked -- a test that builds the shape it expects
 * passes against code that misreads the real one (see {@code MgmtDbRefs.idOf}).
 */
class SiteMetricMappingTest {

    private final MappingMongoConverter converter = converter();

    @Test
    @DisplayName("the per-banner maps read back keyed by the id as a string")
    void perBannerMapsReadBack() {
        Document raw = new Document("_id", "2026-10-11T14")
                .append(SiteMetric.FIELD_START, new Date())
                .append(SiteMetric.FIELD_BANNER_IMPRESSIONS, 9L)
                .append(SiteMetric.FIELD_BANNER_IMPRESSIONS_BY_ID, new Document("12", 5L).append("3", 4L))
                .append(SiteMetric.FIELD_BANNER_VIEWABLE_BY_ID, new Document("12", 2L))
                // A hand-written $inc in mongosh stores an Int32; the reader must still see a Long.
                .append(SiteMetric.FIELD_BANNER_CLICKS_BY_ID, new Document("12", 1));

        SiteMetricHour hour = converter.read(SiteMetricHour.class, raw);

        assertThat(hour.getBannerImpressionsById()).containsEntry("12", 5L).containsEntry("3", 4L);
        assertThat(hour.getBannerViewableById()).containsEntry("12", 2L);
        assertThat(hour.getBannerClicksById().get("12")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a bucket without the per-banner maps reads as empty maps, never null")
    void olderBucketsReadAsEmpty() {
        Document raw = new Document("_id", "2026-10-11")
                .append(SiteMetric.FIELD_START, new Date())
                .append(SiteMetric.FIELD_BANNER_IMPRESSIONS, 40L);

        SiteMetricDay day = converter.read(SiteMetricDay.class, raw);

        assertThat(day.getBannerImpressions()).isEqualTo(40L);
        assertThat(day.getBannerImpressionsById()).isEmpty();
        assertThat(day.getBannerViewableById()).isEmpty();
        assertThat(day.getBannerClicksById()).isEmpty();
    }

    @Test
    @DisplayName("the /social maps read back nested, keyed by string, Int32 included")
    void socialMapsReadBack() {
        // The shape a dotted $inc on socialClicks.k3x9a2bq.INSTAGRAM leaves behind.
        Document raw = new Document("_id", "2026-10-11T14")
                .append(SiteMetric.FIELD_START, new Date())
                .append(SiteMetric.FIELD_SOCIAL_VIEWS_BY_SOURCE, new Document("INSTAGRAM", 7L).append("DIRECT", 2))
                .append(SiteMetric.FIELD_SOCIAL_CLICKS, new Document("k3x9a2bq",
                        new Document("INSTAGRAM", 3L).append("FACEBOOK", 1)))
                .append(SiteMetric.FIELD_SOCIAL_ARTICLE_CLICKS, new Document("3150", new Document("INSTAGRAM", 1)))
                .append(SiteMetric.FIELD_SOCIAL_VISITORS, 4);

        SiteMetricHour hour = converter.read(SiteMetricHour.class, raw);

        assertThat(hour.getSocialViewsBySource()).containsEntry("INSTAGRAM", 7L);
        assertThat(hour.getSocialViewsBySource().get("DIRECT")).isEqualTo(2L);
        assertThat(hour.getSocialClicks().get("k3x9a2bq")).containsEntry("INSTAGRAM", 3L);
        assertThat(hour.getSocialClicks().get("k3x9a2bq").get("FACEBOOK")).isEqualTo(1L);
        assertThat(hour.getSocialArticleClicks().get("3150").get("INSTAGRAM")).isEqualTo(1L);
        assertThat(hour.getSocialVisitors()).isEqualTo(4L);
        assertThat(hour.socialViewTotal()).isEqualTo(9L);
        assertThat(hour.socialClickTotal()).isEqualTo(5L);
    }

    @Test
    @DisplayName("a bucket without /social counts reads as empty social maps")
    void olderBucketsHaveNoSocialMaps() {
        Document raw = new Document("_id", "2026-10-11").append(SiteMetric.FIELD_START, new Date());

        SiteMetricDay day = converter.read(SiteMetricDay.class, raw);

        assertThat(day.getSocialViewsBySource()).isEmpty();
        assertThat(day.getSocialClicks()).isEmpty();
        assertThat(day.getSocialArticleClicks()).isEmpty();
        assertThat(day.socialClickTotal()).isZero();
    }

    @Test
    @DisplayName("the crawler maps read back keyed by bot id, Int32 included")
    void crawlerMapsReadBack() {
        // The shape dotted $incs on crawlerHitsByBot.googlebot and friends leave behind.
        Document raw = new Document("_id", "2026-10-11T14")
                .append(SiteMetric.FIELD_START, new Date())
                .append(SiteMetric.FIELD_CRAWLER_HITS_BY_BOT, new Document("googlebot", 7L).append("other", 3))
                .append(SiteMetric.FIELD_CRAWLER_ERRORS_BY_BOT, new Document("googlebot", 1))
                .append(SiteMetric.FIELD_CRAWLER_VERIFIED_BY_BOT, new Document("googlebot", 6L))
                .append(SiteMetric.FIELD_CRAWLER_UNVERIFIED_BY_BOT, new Document("googlebot", 1L));

        SiteMetricHour hour = converter.read(SiteMetricHour.class, raw);

        assertThat(hour.getCrawlerHitsByBot()).containsEntry("googlebot", 7L);
        assertThat(hour.getCrawlerHitsByBot().get("other")).isEqualTo(3L);
        assertThat(hour.getCrawlerErrorsByBot().get("googlebot")).isEqualTo(1L);
        assertThat(hour.getCrawlerVerifiedByBot()).containsEntry("googlebot", 6L);
        assertThat(hour.getCrawlerUnverifiedByBot()).containsEntry("googlebot", 1L);
        assertThat(hour.crawlerHitTotal()).isEqualTo(10L);
    }

    @Test
    @DisplayName("a bucket without crawler counts reads as empty crawler maps")
    void olderBucketsHaveNoCrawlerMaps() {
        Document raw = new Document("_id", "2026-10-11").append(SiteMetric.FIELD_START, new Date());

        SiteMetricDay day = converter.read(SiteMetricDay.class, raw);

        assertThat(day.getCrawlerHitsByBot()).isEmpty();
        assertThat(day.getCrawlerErrorsByBot()).isEmpty();
        assertThat(day.getCrawlerVerifiedByBot()).isEmpty();
        assertThat(day.getCrawlerUnverifiedByBot()).isEmpty();
        assertThat(day.crawlerHitTotal()).isZero();
    }

    @Test
    @DisplayName("the reader splits read back keyed by code and enum name, Int32 included")
    void readerSplitsReadBack() {
        // The shape dotted $incs on viewsByLanguage.bg, viewsByBrowser.CHROME and friends leave behind.
        Document raw = new Document("_id", "2026-11-03T14")
                .append(SiteMetric.FIELD_START, new Date())
                .append(SiteMetric.FIELD_VIEWS, 9L)
                .append(SiteMetric.FIELD_VIEWS_BY_LANGUAGE, new Document("bg", 6L).append("und", 3))
                .append(SiteMetric.FIELD_VIEWS_BY_BROWSER_LANGUAGE, new Document("de", 5L).append("en", 4))
                .append(SiteMetric.FIELD_VIEWS_BY_BROWSER, new Document("CHROME", 7L).append("SAFARI", 2))
                .append(SiteMetric.FIELD_VIEWS_BY_OS, new Document("ANDROID", 9))
                .append(SiteMetric.FIELD_VIEWS_BY_DEVICE, new Document("MOBILE", 8L).append("TABLET", 1));

        SiteMetricHour hour = converter.read(SiteMetricHour.class, raw);

        assertThat(hour.getViewsByLanguage()).containsEntry("bg", 6L);
        assertThat(hour.getViewsByLanguage().get("und")).isEqualTo(3L);
        assertThat(hour.getViewsByBrowserLanguage()).containsEntry("de", 5L);
        assertThat(hour.getViewsByBrowserLanguage().get("en")).isEqualTo(4L);
        assertThat(hour.getViewsByBrowser()).containsEntry("CHROME", 7L);
        assertThat(hour.getViewsByBrowser().get("SAFARI")).isEqualTo(2L);
        assertThat(hour.getViewsByOs().get("ANDROID")).isEqualTo(9L);
        assertThat(hour.getViewsByDevice()).containsEntry("MOBILE", 8L);
        assertThat(hour.getViewsByDevice().get("TABLET")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a bucket written before the reader splits reads as empty maps, never null")
    void olderBucketsHaveNoReaderSplits() {
        Document raw = new Document("_id", "2026-10-11").append(SiteMetric.FIELD_START, new Date())
                .append(SiteMetric.FIELD_VIEWS, 12L);

        SiteMetricDay day = converter.read(SiteMetricDay.class, raw);

        assertThat(day.getViews()).isEqualTo(12L);
        assertThat(day.getViewsByLanguage()).isEmpty();
        assertThat(day.getViewsByBrowserLanguage()).isEmpty();
        assertThat(day.getViewsByBrowser()).isEmpty();
        assertThat(day.getViewsByOs()).isEmpty();
        assertThat(day.getViewsByDevice()).isEmpty();
    }

    /** Wired the way Spring Boot wires it: without the simple-type holder, {@code java.time} is an entity. */
    private static MappingMongoConverter converter() {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }
}
