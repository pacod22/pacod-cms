package io.pacod.cms.studio.broker;

import io.pacod.cms.core.control.repository.BrokerCredentialRepository;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.repository.SiteFilesRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.studio.identity.Provisioner;
import io.pacod.cms.studio.site.StudioSites;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * pacod-broker, on while {@code pacod.broker.url} ({@code PACOD_BROKER_URL}) is set:
 *
 * <ul>
 *   <li>{@link BrokerFacts} for the installation screen's AI card, with the studio's consumer token
 *       ({@code pacod.broker.token}, {@code PACOD_BROKER_TOKEN}; blank = a broker in open mode);</li>
 *   <li>{@link BrokerAnnouncer} and {@link BrokerProviders}, which announce the offered websites
 *       under each organisation's own provider, made by the provisioner; switched off by
 *       {@code pacod.broker.announce=false} (the test profile and the {@code --create-site} run).
 *       {@code pacod.broker.open-mode} says the broker needs no tokens.</li>
 * </ul>
 */
@Configuration
@ConditionalOnExpression("!'${pacod.broker.url:}'.isBlank()")
public class BrokerConfig {

    @Bean
    BrokerClient brokerClient(@Value("${pacod.broker.url}") String url, ObjectMapper json) {
        return new BrokerClient(url.strip(), json);
    }

    @Bean
    BrokerFacts brokerFacts(BrokerClient client, @Value("${pacod.broker.token:}") String token) {
        return new BrokerFacts(client, token);
    }

    @Bean
    @ConditionalOnExpression("${pacod.broker.announce:true}")
    BrokerProviders brokerProviders(BrokerCredentialRepository credentials, ObjectProvider<Provisioner> provisioner,
                                    @Value("${pacod.broker.open-mode:false}") boolean openMode) {
        return new BrokerProviders(credentials, provisioner::getIfAvailable, openMode);
    }

    @Bean
    @ConditionalOnExpression("${pacod.broker.announce:true}")
    BrokerAnnouncer brokerAnnouncer(WebsiteRepository websites, OrganisationRepository organisations,
                                    SiteSettingsRepository settings, SiteFilesRepository files, StudioSites sites,
                                    BrokerClient client, BrokerProviders providers,
                                    @Value("${pacod.broker.refresh:60s}") Duration refresh,
                                    @Value("${pacod.broker.ttl:60}") int ttl) {
        return new BrokerAnnouncer(websites, organisations, settings, files, sites, client, providers, refresh, ttl);
    }
}
