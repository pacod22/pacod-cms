package io.pacod.cms.studio.identity;

import io.pacod.cms.core.identity.authz.Binding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The feed against pacod-id's wire format, as its own reference client reads it. */
class AuthzFeedTest {

    private static final String SNAPSHOT = """
            { "seq": 10, "product": "cms", "catalog": null,
              "scopes": [
                {"type":"organisation","id":"org_a","slug":"org_a","display_name":"A","status":"active","system":false,
                 "products":["cms"],"path":["platform","organisation:org_a"]},
                {"type":"cms.website","id":"w1","parent":"organisation:org_a","display_name":"W1","system":false,
                 "path":["platform","organisation:org_a","cms.website:w1"]} ],
              "bindings": [
                {"id":"rb_1","principal":"usr_1","principal_kind":"user","role":"cms.editor",
                 "path":["platform","organisation:org_a","cms.website:w1"],"source":"manual"},
                {"id":"rb_2","principal":"usr_2","principal_kind":"user","role":"cms.platform_admin",
                 "path":["platform"],"source":"manual"} ],
              "disabled": ["usr_9"], "deleted": ["usr_8"],
              "revoked_sids": [{"sid":"sid_old","sub":"usr_1"}] }
            """;

    private MockRestServiceServer server;
    private AuthzFeed feed;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://id-internal.test");
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        IdClient client = new IdClient(builder.build(), JsonMapper.builder().build());
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        feed = new AuthzFeed(client, clock);
    }

    private void snapshot() {
        server.expect(requestTo("http://id-internal.test/internal/v1/authz/snapshot?product=cms"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        feed.loadSnapshot();
        server.verify();
        // Ordered expectations cannot be added once requests were made; each test sets its own next.
        server.reset();
    }

    @Test
    @DisplayName("the snapshot gives each person their bindings, and marks the disabled, deleted and ended")
    void snapshotLoads() {
        assertThat(feed.isReady()).isFalse();
        snapshot();

        assertThat(feed.isReady()).isTrue();
        assertThat(feed.seq()).isEqualTo(10);
        assertThat(feed.subjectOf("usr_1").bindings()).containsExactly(
                new Binding("cms.editor", List.of("platform", "organisation:org_a", "cms.website:w1")));
        assertThat(feed.isActive("usr_9")).isFalse();
        assertThat(feed.subjectOf("usr_9").bindings()).isEmpty();
        assertThat(feed.isActive("usr_8")).isFalse();
        assertThat(feed.isRevoked("sid_old")).isTrue();
        assertThat(feed.scopes()).extracting(AuthzFeed.Scope::key)
                .containsExactlyInAnyOrder("organisation:org_a", "cms.website:w1");
    }

    @Test
    @DisplayName("a revoked service token and a snapshot each move the credential epoch, which drops cached token checks")
    void credentialEpochMoves() {
        long before = feed.credentialEpoch();
        snapshot();
        long afterSnapshot = feed.credentialEpoch();
        assertThat(afterSnapshot).isGreaterThan(before);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://id-internal.test/internal/v1/authz/changes")))
                .andRespond(withSuccess("""
                        { "seq": 11, "events": [
                          {"seq":11,"kind":"credential.revoked","token_id":"tok_1","sub":"usr_script"} ] }
                        """, MediaType.APPLICATION_JSON));
        feed.poll();
        assertThat(feed.credentialEpoch()).isGreaterThan(afterSnapshot);
    }

    @Test
    @DisplayName("events are applied, and the next poll starts from the answer's own seq")
    void changesApplyAndAdvance() {
        snapshot();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://id-internal.test/internal/v1/authz/changes")))
                .andExpect(queryParam("after", "10"))
                .andExpect(queryParam("wait", "25"))
                .andRespond(withSuccess("""
                        { "seq": 17, "events": [
                          {"seq":11,"kind":"binding.added","id":"rb_3","principal":"usr_1","principal_kind":"user",
                           "role":"cms.author","path":["platform","organisation:org_a"],"source":"manual"},
                          {"seq":12,"kind":"binding.removed","id":"rb_1","principal":"usr_1","role":"cms.editor",
                           "path":["platform","organisation:org_a","cms.website:w1"]},
                          {"seq":13,"kind":"principal.disabled","sub":"usr_2","principal_kind":"user"},
                          {"seq":14,"kind":"principal.enabled","sub":"usr_9","principal_kind":"user"},
                          {"seq":15,"kind":"session.revoked","sid":"sid_new","sub":"usr_1","reason":"logout"},
                          {"seq":16,"kind":"scope.deleted","type":"cms.website","id":"w1","path":[]} ] }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://id-internal.test/internal/v1/authz/changes")))
                // 17, the top-level seq: events of other products may lie between.
                .andExpect(queryParam("after", "17"))
                .andRespond(withSuccess("{\"seq\": 17, \"events\": []}", MediaType.APPLICATION_JSON));

        feed.poll();
        feed.poll();

        assertThat(feed.subjectOf("usr_1").bindings()).containsExactly(
                new Binding("cms.author", List.of("platform", "organisation:org_a")));
        assertThat(feed.isActive("usr_2")).isFalse();
        assertThat(feed.isActive("usr_9")).isTrue();
        assertThat(feed.isRevoked("sid_new")).isTrue();
        assertThat(feed.scopes()).extracting(AuthzFeed.Scope::key).containsExactly("organisation:org_a");
        server.verify();
    }

    @Test
    @DisplayName("a 410 is answered by pacod-id's error shape and read as cursor_expired")
    void goneIsRecognised() {
        snapshot();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://id-internal.test/internal/v1/authz/changes")))
                .andRespond(withStatus(HttpStatus.GONE).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"type":"gone","message":"load the snapshot","details":{"code":"cursor_expired"}}}
                        """));

        IdClient.IdException gone = org.junit.jupiter.api.Assertions.assertThrows(IdClient.IdException.class, feed::poll);
        assertThat(gone.status()).isEqualTo(410);
        assertThat(gone.code()).isEqualTo("cursor_expired");
    }

    @Test
    @DisplayName("staleness counts from pacod-id's last answer, empty polls included")
    void stalenessFollowsContact() {
        assertThat(feed.staleness()).isGreaterThan(Duration.ofDays(1));
        snapshot();
        now.set(now.get().plusSeconds(45));
        assertThat(feed.staleness()).isEqualTo(Duration.ofSeconds(45));

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://id-internal.test/internal/v1/authz/changes")))
                .andRespond(withSuccess("{\"seq\": 10, \"events\": []}", MediaType.APPLICATION_JSON));
        feed.poll();
        assertThat(feed.staleness()).isZero();
    }

    @Test
    @DisplayName("deletions are confirmed for people, from the snapshot and from events, and not for services")
    void deletionsAreConfirmed() {
        snapshot();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://id-internal.test/internal/v1/authz/changes")))
                .andRespond(withSuccess("""
                        { "seq": 12, "events": [
                          {"seq":11,"kind":"principal.deleted","sub":"usr_7","principal_kind":"user","external_ids":[]},
                          {"seq":12,"kind":"principal.deleted","sub":"svc_1","principal_kind":"service"} ] }
                        """, MediaType.APPLICATION_JSON));
        feed.poll();
        server.verify();
        server.reset();

        server.expect(requestTo("http://id-internal.test/internal/v1/deletions/usr_8/done?product=cms"))
                .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://id-internal.test/internal/v1/deletions/usr_7/done?product=cms"))
                .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        // Unordered: the pending set has no order.
        feed.confirmDeletions();
        server.verify();

        // Confirmed once is enough.
        feed.confirmDeletions();
    }

    @Test
    @DisplayName("awaitSeq returns once the copy has caught up, and false when it does not")
    void awaitSeq() throws InterruptedException {
        snapshot();
        assertThat(feed.awaitSeq(10, Duration.ofMillis(10))).isTrue();
        assertThat(feed.awaitSeq(11, Duration.ofMillis(20))).isFalse();
    }

    @Test
    @DisplayName("an ended session is remembered as long as a studio session may sit idle, across a snapshot reload")
    void revokedSessionsOutliveIdleSessions() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://id-internal.test");
        MockRestServiceServer local = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        AuthzFeed eightHours = new AuthzFeed(new IdClient(builder.build(), JsonMapper.builder().build()), clock,
                java.time.Duration.ofHours(8));
        local.expect(requestTo("http://id-internal.test/internal/v1/authz/snapshot?product=cms"))
                .andRespond(withSuccess(SNAPSHOT, MediaType.APPLICATION_JSON));
        eightHours.loadSnapshot();
        local.reset();

        now.set(now.get().plus(java.time.Duration.ofHours(2)));
        eightHours.pruneRevokedSids();
        assertThat(eightHours.isRevoked("sid_old")).as("well past pacod-id's 15 minutes").isTrue();

        // A 410 reloads the snapshot, which by now no longer lists the old revocation.
        local.expect(requestTo("http://id-internal.test/internal/v1/authz/snapshot?product=cms"))
                .andRespond(withSuccess(SNAPSHOT.replace("{\"sid\":\"sid_old\",\"sub\":\"usr_1\"}", ""),
                        MediaType.APPLICATION_JSON));
        eightHours.loadSnapshot();
        assertThat(eightHours.isRevoked("sid_old")).isTrue();

        now.set(now.get().plus(java.time.Duration.ofHours(7)));
        eightHours.pruneRevokedSids();
        assertThat(eightHours.isRevoked("sid_old")).as("past any idle session").isFalse();
    }
}
