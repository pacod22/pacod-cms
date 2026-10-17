package io.pacod.cms.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One value per website, for state a singleton bean keeps in memory between requests -- the
 * buffered counters, chiefly.
 *
 * <p>A buffered counter is filled on the request thread, where a website is bound, and drained by a
 * scheduled flush, where none is. Kept as one buffer, a flush would have no way to say whose counts
 * it was writing, and the routing template would refuse the write -- or, worse, a default database
 * would take every site's traffic. So each website gets its own value, filled under that website's
 * binding, and {@link #forEach} drains each one with its own website bound again.
 *
 * <p>The tenant is remembered with the value, so the flush binds exactly what the request bound --
 * the same database, the same timezone -- without a directory lookup off the request thread. A
 * later request refreshes it, which is how a changed default language or timezone reaches the next
 * flush.
 *
 * <p><b>A deleted website's slot is dropped, not flushed</b> ({@link #skipWhen}): MongoDB creates a
 * database on its first write, so flushing the counts a deleted website collected in its last
 * minute would bring back the database the studio just dropped.
 *
 * @param <T> the per-site state; it must be safe for concurrent use, as the flush runs while
 *            requests keep filling it
 */
@Slf4j
public final class PerSite<T> {

    private final ConcurrentMap<String, Slot<T>> bySite = new ConcurrentHashMap<>();
    private final AtomicLong lastUnboundWarning = new AtomicLong();
    private final Supplier<T> factory;
    private volatile Predicate<String> gone = siteId -> false;

    public PerSite(Supplier<T> factory) {
        this.factory = factory;
    }

    /**
     * Which websites no longer exist ({@link SiteLiveness#isGone}): their slots are removed, and
     * what they held discarded, instead of flushed. Unset, every website is taken to exist.
     */
    public void skipWhen(Predicate<String> gone) {
        this.gone = gone;
    }

    /**
     * The bound website's value, created on first use; empty when no website is bound.
     *
     * <p>Empty rather than throwing, because every caller is a counter on a render path that has
     * promised never to fail the render: with no website there is nothing to count against, and
     * nothing is written anywhere. On the public site an unknown host never gets this far, so an
     * empty answer there is a wiring fault -- said at WARN, at most once a minute.
     */
    public Optional<T> current() {
        Optional<Tenant> tenant = TenantContext.find();
        if (tenant.isEmpty()) {
            long now = System.currentTimeMillis();
            long last = lastUnboundWarning.get();
            if (now - last > 60_000 && lastUnboundWarning.compareAndSet(last, now)) {
                log.warn("Per-site state touched with no website bound; nothing recorded", new IllegalStateException());
            }
        }
        return tenant.map(this::of);
    }

    /** The value for {@code tenant}, created on first use. */
    public T of(Tenant tenant) {
        Slot<T> slot = bySite.computeIfAbsent(tenant.siteId(), id -> new Slot<>(tenant, factory.get()));
        if (!slot.tenant.equals(tenant)) {
            slot.tenant = tenant;
        }
        return slot.value;
    }

    /**
     * Runs {@code work} over every website's value, with that website bound.
     *
     * <p>One website's failure is logged and does not stop the others: a database that is down for
     * one tenant is no reason to lose the flush of every other.
     */
    public void forEach(Consumer<T> work) {
        for (var entry : bySite.entrySet()) {
            Slot<T> slot = entry.getValue();
            if (gone.test(entry.getKey())) {
                bySite.remove(entry.getKey(), slot);
                log.info("Website {} no longer exists; discarded what was buffered for it", entry.getKey());
                continue;
            }
            try {
                TenantContext.runAs(slot.tenant, () -> work.accept(slot.value));
            } catch (RuntimeException e) {
                log.warn("Per-site work failed for website {}: {}", slot.tenant.siteId(), e.toString());
            }
        }
    }

    /** How many websites have a value. */
    public int size() {
        return bySite.size();
    }

    private static final class Slot<T> {
        private volatile Tenant tenant;
        private final T value;

        Slot(Tenant tenant, T value) {
            this.tenant = tenant;
            this.value = value;
        }
    }
}
