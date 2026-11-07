package io.pacod.cms.studio.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The studio starts with a broker set, as pacod-demo starts it: {@code PACOD_BROKER_URL} named, no
 * token yet (the demo's bootstrap makes it later), the announcer on and its interval unset. Every other test runs
 * without a broker, where {@link BrokerConfig} creates nothing, so a missing dependency of its beans
 * would only show when a stack set the URL -- as a studio that does not start.
 *
 * <p>The broker's address answers nothing: the announcer's rounds fail and are logged, which is what
 * happens before the bootstrap.
 */
@SpringBootTest(properties = {"pacod.broker.url=http://127.0.0.1:9", "pacod.broker.announce=true"})
@ActiveProfiles("test")
class BrokerConfigContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("with a broker's address, the studio starts with the client, the facts, the providers and the announcer")
    void startsWithABroker() {
        assertThat(context.getBeansOfType(BrokerClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerFacts.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerProviders.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerAnnouncer.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerRefresh.class)).hasSize(1);
        // Nothing stored and nothing set: the 60 seconds the announcer has always used.
        assertThat(context.getBean(BrokerRefresh.class).environment()).hasSeconds(60);
        // Without the consumer token the facts read as nothing, never as a failure of the screen.
        assertThat(context.getBean(BrokerFacts.class).of("not-a-resource")).isEmpty();
    }
}
