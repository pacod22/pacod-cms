package io.pacod.cms.studio.identity;

import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The studio's copy of pacod-id's authorization state for product {@code cms}: who holds which
 * role where, which accounts are disabled or deleted, which sessions were ended, which scopes
 * exist. Tokens carry no roles, so every permission the studio checks is read from here.
 *
 * <p>The protocol is pacod-id's own (its reference client, {@code pacod-id-client/src/cache.rs}):
 * <ol>
 *   <li>{@code GET /internal/v1/authz/snapshot?product=cms} -- the whole state and its {@code seq};</li>
 *   <li>then {@code GET /internal/v1/authz/changes?after=<seq>&wait=25} in a loop, applying each
 *       event and sending the answer's <b>top-level</b> {@code seq} as the next {@code after} (it
 *       can be ahead of the last event's: other products' events are read and skipped);</li>
 *   <li>a 410 means the cursor is older than pacod-id keeps (or newer than its head): reload the
 *       snapshot and carry on from its {@code seq}.</li>
 * </ol>
 * Errors back off from one second to thirty. {@link #staleness()} is the time since pacod-id last
 * answered at all; the studio's screens refuse to decide with a copy older than
 * {@link IdProperties#maxStaleness()}.
 *
 * <p><b>Deletions are confirmed.</b> Pushing the catalogue enrols {@code cms} for deletion
 * confirmations, and pacod-id keeps a deleted account pending until every enrolled product says it
 * has erased its records: {@code POST /internal/v1/deletions/<sub>/done?product=cms}. The studio
 * keeps no record of people beyond this copy and an in-heap presence map, so it forgets and
 * confirms -- for the snapshot's pending list as well as for each event, as a confirmation lost to
 * a failure must be retried. Service principals need none.
 *
 * <p>One follower, on a virtual thread, started and stopped by {@link IdentityLifecycle}: the studio
 * has no {@code @EnableScheduling}, on purpose.
 */
@Slf4j
public class AuthzFeed {

    static final String PRODUCT = "cms";
    static final int WAIT_SECONDS = 25;
    /** pacod-id's own window for revoked sessions; the least this copy keeps them. */
    static final Duration MIN_REVOKED_SID_RETENTION = Duration.ofMinutes(15);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /** A scope pacod-id knows: an organisation, or one of this product's types. */
    public record Scope(String type, String id, String parent, String displayName, List<String> products,
                        List<String> path) {

        public String key() {
            return type + ":" + id;
        }
    }

    private final IdClient client;
    private final Clock clock;
    /**
     * How long an ended session is remembered: as long as a studio session can sit idle and still
     * be used. pacod-id says a session ended once; a studio session idle for longer than this copy
     * remembered would carry on after a sign-out done elsewhere.
     */
    private final Duration revokedRetention;

    private final Map<String, Map<String, Binding>> bindingsByPrincipal = new ConcurrentHashMap<>();
    private final Map<String, String> principalOfBinding = new ConcurrentHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> revokedSids = new ConcurrentHashMap<>();
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private final Set<String> deletionsToConfirm = ConcurrentHashMap.newKeySet();

    private final Object seqLock = new Object();
    private volatile long seq = -1;
    private volatile boolean ready;
    private volatile Instant lastContact;
    private volatile boolean running;
    private volatile Thread follower;

    public AuthzFeed(IdClient client) {
        this(client, Clock.systemUTC(), MIN_REVOKED_SID_RETENTION);
    }

    public AuthzFeed(IdClient client, Duration sessionTimeout) {
        this(client, Clock.systemUTC(), sessionTimeout);
    }

    AuthzFeed(IdClient client, Clock clock) {
        this(client, clock, MIN_REVOKED_SID_RETENTION);
    }

    AuthzFeed(IdClient client, Clock clock, Duration sessionTimeout) {
        this.client = client;
        this.clock = clock;
        Duration longest = sessionTimeout == null ? MIN_REVOKED_SID_RETENTION : sessionTimeout.plusMinutes(5);
        this.revokedRetention = longest.compareTo(MIN_REVOKED_SID_RETENTION) > 0 ? longest : MIN_REVOKED_SID_RETENTION;
    }

    // ---------------------------------------------------------------------------------- reading

    /** The person's bindings; none at all while their account is disabled or deleted. */
    public Subject subjectOf(String sub) {
        if (sub == null || !isActive(sub)) {
            return Subject.user(List.of());
        }
        Map<String, Binding> held = bindingsByPrincipal.get(sub);
        return Subject.user(held == null ? List.of() : List.copyOf(held.values()));
    }

    public boolean isActive(String sub) {
        return !disabled.contains(sub) && !deleted.contains(sub);
    }

    /** Whether pacod-id ended this session (logout elsewhere, password change, disabling ...). */
    public boolean isRevoked(String sid) {
        return sid != null && revokedSids.containsKey(sid);
    }

    /** Whether a snapshot has been loaded. */
    public boolean isReady() {
        return ready;
    }

    /** Time since pacod-id last answered; infinite before the first answer. */
    public Duration staleness() {
        Instant contact = lastContact;
        return contact == null ? Duration.ofDays(36500) : Duration.between(contact, clock.instant());
    }

    public long seq() {
        return seq;
    }

    public Collection<Scope> scopes() {
        return List.copyOf(scopes.values());
    }

    /**
     * Waits until the copy has applied {@code target} -- read-your-writes after a write that
     * answered with a {@code seq}. False when it did not get there in time.
     */
    public boolean awaitSeq(long target, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (seqLock) {
            while (seq < target) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                seqLock.wait(Math.max(1, left / 1_000_000));
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- running

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        follower = Thread.ofVirtual().name("pacod-id-feed").start(this::follow);
    }

    public synchronized void stop() {
        running = false;
        Thread t = follower;
        if (t != null) {
            t.interrupt();
        }
    }

    private void follow() {
        Duration backoff = Duration.ofSeconds(1);
        boolean reload = true;
        while (running) {
            try {
                if (reload) {
                    loadSnapshot();
                    reload = false;
                }
                poll();
                confirmDeletions();
                backoff = Duration.ofSeconds(1);
            } catch (IdClient.IdException e) {
                if (e.status() == 410) {
                    log.info("pacod-id no longer keeps changes after seq {}; reloading the snapshot", seq);
                    reload = true;
                    continue;
                }
                backoff = sleep(backoff, e);
            } catch (RuntimeException e) {
                backoff = sleep(backoff, e);
            }
        }
    }

    private Duration sleep(Duration backoff, Exception e) {
        log.warn("pacod-id feed: {} -- retrying in {}s", e.getMessage(), backoff.toSeconds());
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
        Duration next = backoff.multipliedBy(2);
        return next.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : next;
    }

    /** One snapshot, replacing everything held. */
    void loadSnapshot() {
        JsonNode snapshot = client.get("/internal/v1/authz/snapshot", Map.of("product", PRODUCT));
        applySnapshot(snapshot);
    }

    /** One long poll. */
    void poll() {
        JsonNode changes = client.get("/internal/v1/authz/changes",
                Map.of("product", PRODUCT, "after", seq, "wait", WAIT_SECONDS));
        applyChanges(changes);
    }

    synchronized void applySnapshot(JsonNode snapshot) {
        bindingsByPrincipal.clear();
        principalOfBinding.clear();
        disabled.clear();
        deleted.clear();
        // Not cleared: a snapshot lists only pacod-id's recent revocations, and one older than that
        // must still end the idle studio session it belongs to. Expiry prunes them.
        scopes.clear();
        for (JsonNode b : snapshot.path("bindings")) {
            addBinding(b);
        }
        for (JsonNode s : snapshot.path("disabled")) {
            disabled.add(s.asString());
        }
        for (JsonNode s : snapshot.path("deleted")) {
            deleted.add(s.asString());
            deletionsToConfirm.add(s.asString());
        }
        Instant now = clock.instant();
        for (JsonNode r : snapshot.path("revoked_sids")) {
            revokedSids.put(r.path("sid").asString(), now);
        }
        for (JsonNode s : snapshot.path("scopes")) {
            putScope(s);
        }
        ready = true;
        contact();
        advance(snapshot.path("seq").asLong());
        log.info("pacod-id feed: snapshot at seq {} ({} principals with roles, {} scopes)", seq,
                bindingsByPrincipal.size(), scopes.size());
    }

    synchronized void applyChanges(JsonNode changes) {
        for (JsonNode event : changes.path("events")) {
            apply(event);
        }
        pruneRevokedSids();
        contact();
        // The top-level seq, never the last event's: events of other products are skipped.
        advance(changes.path("seq").asLong(seq));
    }

    private void apply(JsonNode e) {
        String kind = e.path("kind").asString("");
        switch (kind) {
            case "binding.added" -> addBinding(e);
            case "binding.removed" -> removeBinding(e.path("id").asString());
            case "principal.disabled" -> disabled.add(e.path("sub").asString());
            case "principal.enabled" -> disabled.remove(e.path("sub").asString());
            case "principal.deleted" -> {
                String sub = e.path("sub").asString();
                deleted.add(sub);
                forget(sub);
                if ("user".equals(e.path("principal_kind").asString("user"))) {
                    deletionsToConfirm.add(sub);
                }
            }
            case "session.revoked" -> revokedSids.put(e.path("sid").asString(), clock.instant());
            case "scope.upserted" -> putScope(e);
            // pacod-id removes a scope's bindings with binding.removed first; only the scope goes.
            case "scope.deleted" -> scopes.remove(e.path("type").asString() + ":" + e.path("id").asString());
            // A new catalogue version, a revoked service token, a renamed account: nothing held
            // here depends on them.
            default -> log.debug("pacod-id feed: {} at seq {}", kind, e.path("seq").asLong());
        }
    }

    private void addBinding(JsonNode b) {
        String id = b.path("id").asString();
        String principal = b.path("principal").asString();
        List<String> path = new ArrayList<>();
        for (JsonNode p : b.path("path")) {
            path.add(p.asString());
        }
        bindingsByPrincipal.computeIfAbsent(principal, k -> new ConcurrentHashMap<>())
                .put(id, new Binding(b.path("role").asString(), path));
        principalOfBinding.put(id, principal);
    }

    private void removeBinding(String id) {
        String principal = principalOfBinding.remove(id);
        if (principal == null) {
            return;
        }
        Map<String, Binding> held = bindingsByPrincipal.get(principal);
        if (held != null) {
            held.remove(id);
            if (held.isEmpty()) {
                bindingsByPrincipal.remove(principal);
            }
        }
    }

    private void forget(String sub) {
        Map<String, Binding> held = bindingsByPrincipal.remove(sub);
        if (held != null) {
            held.keySet().forEach(principalOfBinding::remove);
        }
    }

    private void putScope(JsonNode s) {
        List<String> products = new ArrayList<>();
        for (JsonNode p : s.path("products")) {
            products.add(p.asString());
        }
        List<String> path = new ArrayList<>();
        for (JsonNode p : s.path("path")) {
            path.add(p.asString());
        }
        Scope scope = new Scope(s.path("type").asString(), s.path("id").asString(),
                s.path("parent").asString(null), s.path("display_name").asString(""), products, path);
        scopes.put(scope.key(), scope);
    }

    void pruneRevokedSids() {
        Instant cutoff = clock.instant().minus(revokedRetention);
        revokedSids.values().removeIf(at -> at.isBefore(cutoff));
    }

    private void contact() {
        lastContact = clock.instant();
    }

    private void advance(long next) {
        synchronized (seqLock) {
            seq = next;
            seqLock.notifyAll();
        }
    }

    void confirmDeletions() {
        for (String sub : List.copyOf(deletionsToConfirm)) {
            try {
                client.post("/internal/v1/deletions/" + sub + "/done", Map.of("product", PRODUCT));
                deletionsToConfirm.remove(sub);
                log.info("pacod-id feed: confirmed the deletion of {}", sub);
            } catch (RuntimeException e) {
                log.warn("pacod-id feed: could not confirm the deletion of {} yet: {}", sub, e.getMessage());
            }
        }
    }
}
