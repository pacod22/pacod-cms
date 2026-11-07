package io.pacod.cms.studio.broker;

import io.pacod.cms.core.control.data.BrokerSettings;
import io.pacod.cms.core.control.repository.BrokerSettingsRepository;
import io.pacod.cms.studio.MgmtOperationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * How often {@link BrokerAnnouncer} rebuilds the announcements: the stored {@link BrokerSettings}
 * while it names an interval, else {@code pacod.broker.refresh} ({@code PACOD_BROKER_REFRESH},
 * 60 s) -- set on the platform screen, no restart.
 *
 * <ul>
 *   <li><b>A bare number is seconds</b> ({@code 60}, as the screen writes it); {@code 2m} and
 *       {@code PT2M} work too. Boot's own conversion would read a bare {@code 60} as 60 ms.
 *       Anything else, or a value outside {@link #MIN}-{@link #MAX}, fails the start.</li>
 *   <li><b>Read fresh</b> behind a {@link #MEMO}, since the announcer asks on every tick;
 *       {@link #set} and {@link #clear} forget it (one studio JVM), so a change applies on the
 *       announcer's next tick.</li>
 *   <li><b>An unreadable control plane falls back to the environment's</b>, logged at ERROR.</li>
 * </ul>
 */
@Slf4j
public class BrokerRefresh {

    static final Duration MEMO = Duration.ofSeconds(5);
    /** Twice the announcer's tick: a round reads every offered website's settings. */
    public static final int MIN = 10;
    /** A day: the leases live on heartbeats meanwhile, only a change waits this long. */
    public static final int MAX = 86_400;

    /** Where the interval in effect comes from. */
    public enum Source { STORED, ENVIRONMENT }

    /** The interval in effect. */
    public record Effective(Duration every, Source source, Instant updatedOn, String updatedBy) {

        public long seconds() {
            return every.toSeconds();
        }
    }

    private final Duration environment;
    private final BrokerSettingsRepository repository;
    private final Clock clock;
    private volatile Effective memo;
    private volatile Instant memoUntil = Instant.MIN;

    public BrokerRefresh(String environment, BrokerSettingsRepository repository) {
        this(environment, repository, Clock.systemUTC());
    }

    BrokerRefresh(String environment, BrokerSettingsRepository repository, Clock clock) {
        this.environment = parse(environment);
        this.repository = repository;
        this.clock = clock;
    }

    /** The environment's interval alone, nothing stored: for tests and hand-built announcers. */
    public static BrokerRefresh fixed(Duration every) {
        return new BrokerRefresh(every.toSeconds() + "s", null, Clock.systemUTC());
    }

    /** The interval in effect. */
    public Duration interval() {
        return effective().every();
    }

    public Effective effective() {
        Instant now = clock.instant();
        Effective cached = memo;
        if (cached != null && now.isBefore(memoUntil)) {
            return cached;
        }
        Effective fresh = read();
        memo = fresh;
        memoUntil = now.plus(MEMO);
        return fresh;
    }

    /** The environment's interval, which {@link #clear} goes back to. */
    public Duration environment() {
        return environment;
    }

    private Effective read() {
        if (repository != null) {
            try {
                Optional<BrokerSettings> stored = repository.findById(BrokerSettings.ID);
                if (stored.isPresent() && stored.get().getRefreshSeconds() != null
                        && inRange(stored.get().getRefreshSeconds())) {
                    BrokerSettings s = stored.get();
                    return new Effective(Duration.ofSeconds(s.getRefreshSeconds()), Source.STORED,
                            s.getUpdatedOn(), s.getUpdatedBy());
                }
            } catch (DataAccessException e) {
                log.error("Could not read the pacod-broker settings, using the environment's interval: {}",
                        e.getMessage());
            }
        }
        return new Effective(environment, Source.ENVIRONMENT, null, null);
    }

    /**
     * Stores {@code seconds} as the interval, in force from the announcer's next tick.
     *
     * @throws MgmtOperationException {@code portal.platform.broker.interval.error} outside
     *                                {@link #MIN}-{@link #MAX}
     */
    public Effective set(Integer seconds, String by) {
        if (repository == null) {
            throw new IllegalStateException("No settings store");
        }
        if (seconds == null || !inRange(seconds)) {
            throw new MgmtOperationException("portal.platform.broker.interval.error", MIN, MAX);
        }
        BrokerSettings settings = new BrokerSettings();
        settings.setRefreshSeconds(seconds);
        settings.setUpdatedOn(clock.instant());
        settings.setUpdatedBy(by);
        repository.save(settings);
        forget();
        return effective();
    }

    /** Removes the stored interval: the environment's applies again. */
    public Effective clear() {
        if (repository == null) {
            throw new IllegalStateException("No settings store");
        }
        repository.deleteById(BrokerSettings.ID);
        forget();
        return effective();
    }

    private void forget() {
        memo = null;
        memoUntil = Instant.MIN;
    }

    /** {@code PACOD_BROKER_REFRESH}: blank is 60 s, a bare number seconds. */
    static Duration parse(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(60);
        }
        Duration d;
        try {
            d = DurationStyle.detectAndParse(value.strip(), ChronoUnit.SECONDS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("pacod.broker.refresh (PACOD_BROKER_REFRESH) is not a duration: " + value);
        }
        if (d.getNano() != 0 || d.toSeconds() < MIN || d.toSeconds() > MAX) {
            throw new IllegalStateException("pacod.broker.refresh (PACOD_BROKER_REFRESH) must be whole seconds from "
                    + MIN + " to " + MAX + ", not " + value);
        }
        return d;
    }

    private static boolean inRange(int seconds) {
        return seconds >= MIN && seconds <= MAX;
    }
}
