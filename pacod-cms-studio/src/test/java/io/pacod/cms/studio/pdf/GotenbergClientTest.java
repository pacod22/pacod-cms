package io.pacod.cms.studio.pdf;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a stand-in for Gotenberg on a local port: what is sent, and what is made of the answer. */
class GotenbergClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(int status, byte[] body, AtomicReference<String> received) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forms/chromium/convert/url", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("the page, print media, A4, and every header scoped to the site's own origin")
    void request() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        String url = serve(200, "%PDF-1.7 x".getBytes(), received);
        GotenbergClient client = new GotenbergClient(url + "/", Duration.ofSeconds(5));

        byte[] pdf = client.render("http://site:8443/article-print/12?lang=de", "http://site:8443",
                Map.of("X-Forwarded-Host", "demo.example"));

        assertThat(new String(pdf, StandardCharsets.US_ASCII)).startsWith("%PDF");
        assertThat(received.get())
                .contains("name=\"url\"", "http://site:8443/article-print/12?lang=de")
                .contains("name=\"emulatedMediaType\"", "print")
                .contains("name=\"paperWidth\"", "8.27")
                // JSON-escaped once: the scope is a regex quoting the origin, carried after the value.
                .contains("\"X-Forwarded-Host\":\"demo.example;scope=^\\\\Qhttp://site:8443\\\\E/.*\"");
    }

    @Test
    @DisplayName("Gotenberg's refusal reaches the job's log in its own words; a non-PDF answer is refused")
    void failures() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        GotenbergClient refused = new GotenbergClient(serve(400, "Invalid form data: url is not allowed".getBytes(), received),
                Duration.ofSeconds(5));
        assertThatThrownBy(() -> refused.render("http://elsewhere/", "http://elsewhere", Map.of()))
                .isInstanceOf(GotenbergClient.PdfException.class)
                .hasMessageContaining("400").hasMessageContaining("url is not allowed");
        stop();

        GotenbergClient html = new GotenbergClient(serve(200, "<html>".getBytes(), received), Duration.ofSeconds(5));
        assertThatThrownBy(() -> html.render("http://site:8443/x", "http://site:8443", Map.of()))
                .hasMessageContaining("not a PDF");
    }

    @Test
    @DisplayName("no address: off, and saying so")
    void off() {
        GotenbergClient off = new GotenbergClient("  ", Duration.ofSeconds(5));
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.render("http://site:8443/x", "http://site:8443", Map.of()))
                .hasMessageContaining("off");
    }
}
