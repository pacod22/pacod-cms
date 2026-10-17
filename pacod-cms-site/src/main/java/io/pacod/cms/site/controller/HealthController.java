package io.pacod.cms.site.controller;

import io.pacod.cms.site.application.ApplicationHealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping(value = "/web/v1")
public class HealthController {

    private final ApplicationHealthService applicationHealthService;

    public HealthController(ApplicationHealthService applicationHealthService) {
        this.applicationHealthService = applicationHealthService;
    }

    /**
     * Public, since a {@code private} handler is at best a proxying hazard, and a real JSON body
     * with its content type: one built by hand with {@code String.format("{'status': '%s'}", ...)}
     * is not JSON -- single quotes are not valid there.
     *
     * <p>Note this reports a constant. For a check that actually probes MongoDB, prefer
     * Spring Boot Actuator's {@code /actuator/health}, which ships a Mongo health indicator.
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", String.valueOf(applicationHealthService.health())));
    }

}
