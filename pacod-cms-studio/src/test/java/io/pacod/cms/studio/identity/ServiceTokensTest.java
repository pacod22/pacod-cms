package io.pacod.cms.studio.identity;

import io.pacod.cms.studio.access.ServiceTokenCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** A script's token checked by introspection as the service client, against a stand-in for pacod-id. */
class ServiceTokensTest {

    private static final String INTROSPECT = "http://pacod-id:8071/internal/v1/introspect";
    private static final String SERVICE = "{\"active\":true,\"sub\":\"usr_script\",\"pid_kind\":\"service\",\"token_id\":\"tok_1\"}";

    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private AuthzFeed feed;
    private ServiceTokens tokens;
    private Instant now = Instant.parse("2026-10-31T10:00:00Z");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pacod-id:8071")
                .defaultHeader("Authorization", IdClient.basic("pacod-cms-service", "secret"));
        server = MockRestServiceServer.bindTo(builder).build();
        feed = mock(AuthzFeed.class);
        when(feed.isReady()).thenReturn(true);
        when(feed.staleness()).thenReturn(Duration.ofSeconds(1));
        when(feed.isActive("usr_script")).thenReturn(true);
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        tokens = new ServiceTokens(new IdClient(builder.build(), json), feed, Duration.ofSeconds(60), clock);
    }

    @Test
    @DisplayName("an active service token is its account, asked once as the service client and then cached")
    void activeServiceToken() {
        server.expect(once(), requestTo(INTROSPECT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", IdClient.basic("pacod-cms-service", "secret")))
                .andExpect(content().formDataContains(Map.of("token", "pidt_abc_def")))
                .andRespond(withSuccess(SERVICE, MediaType.APPLICATION_JSON));
        assertThat(tokens.subjectOf("pidt_abc_def")).contains("usr_script");
        assertThat(tokens.subjectOf("pidt_abc_def")).contains("usr_script");
        server.verify();
    }

    @Test
    @DisplayName("a person's token, an inactive one and a disabled account are no one")
    void refusals() {
        server.expect(once(), requestTo(INTROSPECT))
                .andRespond(withSuccess("{\"active\":true,\"sub\":\"usr_person\",\"pid_kind\":\"user\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(INTROSPECT))
                .andRespond(withSuccess("{\"active\":false}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(INTROSPECT))
                .andRespond(withSuccess(SERVICE, MediaType.APPLICATION_JSON));
        assertThat(tokens.subjectOf("eyJ.person")).isEmpty();
        assertThat(tokens.subjectOf("pidt_gone")).isEmpty();
        when(feed.isActive("usr_script")).thenReturn(false);
        assertThat(tokens.subjectOf("pidt_disabled")).isEmpty();
        assertThat(tokens.subjectOf("")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a revoked token is asked again at once: the feed's credential epoch moved")
    void revocationDropsTheCache() {
        server.expect(twice(), requestTo(INTROSPECT)).andRespond(withSuccess(SERVICE, MediaType.APPLICATION_JSON));
        when(feed.credentialEpoch()).thenReturn(1L);
        tokens.subjectOf("pidt_abc_def");
        tokens.subjectOf("pidt_abc_def");
        when(feed.credentialEpoch()).thenReturn(2L);
        tokens.subjectOf("pidt_abc_def");
        server.verify();
    }

    @Test
    @DisplayName("the cache lasts a minute")
    void cacheExpires() {
        server.expect(twice(), requestTo(INTROSPECT)).andRespond(withSuccess(SERVICE, MediaType.APPLICATION_JSON));
        tokens.subjectOf("pidt_abc_def");
        now = now.plus(ServiceTokens.TTL).plusSeconds(1);
        tokens.subjectOf("pidt_abc_def");
        server.verify();
    }

    @Test
    @DisplayName("an old feed or an unreachable pacod-id is unavailable, never a refusal")
    void unavailable() {
        when(feed.staleness()).thenReturn(Duration.ofMinutes(5));
        assertThatThrownBy(() -> tokens.subjectOf("pidt_abc_def")).isInstanceOf(ServiceTokenCheck.Unavailable.class);

        when(feed.staleness()).thenReturn(Duration.ofSeconds(1));
        server.expect(once(), requestTo(INTROSPECT)).andRespond(withServerError());
        assertThatThrownBy(() -> tokens.subjectOf("pidt_abc_def")).isInstanceOf(ServiceTokenCheck.Unavailable.class);
    }
}
