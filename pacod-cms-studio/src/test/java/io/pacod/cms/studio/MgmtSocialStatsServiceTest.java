package io.pacod.cms.studio;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.SiteMetricDay;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.SiteMetricDayRepository;
import io.pacod.cms.core.repository.SiteMetricHourRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MgmtSocialStatsServiceTest {

    private SiteMetricHourRepository hours;
    private SiteMetricDayRepository days;
    private ArticleRepository articles;
    private MongoTemplate mongoTemplate;
    private MgmtSocialStatsService service;
    private SocialPage page;

    @BeforeEach
    void setUp() {
        hours = mock(SiteMetricHourRepository.class);
        days = mock(SiteMetricDayRepository.class);
        articles = mock(ArticleRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        io.pacod.cms.core.repository.SiteSettingsRepository siteSettings =
                mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        io.pacod.cms.core.data.SiteSettings site = new io.pacod.cms.core.data.SiteSettings();
        site.setBaseUrl("https://www.example.org");
        org.mockito.Mockito.when(siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID))
                .thenReturn(java.util.Optional.of(site));
        service = new MgmtSocialStatsService(hours, days, articles, mongoTemplate, siteSettings);

        page = new SocialPage();
        page.getLinks().add(link("instagra", SocialLinkPlacement.ICON, "Instagram"));
        page.getLinks().add(link("subscrib", SocialLinkPlacement.BUTTON, "Абонамент"));
    }

    private static SocialLink link(String id, SocialLinkPlacement placement, String title) {
        SocialLink link = new SocialLink(id);
        link.setPlacement(placement);
        link.putTitle("bg", title);
        link.setUrl("https://example.com/" + id);
        return link;
    }

    private static SiteMetricHour hour(LocalDateTime start, Map<String, Long> views,
                                       Map<String, Map<String, Long>> clicks, long visitors) {
        SiteMetricHour hour = new SiteMetricHour();
        hour.setId(SiteMetricHour.idFor(start));
        hour.setStart(start);
        hour.setSocialViewsBySource(new java.util.LinkedHashMap<>(views));
        hour.setSocialClicks(new java.util.LinkedHashMap<>(clicks));
        hour.setSocialVisitors(visitors);
        return hour;
    }

    private List<MgmtSocialStatsService.Bucket> twoHours() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        when(hours.findInWindow(any(), any())).thenReturn(List.of(
                hour(now.minusHours(1), Map.of("INSTAGRAM", 60L, "FACEBOOK", 20L),
                        Map.of("subscrib", Map.of("INSTAGRAM", 6L, "FACEBOOK", 1L)), 50),
                hour(now, Map.of("INSTAGRAM", 15L, "DIRECT", 5L),
                        Map.of("instagra", Map.of("INSTAGRAM", 2L), "deadlink", Map.of("DIRECT", 1L)), 12)));
        return service.series(MonitoringRange.H24);
    }

    @Test
    @DisplayName("the window is gap-filled: every hour is a bucket, recorded or not")
    void seriesIsGapFilled() {
        List<MgmtSocialStatsService.Bucket> buckets = twoHours();

        assertThat(buckets).hasSize(24);
        assertThat(buckets.get(22).views()).isEqualTo(80L);
        assertThat(buckets.get(23).views()).isEqualTo(20L);
        assertThat(buckets.get(0).views()).isZero();
        assertThat(buckets.get(0).clicks()).isEmpty();
    }

    @Test
    @DisplayName("totals, CTR and the leading source are computed over the window")
    void totals() {
        MgmtSocialStatsService.Report report = service.report(twoHours(), page, null, null, Map.of());

        assertThat(report.totals().views()).isEqualTo(100L);
        assertThat(report.totals().clicks()).isEqualTo(10L);
        assertThat(report.totals().clickThroughRate()).isEqualTo(10.0);
        // Readers do not sum: the busiest bucket.
        assertThat(report.totals().peakVisitors()).isEqualTo(50L);
        assertThat(report.totals().topSource()).isEqualTo(SocialSource.INSTAGRAM);
    }

    @Test
    @DisplayName("the source table carries views, share, clicks and CTR per network, busiest first")
    void sourceRows() {
        MgmtSocialStatsService.Report report = service.report(twoHours(), page, null, null, Map.of());

        assertThat(report.sources()).extracting(MgmtSocialStatsService.SourceRow::source)
                .containsExactly(SocialSource.INSTAGRAM, SocialSource.FACEBOOK, SocialSource.DIRECT);
        MgmtSocialStatsService.SourceRow instagram = report.sources().getFirst();
        assertThat(instagram.views()).isEqualTo(75L);
        assertThat(instagram.viewShare()).isEqualTo(75.0);
        assertThat(instagram.clicks()).isEqualTo(8L);
    }

    @Test
    @DisplayName("every link on the page has a row, and a deleted link keeps its clicks")
    void linkRowsIncludeDeletedLinks() {
        MgmtSocialStatsService.Report report = service.report(twoHours(), page, null, null,
                Map.of("subscrib", 140L));

        assertThat(report.links()).extracting(MgmtSocialStatsService.LinkRow::id)
                .containsExactly("instagra", "subscrib", "deadlink");
        MgmtSocialStatsService.LinkRow subscribe = report.links().get(1);
        assertThat(subscribe.clicks()).isEqualTo(7L);
        assertThat(subscribe.clickThroughRate()).isEqualTo(7.0);
        assertThat(subscribe.allTime()).isEqualTo(140L);
        assertThat(report.links().get(2).deleted()).isTrue();
        // The window's total does not shrink because the page was tidied.
        assertThat(report.links().stream().mapToLong(MgmtSocialStatsService.LinkRow::clicks).sum())
                .isEqualTo(report.totals().clicks());
    }

    @Test
    @DisplayName("a source filter narrows the totals and the link clicks to that network")
    void sourceFilterNarrows() {
        MgmtSocialStatsService.Report report = service.report(twoHours(), page, SocialSource.FACEBOOK, null, Map.of());

        assertThat(report.totals().views()).isEqualTo(20L);
        assertThat(report.totals().clicks()).isEqualTo(1L);
        assertThat(report.links().get(1).clicks()).isEqualTo(1L);
        // Readers are not counted per source, so the series is left out rather than mismatched.
        assertThat(report.chart().series()).extracting(MgmtChart.Series::key).containsExactly("views", "clicks");
    }

    @Test
    @DisplayName("the matrix crosses links with the sources that clicked, busiest column first")
    void matrix() {
        MgmtSocialStatsService.Report report = service.report(twoHours(), page, null, null, Map.of());

        // Facebook and Direct tie on one click each; the tie goes to declaration order, so the
        // columns are the same on every render.
        assertThat(report.matrix().sources())
                .containsExactly(SocialSource.INSTAGRAM, SocialSource.FACEBOOK, SocialSource.DIRECT);
        assertThat(report.matrix().rows()).extracting(MgmtSocialStatsService.MatrixRow::linkId)
                .containsExactly("instagra", "subscrib", "deadlink");
        assertThat(report.matrix().rows().get(1).cells()).containsExactly(6L, 1L, 0L);
    }

    @Test
    @DisplayName("article clicks are named through the projected finder")
    void articleRows() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        SiteMetricHour bucket = hour(now, Map.of("INSTAGRAM", 10L), Map.of(), 1);
        bucket.setSocialArticleClicks(Map.of("3150", Map.of("INSTAGRAM", 3L)));
        when(hours.findInWindow(any(), any())).thenReturn(List.of(bucket));
        Article article = new Article(3150);
        article.setTitle("Есенният фестивал");
        when(articles.findTitlesByIdIn(any())).thenReturn(List.of(article));

        MgmtSocialStatsService.Report report = service.report(service.series(MonitoringRange.H24), page, null, null, Map.of());

        assertThat(report.articles()).singleElement().satisfies(row -> {
            assertThat(row.title()).isEqualTo("Есенният фестивал");
            assertThat(row.fromBlock()).isEqualTo(3L);
            assertThat(row.fromLinks()).isZero();
            assertThat(row.clicks()).isEqualTo(3L);
        });
        assertThat(report.totals().clicks()).isEqualTo(3L);
        // The article itself, not the block as one lump.
        assertThat(report.matrix().rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("Есенният фестивал");
            assertThat(row.articleId()).isEqualTo(3150);
            assertThat(row.linkId()).isNull();
        });
    }

    @Test
    @DisplayName("an article reached by a link and from the block is one row, with both counts")
    void articleClicksAddUpAcrossRoutes() {
        SocialLink toArticle = new SocialLink("artlink1");
        toArticle.setUrl("https://www.example.org/article/3150");
        page.getLinks().add(toArticle);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        SiteMetricHour bucket = hour(now, Map.of("INSTAGRAM", 20L),
                Map.of("artlink1", Map.of("INSTAGRAM", 2L)), 5);
        bucket.setSocialArticleClicks(Map.of("3150", Map.of("FACEBOOK", 1L), "17", Map.of("INSTAGRAM", 4L)));
        when(hours.findInWindow(any(), any())).thenReturn(List.of(bucket));
        Article festival = new Article(3150);
        festival.setTitle("<b>Есенният</b> фестивал");
        Article other = new Article(17);
        other.setTitle("Друга статия");
        when(articles.findTitlesByIdIn(any())).thenReturn(List.of(festival, other));

        MgmtSocialStatsService.Report report = service.report(service.series(MonitoringRange.H24), page, null, null, Map.of());

        assertThat(report.articles()).extracting(MgmtSocialStatsService.ArticleRow::id).containsExactly(17, 3150);
        MgmtSocialStatsService.ArticleRow festivalRow = report.articles().get(1);
        assertThat(festivalRow.title()).isEqualTo("Есенният фестивал");
        assertThat(festivalRow.fromBlock()).isEqualTo(1L);
        assertThat(festivalRow.fromLinks()).isEqualTo(2L);
        assertThat(festivalRow.clicks()).isEqualTo(3L);
        // The link with no text of its own is called by the article's title, and names the article.
        MgmtSocialStatsService.LinkRow linkRow = report.links().stream()
                .filter(r -> r.id().equals("artlink1")).findFirst().orElseThrow();
        assertThat(linkRow.label()).isEqualTo("Есенният фестивал");
        assertThat(linkRow.articleId()).isEqualTo(3150);
        // Narrowed to one network, both routes narrow with it.
        MgmtSocialStatsService.Report instagram = service.report(service.series(MonitoringRange.H24), page,
                SocialSource.INSTAGRAM, null, Map.of());
        assertThat(instagram.articles()).filteredOn(r -> r.id() == 3150).singleElement()
                .satisfies(r -> assertThat(r.fromBlock()).isZero())
                .satisfies(r -> assertThat(r.fromLinks()).isEqualTo(2L));
    }

    @Test
    @DisplayName("filters are lenient: anything unknown is no filter")
    void filtersAreLenient() {
        List<MgmtSocialStatsService.Bucket> buckets = twoHours();

        assertThat(MgmtSocialStatsService.sourceFilter("instagram")).isEqualTo(SocialSource.INSTAGRAM);
        assertThat(MgmtSocialStatsService.sourceFilter("myspace")).isNull();
        assertThat(MgmtSocialStatsService.sourceFilter("")).isNull();
        assertThat(MgmtSocialStatsService.linkFilter("subscrib", page, buckets)).isEqualTo("subscrib");
        // Deleted, but the window has clicks for it.
        assertThat(MgmtSocialStatsService.linkFilter("deadlink", page, buckets)).isEqualTo("deadlink");
        assertThat(MgmtSocialStatsService.linkFilter("nosuchid", page, buckets)).isNull();
        assertThat(MgmtSocialStatsService.linkFilter("../etc", page, buckets)).isNull();
    }

    @Test
    @DisplayName("the link filter narrows the clicks line to that link")
    void linkFilterNarrowsTheChart() {
        MgmtSocialStatsService.Report report = service.report(twoHours(), page, null, "instagra", Map.of());

        MgmtChart.Series clicks = report.chart().series().stream()
                .filter(s -> s.key().equals("clicks")).findFirst().orElseThrow();
        assertThat(clicks.total()).isEqualTo(2L);
        assertThat(report.linkFilterLabel()).isEqualTo("Instagram");
    }

    @Test
    @DisplayName("a failed read degrades to an empty window and an empty all-time column")
    void failuresDegrade() {
        when(hours.findInWindow(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        when(mongoTemplate.find(any(Query.class), eq(SiteMetricDay.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(service.series(MonitoringRange.H24)).hasSize(24)
                .allSatisfy(b -> assertThat(b.views()).isZero());
        assertThat(service.allTimeClicks()).isEmpty();
        // Empty all-time is a dash on screen, not zero.
        assertThat(service.report(service.series(MonitoringRange.H24), page, null, null, Map.of()).links())
                .allSatisfy(row -> assertThat(row.allTime()).isNull());
    }

    @Test
    @DisplayName("all-time clicks sum every day bucket")
    void allTimeSumsTheDays() {
        SiteMetricDay one = new SiteMetricDay();
        one.setSocialClicks(Map.of("subscrib", Map.of("INSTAGRAM", 4L, "DIRECT", 1L)));
        SiteMetricDay two = new SiteMetricDay();
        two.setSocialClicks(Map.of("subscrib", Map.of("INSTAGRAM", 2L), "instagra", Map.of("FACEBOOK", 3L)));
        when(mongoTemplate.find(any(Query.class), eq(SiteMetricDay.class))).thenReturn(List.of(one, two));

        assertThat(service.allTimeClicks()).containsEntry("subscrib", 7L).containsEntry("instagra", 3L);
    }

    @Test
    @DisplayName("a rate over nothing is no rate, not zero")
    void rateOverNothing() {
        assertThat(MgmtSocialStatsService.rate(3, 0)).isNull();
        assertThat(MgmtSocialStatsService.rate(1, 4)).isEqualTo(25.0);
    }
}
