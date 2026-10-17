package io.pacod.cms.studio.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a stand-in for pacod-gw on a local port. */
class AiGatewayTest {

    private HttpServer server;
    private final List<Map<String, String>> requests = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {
    }

    private AiGateway serve(boolean tags, Reply... replies) throws IOException {
        AtomicInteger next = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            Map<String, String> seen = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), v.getFirst()));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(seen);
            Reply reply = replies[Math.min(next.getAndIncrement(), replies.length - 1)];
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new AiGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/", "pgw_cms_test", Duration.ofSeconds(5), tags);
    }

    private static Reply ok(String content, String finish) {
        return new Reply(200, "{\"choices\":[{\"message\":{\"content\":" + quote(content) + ",\"refusal\":null},\"finish_reason\":\""
                + finish + "\"}],\"usage\":{\"cost\":{\"amount\":0.0042,\"currency\":\"USD\"}}}", Map.of("x-gw-request-id", "gw_1"));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static AiGateway.Call translation() {
        return new AiGateway.Call("translate", "@fast", "Translate.", "<p>Добро утро</p>", null, "translation",
                Map.of("type", "object"), 600, "demo", "usr_1", "0123456789abcdef01234567");
    }

    @Test
    @DisplayName("a call carries the key, opaque attribution and a strict schema; the answer, its cost and id come back")
    void sendsAndReads() throws Exception {
        AiGateway gateway = serve(false, ok("{\"text\":\"<p>Guten Morgen</p>\"}", "stop"));
        AiGateway.Answer answer = gateway.complete(translation());

        assertThat(answer.json().path("text").asString()).isEqualTo("<p>Guten Morgen</p>");
        assertThat(answer.costMicros()).isEqualTo(4200);
        assertThat(answer.requestId()).isEqualTo("gw_1");
        Map<String, String> sent = requests.getFirst();
        assertThat(sent).containsEntry("authorization", "Bearer pgw_cms_test")
                .containsEntry("x-gw-end-user", "demo").containsEntry("x-gw-task", "translate")
                .containsEntry("x-gw-trace", "0123456789abcdef01234567").containsEntry("x-gw-user-id", "usr_1")
                .doesNotContainKey("x-gw-tags");
        assertThat(sent.get("body")).contains("\"model\":\"@fast\"", "\"json_schema\"", "\"strict\":true", "\"max_tokens\":600",
                "\"role\":\"system\"", "Добро утро");
    }

    @Test
    @DisplayName("tags only when switched on")
    void tags() throws Exception {
        serve(true, ok("{\"text\":\"x\"}", "stop")).complete(translation());
        assertThat(requests.getFirst()).containsEntry("x-gw-tags", "site=demo,feature=translate");
    }

    @Test
    @DisplayName("a 429 is retried once after its Retry-After; a second is a failure")
    void retriesA429Once() throws Exception {
        AiGateway gateway = serve(false, new Reply(429, "{}", Map.of("Retry-After", "0")), ok("{\"text\":\"x\"}", "stop"));
        assertThat(gateway.complete(translation()).json().path("text").asString()).isEqualTo("x");
        assertThat(requests).hasSize(2);

        stop();
        requests.clear();
        AiGateway busy = serve(false, new Reply(429, "{}", Map.of("Retry-After", "0")));
        assertThatThrownBy(() -> busy.complete(translation())).hasMessageContaining("busy");
        assertThat(requests).hasSize(2);
    }

    @Test
    @DisplayName("ping: one small call naming no website or person")
    void ping() throws Exception {
        AiGateway gateway = serve(false, ok("{\"reply\":\"pong\"}", "stop"));
        assertThat(gateway.ping("@fast").requestId()).isEqualTo("gw_1");
        Map<String, String> sent = requests.getFirst();
        assertThat(sent).containsEntry("x-gw-task", "ping").doesNotContainKey("x-gw-end-user")
                .doesNotContainKey("x-gw-user-id").doesNotContainKey("x-gw-trace");
        assertThat(sent.get("body")).contains("\"max_tokens\":64", "\"model\":\"@fast\"");
    }

    @Test
    @DisplayName("a refusal names the gateway's error code and request id, and quotes nothing else of its answer")
    void refusalDetails() throws Exception {
        AiGateway gateway = serve(false, new Reply(402,
                "{\"error\":{\"message\":\"Workspace acme has spent 12.00 USD of 12.00 USD\",\"code\":\"quota_exceeded\"}}",
                Map.of("x-gw-request-id", "req_01HXYZ")));
        assertThatThrownBy(() -> gateway.ping("@fast"))
                .hasMessage("The AI budget is used up (quota_exceeded, request req_01HXYZ)");
        stop();
        requests.clear();
        AiGateway odd = serve(false, new Reply(500, "{\"error\":{\"code\":\"<script>x</script>\"}}", Map.of()));
        assertThatThrownBy(() -> odd.ping("@fast")).hasMessage("The AI gateway answered 500");
    }

    @Test
    @DisplayName("a 402 or a 504 is never retried")
    void neverRetried() throws Exception {
        AiGateway spent = serve(false, new Reply(402, "{}", Map.of()));
        assertThatThrownBy(() -> spent.complete(translation())).hasMessageContaining("budget");
        assertThat(requests).hasSize(1);
        stop();
        requests.clear();
        AiGateway slow = serve(false, new Reply(504, "{}", Map.of()));
        assertThatThrownBy(() -> slow.complete(translation())).hasMessageContaining("did not answer");
        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("an answer cut short, a refusal, or not the JSON asked for is a failure")
    void unusableAnswers() throws Exception {
        assertThatThrownBy(() -> serve(false, ok("{\"text\":\"half", "length")).complete(translation())).hasMessageContaining("cut off");
        stop();
        assertThatThrownBy(() -> serve(false, new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"\",\"refusal\":\"no\"},\"finish_reason\":\"stop\"}]}",
                Map.of())).complete(translation())).hasMessageContaining("declined");
        stop();
        assertThatThrownBy(() -> serve(false, ok("not json", "stop")).complete(translation())).hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("no address or key: not set up, and nothing is sent")
    void notSetUp() {
        AiGateway off = new AiGateway("http://127.0.0.1:1", "", Duration.ofSeconds(1), false);
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.complete(translation())).hasMessageContaining("not set up");
    }
}
