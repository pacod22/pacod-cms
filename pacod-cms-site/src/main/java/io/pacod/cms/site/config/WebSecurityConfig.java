package io.pacod.cms.site.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for the public site.
 *
 * <p>One chain, because this application serves one surface. Signing in belongs to the studio
 * ({@code MgmtSecurityConfig} in {@code pacod-cms-studio}), through pacod-id.
 *
 * <p>Two rules follow, pinned by {@code WebApplicationTests}, and should not be loosened:
 *
 * <ul>
 *   <li><b>Exactly one filter chain.</b> A second one here means a security configuration has
 *       leaked past {@code WebApplication}'s narrowed component scan. A chain that carries no
 *       {@code securityMatcher}, loose in this context, would match every request ahead of this
 *       one.</li>
 *   <li><b>No {@code UserDetailsService} at all.</b> There is nothing to authenticate against
 *       here. {@code WebApplication} excludes {@code UserDetailsServiceAutoConfiguration} so that
 *       Spring Boot does not helpfully supply a generated-password in-memory user in its
 *       place.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    /** Public, read-only site. */
    @Bean
    @Order(5)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
                                                      @Value("${pacod.studio.origin:}") String studioOrigin) throws Exception {
        log.info("Initializing webSecurityFilterChain");

        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .anonymous(Customizer.withDefaults())
            // frame-ancestors instead of X-Frame-Options: DENY, which blanks every HTML5 booking:
            // the website frames its own /resources/banners/ (FramingHeaders).
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                .addHeaderWriter(new FramingHeaders(studioOrigin)))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
