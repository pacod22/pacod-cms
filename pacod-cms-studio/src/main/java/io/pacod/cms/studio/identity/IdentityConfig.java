package io.pacod.cms.studio.identity;

import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.studio.access.StudioAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The studio's pacod-id integration. The client registration for sign-in is always there
 * ({@code MgmtSecurityConfig}); the background parts -- feed, catalogue, scopes -- only while
 * {@code pacod.id.enabled}, which the tests and the {@code --create-site} run switch off.
 */
@Configuration
@EnableConfigurationProperties(IdProperties.class)
public class IdentityConfig {

    @Bean
    UserTokens userTokens(OAuth2AuthorizedClientManager manager) {
        return new UserTokens(manager);
    }

    @Configuration
    @ConditionalOnProperty(name = "pacod.id.enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {

        @Bean
        IdClient idClient(IdProperties id, ObjectMapper json) {
            return new IdClient(id, json);
        }

        /** Ended sessions are remembered as long as a studio session may sit idle. */
        @Bean
        AuthzFeed authzFeed(IdClient client,
                            @Value("${server.servlet.session.timeout:30m}") java.time.Duration sessionTimeout) {
            return new AuthzFeed(client, sessionTimeout);
        }

        /** Scripts' {@code pidt_} tokens on {@code /internal/**}. */
        @Bean
        ServiceTokens serviceTokens(IdClient client, AuthzFeed feed, IdProperties id) {
            return new ServiceTokens(client, feed, id.maxStaleness());
        }

        @Bean
        CatalogPusher catalogPusher(IdClient client, StudioAccess access) {
            return new CatalogPusher(client, access.manifest());
        }

        @Bean
        ScopeSync scopeSync(IdClient client, AuthzFeed feed, OrganisationRepository organisations,
                            WebsiteRepository websites) {
            return new ScopeSync(client, feed, organisations, websites);
        }

        @Bean
        TokenExchange tokenExchange(IdProperties id, ObjectMapper json) {
            return new TokenExchange(id, json);
        }

        @Bean
        IdAdmin idAdmin(IdProperties id, ObjectMapper json) {
            return new IdAdmin(id, json);
        }

        /**
         * Only with a token ({@code PACOD_ID_PROVISIONER_TOKEN}, written by
         * {@code make id-provisioner}). Without one, nobody but a platform admin can create an
         * organisation: there would be no way to make its creator its admin.
         */
        @Bean
        @ConditionalOnExpression("!'${pacod.id.provisioner-token:}'.isBlank()")
        Provisioner provisioner(@Value("${pacod.id.provisioner-token}") String token, TokenExchange exchange,
                                IdAdmin admin) {
            return new Provisioner(token.strip(), exchange, admin);
        }

        @Bean
        IdentityLifecycle identityLifecycle(CatalogPusher catalog, AuthzFeed feed, ScopeSync scopes) {
            return new IdentityLifecycle(catalog, feed, scopes);
        }
    }
}
