package io.pacod.cms.studio.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TokenExchangeTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private TokenExchange exchange;
    private IdAdmin admin;
    private MockRestServiceServer adminServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pacod-id:8071")
                .defaultHeader("Authorization", IdClient.basic("pacod-cms-service", "secret"));
        server = MockRestServiceServer.bindTo(builder).build();
        exchange = new TokenExchange(builder.build(), json);

        RestClient.Builder adminBuilder = RestClient.builder().baseUrl("http://pacod-id:8071/admin/v1");
        adminServer = MockRestServiceServer.bindTo(adminBuilder).build();
        admin = new IdAdmin(adminBuilder.build(), json);
    }

    @Test
    @DisplayName("an exchange is RFC 8693 as the service client, for audience pacod-id, and is kept for a while")
    void exchangesOnceAndKeeps() {
        server.expect(once(), requestTo("http://pacod-id:8071/oidc/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", IdClient.basic("pacod-cms-service", "secret")))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                        "subject_token", "pidt_abc_def",
                        "subject_token_type", "urn:ietf:params:oauth:token-type:access_token",
                        "audience", "pacod-id")))
                .andRespond(withSuccess("{\"access_token\":\"eyJ.admin\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(exchange.forAdmin("pidt_abc_def")).isEqualTo("eyJ.admin");
        assertThat(exchange.forAdmin("pidt_abc_def")).isEqualTo("eyJ.admin");
        server.verify();
    }

    @Test
    @DisplayName("a refused exchange carries OAuth's error code")
    void refusalKeepsItsCode() {
        server.expect(requestTo("http://pacod-id:8071/oidc/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"subject not taken\"}"));
        assertThatThrownBy(() -> exchange.forAdmin("stale"))
                .isInstanceOf(IdClient.IdException.class)
                .extracting(e -> ((IdClient.IdException) e).code()).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("the provisioner binds org_admin at the new organisation, and nothing else")
    void provisionerBindsOrgAdmin() {
        server.expect(requestTo("http://pacod-id:8071/oidc/token"))
                .andRespond(withSuccess("{\"access_token\":\"eyJ.svc\"}", MediaType.APPLICATION_JSON));
        adminServer.expect(requestTo("http://pacod-id:8071/admin/v1/bindings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer eyJ.svc"))
                .andExpect(content().json("{\"principal\":\"usr_1\",\"role\":\"cms.org_admin\",\"scope\":\"organisation:org_x\"}"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"rb_1\",\"seq\":77}"));

        long seq = new Provisioner("pidt_abc_def", exchange, admin).makeOrgAdmin("usr_1", "org_x");
        assertThat(seq).isEqualTo(77);
        adminServer.verify();
    }

    @Test
    @DisplayName("holding the role already is not an error")
    void existingBindingIsFine() {
        server.expect(requestTo("http://pacod-id:8071/oidc/token"))
                .andRespond(withSuccess("{\"access_token\":\"eyJ.svc\"}", MediaType.APPLICATION_JSON));
        adminServer.expect(requestTo("http://pacod-id:8071/admin/v1/bindings"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"conflict\",\"message\":\"exists\",\"details\":{\"code\":\"binding_exists\"}}}"));
        assertThat(new Provisioner("pidt_abc_def", exchange, admin).makeOrgAdmin("usr_1", "org_x")).isEqualTo(-1);
    }
}
