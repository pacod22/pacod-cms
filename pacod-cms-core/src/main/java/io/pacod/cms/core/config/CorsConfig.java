package io.pacod.cms.core.config;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Single source of truth for CORS.
 *
 * <p>Add no hand-written CORS {@code Filter} and no {@code @CrossOrigin} on a controller. Two
 * sources disagree: a filter writing one origin into {@code Access-Control-Allow-Origin} with
 * {@code Allow-Credentials: true} beside an annotation saying {@code *} -- and browsers reject
 * {@code *} combined with credentials outright, so which behaviour you get depends on which
 * wrote the header last.
 *
 * <p>Nor may anything answer preflights itself: a filter at {@code HIGHEST_PRECEDENCE} that
 * short-circuits every OPTIONS request with 200 without calling {@code chain.doFilter} answers
 * them before the Spring Security chain ever executes. Spring Security consumes this bean and
 * handles preflight correctly on its own.
 *
 * <p>Origins are registered as <em>patterns</em>, not as a fixed list. A pattern containing no
 * wildcard matches exactly the string it is written as, so a plain origin behaves the same either
 * way; what patterns add is {@code http://localhost:[*]}, which is what the local stack needs --
 * it is reachable on several ports at once (Caddy on 8000/8443, and the two applications directly
 * on 8080/7070 when you bypass Caddy), and enumerating them is a list that goes stale.
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:[*]}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = List.of(allowedOrigins.split("\\s*,\\s*"));
        log.info("Initializing CORS for origin patterns: {}", origins);

        // Never a bare "*". setAllowedOrigins throws on "*" combined with credentials, but
        // setAllowedOriginPatterns accepts it happily and reflects whatever Origin was sent --
        // so with patterns, what would be a startup failure silently becomes an open CORS
        // policy on a credentialed endpoint. Scope the wildcard instead: "http://localhost:[*]".
        if (origins.contains("*")) {
            throw new IllegalStateException("app.cors.allowed-origins must not contain \"*\": "
                    + "credentials are allowed, so every origin must be scoped to a host. "
                    + "Use a pattern such as http://localhost:[*] instead.");
        }
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("x-requested-with", "authorization", "origin",
                "content-type", "enctype"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
