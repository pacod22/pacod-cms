package io.pacod.cms.studio.identity;

import io.pacod.cms.core.identity.authz.Manifest;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

/**
 * Pushes {@code authz/cms.yaml} to pacod-id: {@code PUT /internal/v1/catalog/cms}.
 *
 * <p>pacod-id evaluates nothing for the studio, but it needs the catalogue to accept bindings of
 * {@code cms.*} roles at {@code cms.website} scopes, to check grants in its admin API, and to bind
 * the bootstrap admin's {@code cms.platform_admin} (which waits for the push). The body is the
 * manifest's shape-checked source as JSON -- not the YAML text -- with an empty {@code migrate}.
 * Idempotent: an unchanged manifest answers {@code changed: false}.
 *
 * <p>Pushed at every start, in the background, retried from one second to thirty until pacod-id
 * takes it -- so a studio started before pacod-id simply pushes once it is up.
 */
@Slf4j
public class CatalogPusher {

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final IdClient client;
    private final Manifest manifest;
    private volatile boolean pushed;

    public CatalogPusher(IdClient client, Manifest manifest) {
        this.client = client;
        this.manifest = manifest;
    }

    public boolean isPushed() {
        return pushed;
    }

    /** One attempt; true when pacod-id took it. */
    boolean pushOnce() {
        JsonNode answer = client.put("/internal/v1/catalog/" + manifest.product(),
                Map.of("manifest", manifest.source(), "migrate", Map.of()));
        pushed = true;
        log.info("pacod-id took the {} catalogue: version {}, {}", manifest.product(),
                answer.path("version").asLong(), answer.path("changed").asBoolean() ? "changed" : "unchanged");
        return true;
    }

    /** Pushes until it succeeds or the thread is interrupted. */
    void pushUntilTaken() {
        Duration backoff = Duration.ofSeconds(1);
        while (!pushed && !Thread.currentThread().isInterrupted()) {
            try {
                pushOnce();
            } catch (IdClient.IdException e) {
                if (e.status() == 409 || e.status() == 422) {
                    // A role or scope type in use, or a refused manifest: retrying cannot help.
                    log.error("pacod-id refused the {} catalogue: {}", manifest.product(), e.getMessage());
                    return;
                }
                backoff = sleep(backoff, e);
            } catch (RuntimeException e) {
                backoff = sleep(backoff, e);
            }
        }
    }

    private static Duration sleep(Duration backoff, Exception e) {
        log.warn("Could not push the catalogue to pacod-id yet ({}); retrying in {}s", e.getMessage(), backoff.toSeconds());
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Duration next = backoff.multipliedBy(2);
        return next.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : next;
    }
}
