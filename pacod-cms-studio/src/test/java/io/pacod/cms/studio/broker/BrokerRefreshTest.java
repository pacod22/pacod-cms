package io.pacod.cms.studio.broker;

import io.pacod.cms.core.control.data.BrokerSettings;
import io.pacod.cms.core.control.repository.BrokerSettingsRepository;
import io.pacod.cms.studio.MgmtOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How often the announcer rebuilds: {@code PACOD_BROKER_REFRESH} read as seconds (never Boot's
 * milliseconds), the platform screen's interval over it, the bounds on both, the reset, and an
 * unreadable control plane falling back to the environment's.
 */
class BrokerRefreshTest {

    /** A clock the test moves. */
    static final class Steps extends Clock {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final BrokerSettingsRepository repository = mock(BrokerSettingsRepository.class);
    private final Steps clock = new Steps();

    private static BrokerSettings stored(Integer seconds) {
        BrokerSettings s = new BrokerSettings();
        s.setRefreshSeconds(seconds);
        s.setUpdatedOn(Instant.parse("2026-11-04T09:00:00Z"));
        s.setUpdatedBy("usr_admin");
        return s;
    }

    @Test
    @DisplayName("the environment's value: a bare number is seconds, a unit or ISO-8601 works, blank is 60")
    void parsesTheEnvironment() {
        assertThat(BrokerRefresh.parse(null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(BrokerRefresh.parse("  ")).isEqualTo(Duration.ofSeconds(60));
        assertThat(BrokerRefresh.parse("60")).isEqualTo(Duration.ofSeconds(60));
        assertThat(BrokerRefresh.parse("90s")).isEqualTo(Duration.ofSeconds(90));
        assertThat(BrokerRefresh.parse("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThat(BrokerRefresh.parse("PT5M")).isEqualTo(Duration.ofMinutes(5));
        assertThat(BrokerRefresh.parse("1d")).isEqualTo(Duration.ofDays(1));
    }

    @Test
    @DisplayName("an unusable environment value fails the start, naming the variable")
    void refusesAnUnusableEnvironment() {
        for (String bad : new String[]{"5", "86401", "1500ms", "sixty", "-60", "999999999999d"}) {
            assertThatThrownBy(() -> new BrokerRefresh(bad, repository))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("PACOD_BROKER_REFRESH");
        }
    }

    @Test
    @DisplayName("nothing stored: the environment's; stored: the screen's, with who set it")
    void storedWinsOverTheEnvironment() {
        BrokerRefresh r = new BrokerRefresh("120", repository, clock);
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.empty());
        assertThat(r.effective().every()).isEqualTo(Duration.ofSeconds(120));
        assertThat(r.effective().source()).isEqualTo(BrokerRefresh.Source.ENVIRONMENT);

        clock.now = clock.now.plusSeconds(6);
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.of(stored(300)));
        BrokerRefresh.Effective e = r.effective();
        assertThat(e.seconds()).isEqualTo(300);
        assertThat(e.source()).isEqualTo(BrokerRefresh.Source.STORED);
        assertThat(e.updatedBy()).isEqualTo("usr_admin");
        assertThat(r.environment()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("a stored value outside the bounds (written by hand) reads as the environment's")
    void aStoredValueOutOfBoundsIsIgnored() {
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.of(stored(1)));
        assertThat(new BrokerRefresh("60", repository, clock).effective().source()).isEqualTo(BrokerRefresh.Source.ENVIRONMENT);
    }

    @Test
    @DisplayName("read behind a five-second memo, as the announcer asks on every tick")
    void readsBehindAMemo() {
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.empty());
        BrokerRefresh r = new BrokerRefresh("60", repository, clock);
        r.interval();
        r.interval();
        verify(repository, times(1)).findById(BrokerSettings.ID);
        clock.now = clock.now.plus(BrokerRefresh.MEMO);
        r.interval();
        verify(repository, times(2)).findById(BrokerSettings.ID);
    }

    @Test
    @DisplayName("set stores whole seconds within the bounds and applies at once; anything else is refused unsaved")
    void setChecksAndApplies() {
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.empty());
        BrokerRefresh r = new BrokerRefresh("60", repository, clock);
        assertThat(r.interval()).isEqualTo(Duration.ofSeconds(60));

        for (Integer bad : new Integer[]{null, 0, BrokerRefresh.MIN - 1, BrokerRefresh.MAX + 1}) {
            assertThatThrownBy(() -> r.set(bad, "usr_admin")).as(String.valueOf(bad))
                    .isInstanceOf(MgmtOperationException.class)
                    .satisfies(e -> assertThat(((MgmtOperationException) e).code())
                            .isEqualTo("portal.platform.broker.interval.error"));
        }
        verify(repository, never()).save(any());

        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.of(stored(30)));
        BrokerRefresh.Effective e = r.set(30, "usr_admin");
        ArgumentCaptor<BrokerSettings> saved = ArgumentCaptor.forClass(BrokerSettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRefreshSeconds()).isEqualTo(30);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo("usr_admin");
        assertThat(saved.getValue().getUpdatedOn()).isEqualTo(clock.now);
        // The memo is forgotten: the announcer's next tick sees the new interval, not the old one.
        assertThat(e.seconds()).isEqualTo(30);
        assertThat(r.interval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("clear deletes the stored interval and the environment's applies again")
    void clearGoesBackToTheEnvironment() {
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.of(stored(30)));
        BrokerRefresh r = new BrokerRefresh("75", repository, clock);
        assertThat(r.interval()).isEqualTo(Duration.ofSeconds(30));
        when(repository.findById(BrokerSettings.ID)).thenReturn(Optional.empty());
        BrokerRefresh.Effective e = r.clear();
        verify(repository).deleteById(BrokerSettings.ID);
        assertThat(e.seconds()).isEqualTo(75);
        assertThat(e.source()).isEqualTo(BrokerRefresh.Source.ENVIRONMENT);
    }

    @Test
    @DisplayName("an unreadable control plane falls back to the environment's interval, never fails")
    void unreadableFallsBack() {
        when(repository.findById(BrokerSettings.ID)).thenThrow(new DataAccessResourceFailureException("down"));
        BrokerRefresh r = new BrokerRefresh("45", repository, clock);
        assertThat(r.interval()).isEqualTo(Duration.ofSeconds(45));
        assertThat(BrokerRefresh.fixed(Duration.ofSeconds(90)).interval()).isEqualTo(Duration.ofSeconds(90));
    }
}
