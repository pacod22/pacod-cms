package io.pacod.cms.studio.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;

/**
 * Starts the studio's conversation with pacod-id once the application is ready, and ends it on
 * shutdown: the catalogue push, the feed follower, and the scope reconciliation every
 * {@link #SYNC_EVERY}. Each runs on a virtual thread of its own and none blocks startup -- a
 * pacod-id that is down costs the studio's screens (503), never the container's start or its
 * health check.
 */
@Slf4j
public class IdentityLifecycle {

    static final Duration SYNC_EVERY = Duration.ofSeconds(10);

    private final CatalogPusher catalog;
    private final AuthzFeed feed;
    private final ScopeSync scopes;
    private volatile Thread worker;

    public IdentityLifecycle(CatalogPusher catalog, AuthzFeed feed, ScopeSync scopes) {
        this.catalog = catalog;
        this.feed = feed;
        this.scopes = scopes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        feed.start();
        worker = Thread.ofVirtual().name("pacod-id-sync").start(() -> {
            catalog.pushUntilTaken();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    scopes.reconcile();
                } catch (RuntimeException e) {
                    log.warn("Scope reconciliation with pacod-id failed: {}", e.getMessage());
                }
                try {
                    Thread.sleep(SYNC_EVERY);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        feed.stop();
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}
