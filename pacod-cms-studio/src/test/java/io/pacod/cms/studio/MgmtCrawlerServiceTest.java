package io.pacod.cms.studio;

import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.CrawlerVisit;
import io.pacod.cms.core.data.SiteMetricDay;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.repository.CrawlerVisitRepository;
import io.pacod.cms.core.repository.SiteMetricDayRepository;
import io.pacod.cms.core.repository.SiteMetricHourRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The arithmetic and the judgements behind {@code /mgmt/crawlers}: the report over the buckets, the
 * stale warning and when it holds back, the log's paging, and degradation.
 */
class MgmtCrawlerServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private SiteMetricHourRepository hourRepository;
    private SiteMetricDayRepository dayRepository;
    private CrawlerVisitRepository visitRepository;
    private MongoTemplate mongoTemplate;
    private MgmtCrawlerService service;

    @BeforeEach
    void setUp() {
        hourRepository = mock(SiteMetricHourRepository.class);
        dayRepository = mock(SiteMetricDayRepository.class);
        visitRepository = mock(CrawlerVisitRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new MgmtCrawlerService(hourRepository, dayRepository, visitRepository, mongoTemplate);
    }

    /* ------------------------------------------------------------------ series and report */

    @Test
    @DisplayName("the series is gap-filled, so the chart never draws a line across an outage")
    void seriesIsGapFilled() {
        LocalDateTime current = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        SiteMetricHour hour = new SiteMetricHour();
        hour.setStart(current.minusHours(2));
        hour.setCrawlerHitsByBot(Map.of("googlebot", 5L));
        when(hourRepository.findInWindow(any(), any())).thenReturn(List.of(hour));

        List<MgmtCrawlerService.Bucket> buckets = service.series(MonitoringRange.H24);

        assertThat(buckets).hasSize(24);
        assertThat(buckets.get(21).hits()).containsEntry("googlebot", 5L);
        assertThat(buckets.get(20).hits()).isEmpty();
        verify(dayRepository, never()).findInWindow(any(), any());
    }

    @Test
    @DisplayName("the long ranges read the day buckets")
    void longRangesReadDays() {
        SiteMetricDay day = new SiteMetricDay();
        day.setStart(LocalDateTime.now().truncatedTo(ChronoUnit.DAYS));
        day.setCrawlerHitsByBot(Map.of("bingbot", 3L));
        when(dayRepository.findInWindow(any(), any())).thenReturn(List.of(day));

        List<MgmtCrawlerService.Bucket> buckets = service.series(MonitoringRange.M12);

        assertThat(buckets).hasSize(365);
        assertThat(buckets.getLast().hits()).containsEntry("bingbot", 3L);
    }

    @Test
    @DisplayName("a failed read is zeroes and a log line, not an error page")
    void failedSeriesIsZeroes() {
        when(hourRepository.findInWindow(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        List<MgmtCrawlerService.Bucket> buckets = service.series(MonitoringRange.H24);

        assertThat(buckets).hasSize(24).allSatisfy(b -> assertThat(b.hits()).isEmpty());
        assertThat(service.report(buckets, Map.of()).chart().empty()).isTrue();
    }

    @Test
    @DisplayName("the report sums the buckets per bot, ranks them, and derives the categories")
    void reportSumsPerBot() {
        List<MgmtCrawlerService.Bucket> buckets = List.of(
                bucket(Map.of("googlebot", 10L, "gptbot", 4L, "other", 2L),
                        Map.of("googlebot", 1L), Map.of("googlebot", 9L), Map.of("googlebot", 1L)),
                bucket(Map.of("googlebot", 5L, "ahrefsbot", 6L),
                        Map.of("ahrefsbot", 6L), Map.of("googlebot", 5L), Map.of()));
        Instant seen = NOW.minus(Duration.ofHours(3));

        MgmtCrawlerService.Report report = service.report(buckets, Map.of("googlebot", seen));

        assertThat(report.totals()).isEqualTo(new MgmtCrawlerService.Totals(27, 7, 1, 4));
        assertThat(report.bots()).extracting(MgmtCrawlerService.BotRow::bot)
                .containsExactly("googlebot", "ahrefsbot", "gptbot", "other");
        MgmtCrawlerService.BotRow google = report.bots().getFirst();
        assertThat(google).isEqualTo(new MgmtCrawlerService.BotRow("googlebot", CrawlerCategory.SEARCH,
                15, 1, 14, 1, seen));
        assertThat(report.bots().get(1).lastSeen()).isNull();

        assertThat(report.categories()).extracting(MgmtCrawlerService.CategoryRow::category)
                .containsExactly(CrawlerCategory.SEARCH, CrawlerCategory.SEO, CrawlerCategory.AI, CrawlerCategory.OTHER);
        assertThat(report.categories().getFirst().share()).isCloseTo(15 * 100.0 / 27, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("the chart draws one line per category that had any hits, and none for the rest")
    void chartHasALinePerPresentCategory() {
        MgmtCrawlerService.Report report = service.report(List.of(
                bucket(Map.of("googlebot", 3L, "bingbot", 2L), Map.of(), Map.of(), Map.of()),
                bucket(Map.of("gptbot", 1L), Map.of(), Map.of(), Map.of())), Map.of());

        assertThat(report.chart().series()).extracting(MgmtChart.Series::key)
                .containsExactly("crawl-search", "crawl-ai");
        // Googlebot and Bingbot add up into one search line.
        assertThat(report.chart().series().getFirst().total()).isEqualTo(5);
    }

    @Test
    @DisplayName("a bot id the table no longer knows is still counted, under «Други»")
    void removedBotIdsLandInOther() {
        MgmtCrawlerService.Report report = service.report(List.of(
                bucket(Map.of("some-bot-removed-in-2027", 4L), Map.of(), Map.of(), Map.of())), Map.of());

        assertThat(report.bots().getFirst().category()).isEqualTo(CrawlerCategory.OTHER);
        assertThat(report.categories()).singleElement()
                .satisfies(c -> assertThat(c.category()).isEqualTo(CrawlerCategory.OTHER));
    }

    /* ------------------------------------------------------------------ the stale warning */

    @Test
    @DisplayName("an empty log is still being collected: no warning that Googlebot has gone")
    void emptyLogIsCollecting() {
        when(visitRepository.findFirstByOrderByOccurredAtAsc()).thenReturn(Optional.empty());

        MgmtCrawlerService.Watch watch = service.watch(NOW);

        assertThat(watch.collecting()).isTrue();
        assertThat(watch.since()).isNull();
        assertThat(watch.stale()).isEmpty();
        verify(visitRepository, never()).findFirstByBotAndVerificationNotOrderByOccurredAtDesc(any(), any());
    }

    @Test
    @DisplayName("a log younger than 48 hours proves no absence yet")
    void youngLogIsCollecting() {
        when(visitRepository.findFirstByOrderByOccurredAtAsc())
                .thenReturn(Optional.of(visit("bingbot", NOW.minus(Duration.ofHours(20)))));

        MgmtCrawlerService.Watch watch = service.watch(NOW);

        assertThat(watch.collecting()).isTrue();
        assertThat(watch.stale()).isEmpty();
    }

    @Test
    @DisplayName("with history, a watched bot unseen for 48 hours -- or not at all -- is reported")
    void staleBotsAreReported() {
        when(visitRepository.findFirstByOrderByOccurredAtAsc())
                .thenReturn(Optional.of(visit("ahrefsbot", NOW.minus(Duration.ofDays(10)))));
        Instant longAgo = NOW.minus(Duration.ofHours(60));
        when(visitRepository.findFirstByBotAndVerificationNotOrderByOccurredAtDesc("googlebot", CrawlerVerification.UNVERIFIED))
                .thenReturn(Optional.of(visit("googlebot", longAgo)));
        when(visitRepository.findFirstByBotAndVerificationNotOrderByOccurredAtDesc("bingbot", CrawlerVerification.UNVERIFIED))
                .thenReturn(Optional.empty());

        MgmtCrawlerService.Watch watch = service.watch(NOW);

        assertThat(watch.collecting()).isFalse();
        assertThat(watch.stale()).containsExactly(
                new MgmtCrawlerService.Stale("googlebot", longAgo),
                new MgmtCrawlerService.Stale("bingbot", null));
    }

    @Test
    @DisplayName("a recent visit clears the warning, and a forged one does not count as a visit")
    void recentVisitsClearTheWarning() {
        when(visitRepository.findFirstByOrderByOccurredAtAsc())
                .thenReturn(Optional.of(visit("ahrefsbot", NOW.minus(Duration.ofDays(10)))));
        when(visitRepository.findFirstByBotAndVerificationNotOrderByOccurredAtDesc(any(), any()))
                .thenReturn(Optional.of(visit("googlebot", NOW.minus(Duration.ofHours(2)))));

        assertThat(service.watch(NOW).stale()).isEmpty();
        // The query itself excludes forgeries: a spoofed "Googlebot" is not evidence of indexing.
        verify(visitRepository).findFirstByBotAndVerificationNotOrderByOccurredAtDesc("googlebot",
                CrawlerVerification.UNVERIFIED);
    }

    @Test
    @DisplayName("a database that cannot be read raises no warning -- that is not evidence either")
    void unreadableLogWarnsOfNothing() {
        when(visitRepository.findFirstByOrderByOccurredAtAsc()).thenThrow(new DataAccessResourceFailureException("down"));

        MgmtCrawlerService.Watch watch = service.watch(NOW);

        assertThat(watch.collecting()).isFalse();
        assertThat(watch.stale()).isEmpty();
    }

    /* ------------------------------------------------------------------ the log */

    @Test
    @DisplayName("the log fetches one row more than a page to know whether there is a next one")
    void logPagesWithoutACount() {
        List<CrawlerVisit> rows = new ArrayList<>();
        for (int i = 0; i < MgmtCrawlerService.PAGE_SIZE + 1; i++) {
            rows.add(visit("googlebot", NOW.minusSeconds(i)));
        }
        when(mongoTemplate.find(any(Query.class), eq(CrawlerVisit.class))).thenReturn(rows);

        MgmtCrawlerService.LogPage page = service.log(noFilter(), NOW.minus(Duration.ofDays(1)), 2);

        assertThat(page.rows()).hasSize(MgmtCrawlerService.PAGE_SIZE);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.from()).isEqualTo(101);
        assertThat(page.to()).isEqualTo(150);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(CrawlerVisit.class));
        assertThat(query.getValue().getSkip()).isEqualTo(100);
        assertThat(query.getValue().getLimit()).isEqualTo(MgmtCrawlerService.PAGE_SIZE + 1);
    }

    @Test
    @DisplayName("every filter reaches the query, and the path term is quoted, not a pattern")
    void logFiltersReachTheQuery() {
        when(mongoTemplate.find(any(Query.class), eq(CrawlerVisit.class))).thenReturn(List.of());

        service.log(new MgmtCrawlerService.LogFilter("googlebot", CrawlerCategory.SEARCH,
                CrawlerVerification.UNVERIFIED, true, "/article/(1"), NOW, 0);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(CrawlerVisit.class));
        String json = query.getValue().getQueryObject().toJson();
        assertThat(json).contains("\"bot\": \"googlebot\"", "\"category\": \"SEARCH\"",
                "\"verification\": \"UNVERIFIED\"", "\"$gte\": 400", "\\\\Q/article/(1\\\\E");
    }

    @Test
    @DisplayName("a failed log read is an empty page, not an error")
    void failedLogIsEmpty() {
        when(mongoTemplate.find(any(Query.class), eq(CrawlerVisit.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        MgmtCrawlerService.LogPage page = service.log(noFilter(), NOW, 0);

        assertThat(page.rows()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("the log-backed cards stop at the retention boundary, and say so")
    void windowIsClampedToRetention() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 11, 12, 30);

        MgmtCrawlerService.Window day = MgmtCrawlerService.window(MonitoringRange.H24, now);
        MgmtCrawlerService.Window year = MgmtCrawlerService.window(MonitoringRange.M12, now);

        assertThat(day.clamped()).isFalse();
        assertThat(day.from()).isEqualTo(MonitoringRange.H24.from(now).atZone(ZoneId.systemDefault()).toInstant());
        assertThat(year.clamped()).isTrue();
        assertThat(year.from()).isEqualTo(now.atZone(ZoneId.systemDefault()).toInstant().minus(MgmtCrawlerService.RETENTION));
        assertThat(MgmtCrawlerService.window(MonitoringRange.D30, now).clamped()).isFalse();
    }

    @Test
    @DisplayName("relative times: minutes, hours up to 48, then days")
    void relativeTimes() {
        MgmtCrawlerService.Times times = new MgmtCrawlerService.Times(NOW, ZoneId.of("Europe/Sofia"));

        // Built without a request, so in the base bundle's language: English.
        assertThat(times.ago(NOW.minusSeconds(20))).isEqualTo("just now");
        assertThat(times.ago(NOW.minus(Duration.ofMinutes(12)))).isEqualTo("12 min ago");
        assertThat(times.ago(NOW.minus(Duration.ofHours(47)))).isEqualTo("47 h ago");
        assertThat(times.ago(NOW.minus(Duration.ofDays(5)))).isEqualTo("5 days ago");
        assertThat(times.ago(null)).isEqualTo("—");
        assertThat(times.stamp(NOW)).isEqualTo("11.10.2026 15:00:00");
    }

    /* ------------------------------------------------------------------ fixtures */

    private static MgmtCrawlerService.LogFilter noFilter() {
        return new MgmtCrawlerService.LogFilter(null, null, null, false, null);
    }

    private static MgmtCrawlerService.Bucket bucket(Map<String, Long> hits, Map<String, Long> errors,
                                                    Map<String, Long> verified, Map<String, Long> unverified) {
        return new MgmtCrawlerService.Bucket(LocalDateTime.of(2026, 10, 11, 10, 0), "10:00", "11.10 10:00",
                hits, errors, verified, unverified);
    }

    private static CrawlerVisit visit(String bot, Instant at) {
        CrawlerVisit visit = new CrawlerVisit();
        visit.setBot(bot);
        visit.setOccurredAt(at);
        visit.setVerification(CrawlerVerification.UNKNOWN);
        return visit;
    }
}
