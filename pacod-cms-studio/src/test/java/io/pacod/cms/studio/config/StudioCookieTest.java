package io.pacod.cms.studio.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The studio's cookies as the production profile sets them, through a real Tomcat -- the session
 * cookie is the container's, and MockMvc never writes one. A browser keeps a {@code __Host-} cookie
 * only when it is {@code Secure}, has {@code Path=/} and names no {@code Domain}; one that fails
 * any of the three is silently dropped, and every sign-in and every POST with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.name=__Host-studio-session",
        "server.servlet.session.cookie.secure=true",
        "server.servlet.session.cookie.path=/",
        "server.servlet.session.cookie.same-site=lax",
        "pacod.studio.host-cookies=true"})
@ActiveProfiles("test")
class StudioCookieTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private List<String> setCookies(String path) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.discarding());
        return response.headers().allValues("Set-Cookie");
    }

    private static String cookie(List<String> headers, String name) {
        return headers.stream().filter(h -> h.startsWith(name + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in " + headers));
    }

    @Test
    @DisplayName("the session cookie, made by the sign-in's start, is a __Host- cookie: Secure, Path=/, no Domain, Lax")
    void sessionCookie() throws Exception {
        String session = cookie(setCookies("/mgmt/oauth2/authorization/pacod-id"), "__Host-studio-session");

        assertThat(session).contains("; Secure", "; Path=/", "; HttpOnly").containsIgnoringCase("SameSite=Lax")
                .doesNotContainIgnoringCase("Domain=");
        assertThat(session).doesNotContainPattern("Path=/[^;]");
    }

    @Test
    @DisplayName("the CSRF cookie, made by the sign-in page, is a __Host- cookie too, Secure although the request was not")
    void csrfCookie() throws Exception {
        String csrf = cookie(setCookies("/mgmt/login"), "__Host-XSRF-TOKEN");

        assertThat(csrf).contains("; Secure", "; Path=/").containsIgnoringCase("SameSite=Lax")
                .doesNotContainIgnoringCase("Domain=");
        assertThat(csrf).doesNotContainPattern("Path=/[^;]");
    }

    @Test
    @DisplayName("the production profile sets exactly these, so a deployment gets them without asking")
    void productionProfileSetsThem() throws Exception {
        Properties prod = new Properties();
        try (InputStream in = new ClassPathResource("application-prod.properties").getInputStream()) {
            prod.load(in);
        }
        assertThat(prod).containsEntry("server.servlet.session.cookie.name", "__Host-studio-session")
                .containsEntry("server.servlet.session.cookie.secure", "true")
                .containsEntry("server.servlet.session.cookie.path", "/")
                .containsEntry("server.servlet.session.cookie.same-site", "lax")
                .containsEntry("pacod.studio.host-cookies", "true");
    }
}
