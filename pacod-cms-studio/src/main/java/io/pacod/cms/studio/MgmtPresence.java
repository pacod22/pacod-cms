package io.pacod.cms.studio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which operators are working in the console right now, for the dashboard's "Admins now" tile.
 *
 * <p>An operator is <em>active</em> when they made a console request within the window -- the same
 * {@code app.mgmt.monitoring.active-window-minutes} the live reader count uses, so the two tiles
 * beside each other mean the same thing by "now". {@code MgmtPresenceInterceptor} feeds it, and
 * deliberately skips the background polls: a tab left open over lunch is not someone working.
 *
 * <p>Two obvious sources are not used, each for a reason that fails silently:
 * <ul>
 *   <li><b>A sign-in timestamp</b> says when someone arrived, not whether they are still here, and
 *       pacod-id's single sign-on brings an operator back without a sign-in anyone sees.</li>
 *   <li><b>A {@code SessionRegistry}</b> refreshes {@code lastRequest} only under
 *       {@code ConcurrentSessionFilter}, so without {@code maximumSessions} it counts every idle
 *       session for the whole 8 h timeout.</li>
 * </ul>
 *
 * <p>The map is this JVM's heap. That is right for the one studio container this stack runs, and
 * has two consequences stated rather than engineered away: a restart forgets everyone until their
 * next request, and a second console instance would each see only its own operators.
 */
@Component
public class MgmtPresence {

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Duration window;
    private final Clock clock;

    @Autowired
    public MgmtPresence(@Value("${app.mgmt.monitoring.active-window-minutes:5}") int activeWindowMinutes) {
        this(Duration.ofMinutes(activeWindowMinutes), Clock.systemUTC());
    }

    MgmtPresence(Duration window, Clock clock) {
        this.window = window;
        this.clock = clock;
    }

    /** Records a request by {@code username}. A blank name is ignored. */
    public void touch(String username) {
        if (username != null && !username.isBlank()) {
            lastSeen.put(username, clock.instant());
        }
    }

    /** The operators seen within the window, sorted; anyone older is forgotten on the way. */
    public List<String> active() {
        Instant cutoff = clock.instant().minus(window);
        lastSeen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));

        return lastSeen.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public int windowMinutes() {
        return (int) window.toMinutes();
    }
}
