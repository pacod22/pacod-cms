package io.pacod.cms.studio.config;

import io.pacod.cms.studio.access.ServiceTokenCheck;
import io.pacod.cms.studio.access.ServiceTokenFilter;
import io.pacod.cms.studio.identity.IdProperties;
import io.pacod.cms.studio.i18n.MgmtLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import java.time.Duration;
import java.util.Map;

/**
 * Who may reach the studio: people signed in through pacod-id.
 *
 * <h2>Sign-in is OpenID Connect against pacod-id</h2>
 * Authorization code with PKCE (pacod-id requires it of every client, confidential ones included),
 * through a {@link ClientRegistration} built by hand rather than from Boot's {@code issuer-uri}:
 * <ul>
 *   <li><b>No discovery.</b> pacod-id answers its browser endpoints -- discovery among them -- only
 *       when the {@code Host} is the issuer's own, and inside the compose network it is not. So
 *       the browser endpoints (authorize, end session) are the public issuer's, and the token and
 *       key endpoints the internal listener's; {@code issuerUri} is still set, for the ID token's
 *       {@code iss} check.</li>
 *   <li><b>Every OAuth endpoint sits under {@code /mgmt/}</b>
 *       ({@code /mgmt/oauth2/authorization/pacod-id}, {@code /mgmt/oauth2/code/pacod-id}).
 *       Spring's defaults, {@code /oauth2/**} and {@code /login/oauth2/**}, would fall outside
 *       this chain's matcher into the permit-all chain below, which signs nobody in, and the
 *       website filter treats both as platform screens ({@code StudioPaths}).</li>
 *   <li><b>The redirect and post-logout URIs are absolute, from configuration</b>
 *       ({@link IdProperties#studioOrigin()}), never {@code {baseUrl}}: inside a website the context
 *       path is the virtual {@code /sites/<id>}, so {@code {baseUrl}} would name an address
 *       pacod-id does not have registered and refuse.</li>
 *   <li><b>No user-info call.</b> The ID token carries the subject, the session id and -- with the
 *       {@code email} and {@code profile} scopes -- the address and name the chrome shows.</li>
 * </ul>
 *
 * <h2>What a signed-in person may do is not decided here</h2>
 * Every authenticated person reaches the studio's screens; tokens carry no roles. Each screen's
 * permission is checked by {@code PermissionInterceptor} against the person's role bindings,
 * which come from pacod-id's feed. Someone who signed up and holds no role sees an empty website
 * list and a 403 on every website screen.
 *
 * <h2>Staying signed in</h2>
 * The studio session is this JVM's heap and ends with it, or after {@code MGMT_SESSION_TIMEOUT}
 * idle; pacod-id's own session (with "keep me signed in", days) then signs the person straight
 * back in through the same redirect, without a password; the studio keeps no remember-me cookie
 * of its own. Signing out ends both (RP-initiated logout with the ID token as hint).
 *
 * <h2>The person's tokens stay with their session</h2>
 * The access and refresh tokens pacod-id issues at sign-in are kept in the studio session
 * ({@link HttpSessionOAuth2AuthorizedClientRepository}), not Spring's default store keyed by the
 * account: two browsers of one person would otherwise share -- and race to rotate -- one refresh
 * token, and pacod-id ends every session of an account whose refresh token is used twice. The
 * studio uses the access token only to act as the person on pacod-id's admin API (the members
 * screens, through {@code UserTokens}).
 *
 * <h2>CSRF</h2>
 * On, with the token in a cookie ({@code CookieCsrfTokenRepository}, path {@code /}, names pinned
 * to {@code _csrf}/{@code X-CSRF-TOKEN}, which {@code mgmt.js} reads from the head fragment's meta
 * tags). The path is written out: left to default it is the request's context path, which under
 * {@code /sites/<id>} is virtual, and a browser holding two cookies of one name refuses every POST.
 *
 * <h2>{@code __Host-} cookies in production</h2>
 * With {@code pacod.studio.host-cookies} (on in the prod profile) the CSRF cookie is
 * {@value #HOST_CSRF_COOKIE}, always {@code Secure}, and the prod profile names the session cookie
 * {@code __Host-studio-session} the same way ({@code server.servlet.session.cookie.*}). A browser
 * accepts a {@code __Host-} cookie only when it is {@code Secure}, has {@code Path=/} and names no
 * {@code Domain} -- so no other host, a website on a sibling subdomain included, can set or
 * overwrite one: the studio's CSRF token cannot be planted from a page its owner wrote. Off in
 * development, which runs on plain http, where a {@code Secure} cookie is never stored.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class MgmtSecurityConfig {

    /** The registration id: the last segment of both OAuth endpoints. */
    public static final String REGISTRATION = "pacod-id";

    public static final String AUTHORIZATION_BASE = "/mgmt/oauth2/authorization";
    public static final String CALLBACK_BASE = "/mgmt/oauth2/code";

    /** The CSRF cookie's name with {@code pacod.studio.host-cookies}; the repository's default otherwise. */
    public static final String HOST_CSRF_COOKIE = "__Host-XSRF-TOKEN";

    /** {@code ?signup} on the authorization endpoint opens pacod-id's sign-up page instead. */
    public static final String SIGNUP_PARAMETER = "signup";

    private static final Duration CSRF_COOKIE_LIFETIME = Duration.ofDays(30);

    @Bean
    @Order(1)
    public SecurityFilterChain mgmtSecurityFilterChain(HttpSecurity http,
                                                       ClientRegistrationRepository registrations,
                                                       CsrfTokenRepository mgmtCsrfTokenRepository,
                                                       OAuth2AuthorizedClientRepository authorizedClients,
                                                       IdProperties id) throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler();
        // The website list: signing in belongs to no website.
        success.setDefaultTargetUrl("/mgmt/sites");

        OidcClientInitiatedLogoutSuccessHandler logoutSuccess = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logoutSuccess.setPostLogoutRedirectUri(id.studioOrigin() + "/mgmt/login?loggedOut");

        http
            // A website's own files as well as the screens: the studio's image previews
            // (StudioMediaController) are a website's content.
            .securityMatcher("/mgmt/**", "/assets/**", "/resources/**")
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.csrfTokenRepository(mgmtCsrfTokenRepository))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/mgmt/login").permitAll()
                // The language switch is offered on the sign-in page too. Authenticated-only, the
                // request cache would save it and the operator would land on it after signing in.
                .requestMatchers("/mgmt/ui-language/*").permitAll()
                .requestMatchers(AUTHORIZATION_BASE + "/**", CALLBACK_BASE + "/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(login -> login
                .loginPage("/mgmt/login")
                .authorizedClientRepository(authorizedClients)
                .authorizationEndpoint(endpoint -> endpoint
                    .baseUri(AUTHORIZATION_BASE)
                    .authorizationRequestResolver(authorizationRequests(registrations)))
                .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_BASE + "/*"))
                .successHandler(success)
                .failureUrl("/mgmt/login?error")
            )
            .logout(logout -> logout
                .logoutUrl("/mgmt/logout")
                .logoutSuccessHandler(logoutSuccess)
            );

        return http.build();
    }

    /**
     * Scripts: {@code /internal/**} (the AI gateway's address and key, {@code InternalAiGatewayController}),
     * with a pacod-id service token as bearer ({@link ServiceTokenFilter}) and the same per-handler
     * permissions as the screens. Stateless and without CSRF -- no cookie is read or written here,
     * so there is nothing a browser could be made to send. Caddy does not route {@code /internal/};
     * callers use {@code cms-studio} on the compose network. The bearer check is the guard, as the
     * studio's own port is published too.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain internalApiFilterChain(HttpSecurity http, ObjectProvider<ServiceTokenCheck> tokens)
            throws Exception {
        http
            .securityMatcher("/internal/**")
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .anonymous(AbstractHttpConfigurer::disable)
            .addFilterBefore(new ServiceTokenFilter(tokens), AuthorizationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    ServiceTokenFilter.refuse(response, 401, "unauthenticated", "A bearer token is required"))
                .accessDeniedHandler((request, response, ex) ->
                    ServiceTokenFilter.refuse(response, 403, "forbidden", "Not allowed")))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());

        return http.build();
    }

    /**
     * Everything else: the studio's CSS and JavaScript, {@code /mgmt-health} and {@code /error}.
     * Permit-all and stateless, deliberately not {@code denyAll} -- what keeps this application
     * from serving anything else is its narrowed component scan, and {@code denyAll} risks turning
     * every error page into a 403.
     */
    @Bean
    @Order(5)
    public SecurityFilterChain mgmtPublicSurfaceFilterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .anonymous(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Hands out the session's access token, refreshing it through the token endpoint when due. */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                                 OAuth2AuthorizedClientRepository authorizedClients) {
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return manager;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(IdProperties id) {
        return new InMemoryClientRegistrationRepository(registration(id));
    }

    static ClientRegistration registration(IdProperties id) {
        String issuer = id.issuer();
        String internal = id.internalUrl();
        log.info("Studio sign-in through pacod-id at {} (server-to-server {})", issuer, internal);
        return ClientRegistration.withRegistrationId(REGISTRATION)
                .clientId(id.studioClientId())
                .clientSecret(id.studioSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(id.studioOrigin() + CALLBACK_BASE + "/" + REGISTRATION)
                .scope("openid", "email", "profile")
                .authorizationUri(issuer + "/oidc/authorize")
                .tokenUri(internal + "/oidc/token")
                .jwkSetUri(internal + "/oidc/jwks")
                .issuerUri(issuer)
                .userNameAttributeName("sub")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", issuer + "/oidc/logout"))
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .clientName("pacod-id")
                .build();
    }

    /**
     * Spring's resolver, plus two parameters pacod-id understands: {@code prompt=create} when the
     * person asked to create an account, and {@code ui_locales} in the studio's own language, so
     * the sign-in pages speak the language the person was just reading.
     */
    static OAuth2AuthorizationRequestResolver authorizationRequests(ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return customise(request, delegate.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return customise(request, delegate.resolve(request, clientRegistrationId));
            }
        };
    }

    private static OAuth2AuthorizationRequest customise(HttpServletRequest request, OAuth2AuthorizationRequest original) {
        if (original == null) {
            return null;
        }
        String uiLanguage = new MgmtLocaleResolver().resolveLocale(request).getLanguage();
        boolean signup = request.getParameter(SIGNUP_PARAMETER) != null;
        return OAuth2AuthorizationRequest.from(original)
                .additionalParameters(parameters -> {
                    parameters.put("ui_locales", uiLanguage);
                    if (signup) {
                        parameters.put("prompt", "create");
                    }
                })
                .build();
    }

    @Bean
    public CsrfTokenRepository mgmtCsrfTokenRepository(@Value("${pacod.studio.host-cookies:false}") boolean hostCookies) {
        return csrfTokenRepository(hostCookies);
    }

    static CsrfTokenRepository csrfTokenRepository(boolean hostCookies) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        // Pinned: this class defaults the header to X-XSRF-TOKEN; mgmt.js and every form send these.
        repository.setParameterName("_csrf");
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookiePath("/");
        if (hostCookies) {
            repository.setCookieName(HOST_CSRF_COOKIE);
        }
        // Longer than a session, so a page left open over a re-sign-in still posts. Secure always for
        // a __Host- cookie (the browser drops one without it); otherwise it follows the request.
        repository.setCookieCustomizer(cookie -> {
            cookie.maxAge(CSRF_COOKIE_LIFETIME).sameSite("Lax");
            if (hostCookies) {
                cookie.secure(true);
            }
        });
        return repository;
    }
}
