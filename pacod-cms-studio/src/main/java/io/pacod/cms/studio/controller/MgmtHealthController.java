package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.PlatformScreen;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness for the console container.
 *
 * <p>{@code /mgmt-health}, not {@code /mgmt/health}: anything under {@code /mgmt/} is inside
 * {@code MgmtSecurityConfig}'s authenticated matcher, and a healthcheck that has to log in is not
 * a healthcheck. Same reasoning, and the same prefix trick, as {@code /mgmt-assets/}.
 *
 * <p>Docker's healthcheck calls this. It is reachable on the studio's host as well, through Caddy,
 * and says nothing there but that the JVM is up.
 *
 * <p>Liveness only, deliberately. It says the JVM is up and serving; it says nothing about whether
 * anyone can sign in: that is pacod-id's, and a studio that cannot reach it answers 503 on its
 * screens while this stays up -- healthy by this endpoint's definition, and said in its log.
 */
@PlatformScreen
@RestController
public class MgmtHealthController {

    @GetMapping("/mgmt-health")
    public Map<String, String> health() {
        return Map.of("status", "OK");
    }
}
