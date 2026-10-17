package io.pacod.cms.studio;

import io.pacod.cms.core.data.SocialPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MgmtDashboardServiceTest {

    private final MgmtMonitoringService monitoring = mock(MgmtMonitoringService.class);
    private final MgmtCrawlerService crawlers = mock(MgmtCrawlerService.class);
    private final MgmtSocialService social = mock(MgmtSocialService.class);
    private final MgmtSocialStatsService socialStats = mock(MgmtSocialStatsService.class);
    private final io.pacod.cms.core.jobs.JobQueue jobs = mock(io.pacod.cms.core.jobs.JobQueue.class);
    private final MgmtImageService images = mock(MgmtImageService.class);

    private MgmtDashboardService service;

    @BeforeEach
    void setUp() {
        MgmtPresence presence = new MgmtPresence(5);
        service = new MgmtDashboardService(monitoring, crawlers, social, socialStats, jobs,
                images, presence);

        when(monitoring.liveVisitors()).thenReturn(new MgmtMonitoringService.LiveCount(3, 5, true));
        doCallRealMethod().when(monitoring).totals(any());
        when(monitoring.series(MonitoringRange.H24)).thenReturn(hours(24, 10, 2));
        when(jobs.counts(any(), any())).thenReturn(java.util.Map.of());
    }

    @Test
    @DisplayName("the change is taken against the 24 hourly buckets before the window")
    void changeAgainstThePrevious24Hours() {
        // A week of hours: 120 old buckets, then 24 of yesterday at 8 views, then today's 24 at 10.
        List<MgmtMonitoringService.Bucket> week = new ArrayList<>(hours(120, 999, 999));
        week.addAll(hours(24, 8, 4));
        week.addAll(hours(24, 10, 2));
        when(monitoring.series(MonitoringRange.D7)).thenReturn(week);

        MgmtDashboardService.Traffic traffic = service.dashboard().traffic();

        assertThat(traffic.views()).isEqualTo(240);
        assertThat(traffic.viewsChange()).isCloseTo(25.0, within(0.001));
        assertThat(traffic.peakChange()).isCloseTo(-50.0, within(0.001));
    }

    @Test
    @DisplayName("nothing before means no percentage, not +∞")
    void noBaselineNoChange() {
        List<MgmtMonitoringService.Bucket> week = new ArrayList<>(hours(144, 0, 0));
        week.addAll(hours(24, 10, 2));
        when(monitoring.series(MonitoringRange.D7)).thenReturn(week);

        MgmtDashboardService.Traffic traffic = service.dashboard().traffic();

        assertThat(traffic.viewsChange()).isNull();
        assertThat(traffic.peakChange()).isNull();
        assertThat(MgmtDashboardService.change(5, 0)).isNull();
    }

    @Test
    @DisplayName("a section that throws is emptied; the rest of the dashboard survives")
    void failingSectionsAreContained() {
        when(monitoring.series(MonitoringRange.D7)).thenThrow(new IllegalStateException("boom"));
        when(crawlers.series(any())).thenThrow(new IllegalStateException("boom"));
        when(jobs.counts(any(), any())).thenThrow(new IllegalStateException("boom"));

        MgmtDashboardService.Dashboard dashboard = service.dashboard();

        assertThat(dashboard.readers().active()).isEqualTo(3);
        assertThat(dashboard.traffic().views()).isZero();
        assertThat(dashboard.traffic().chart().empty()).isTrue();
        assertThat(dashboard.crawlers().totals().hits()).isZero();
        assertThat(dashboard.jobs().queued()).isZero();
    }

    @Test
    @DisplayName("no social card for a page never saved, or saved and unpublished")
    void socialOnlyWhenPublished() {
        when(social.exists()).thenReturn(false);
        assertThat(service.dashboard().social()).isNull();
        verify(socialStats, never()).series(any());

        SocialPage page = new SocialPage();
        page.setPublished(false);
        when(social.exists()).thenReturn(true);
        when(social.load()).thenReturn(page);
        assertThat(service.dashboard().social()).isNull();
        verify(socialStats, never()).series(any());
    }

    private static List<MgmtMonitoringService.Bucket> hours(int count, long views, long visitors) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
        List<MgmtMonitoringService.Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buckets.add(new MgmtMonitoringService.Bucket(start.plus(Duration.ofHours(i)), "", "",
                    views, visitors, 0, 0, Map.of(), Map.of(), Map.of(), Map.of()));
        }
        return buckets;
    }
}
