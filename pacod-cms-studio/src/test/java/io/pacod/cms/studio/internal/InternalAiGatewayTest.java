package io.pacod.cms.studio.internal;

import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.studio.access.ServiceTokenCheck;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.AiGatewayConfig;
import io.pacod.cms.studio.testing.TestSubjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/v1/ai-gateway} through the real chains: the bearer token, the permission
 * ({@code platform.manage}, decided from the account's bindings as for a person), JSON refusals,
 * and the key never answered back.
 */
@SpringBootTest
@ActiveProfiles("test")
class InternalAiGatewayTest {

    private static final String PATH = InternalAiGatewayController.PATH;
    private static final String TOKEN = "pidt_tok1_secretsecretsecret";
    private static final String KEY = "pgw_cms_0123456789abcdefWXYZ";

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private ServiceTokenCheck tokens;
    @MockitoBean
    private AiGatewayConfig config;
    @MockitoBean
    private AiGateway gateway;
    @MockitoBean
    private io.pacod.cms.studio.ai.AiModels models;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(tokens.subjectOf(anyString())).thenReturn(Optional.empty());
        when(tokens.subjectOf(TOKEN)).thenReturn(Optional.of("usr_script"));
        when(config.effective()).thenReturn(stored("https://gw.example", KEY));
        when(models.defaultModel()).thenReturn("@fast");
        TestSubjects.use(TestSubjects.platformAdmin());
    }

    @AfterEach
    void tearDown() {
        TestSubjects.reset();
    }

    private static AiGatewayConfig.Effective stored(String url, String key) {
        return new AiGatewayConfig.Effective(url, key, AiGatewayConfig.Source.STORED,
                Instant.parse("2026-10-31T10:00:00Z"), "usr_script");
    }

    @Test
    @DisplayName("no token or a token that is not an active service token: a JSON 401, no redirect to sign-in")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        mockMvc.perform(get(PATH).header("Authorization", "Bearer pidt_unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://evil.example\",\"apiKey\":\"k\"}"))
                .andExpect(status().isUnauthorized());
        verify(config, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("under a website's prefix the API does not exist, token or not: Caddy's /internal/* rule would not cover it")
    void noWebsitePrefix() throws Exception {
        mockMvc.perform(get("/sites/demo" + PATH).contextPath("/sites/demo").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mockMvc.perform(delete("/sites/demo" + PATH).contextPath("/sites/demo").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
        verify(config, never()).clear();
    }

    @Test
    @DisplayName("pacod-id unavailable: a 503 to try again, never a refusal")
    void unavailable() throws Exception {
        when(tokens.subjectOf(TOKEN)).thenThrow(new ServiceTokenCheck.Unavailable("feed old"));
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("identity_unavailable"));
    }

    @Test
    @DisplayName("an account without platform.manage is refused in JSON")
    void forbidden() throws Exception {
        TestSubjects.use(Subject.user(List.of(new Binding("cms.org_admin", List.of("platform", "organisation:org_a")))));
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://evil.example\",\"apiKey\":\"k\"}"))
                .andExpect(status().isForbidden());
        verify(config, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("GET shows the address and where it comes from, and only the key's last characters")
    void show() throws Exception {
        String body = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.url").value("https://gw.example"))
                .andExpect(jsonPath("$.source").value("stored"))
                .andExpect(jsonPath("$.keySet").value(true))
                .andExpect(jsonPath("$.keyHint").value("…WXYZ"))
                .andExpect(jsonPath("$.configured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(KEY);
    }

    @Test
    @DisplayName("PUT stores the pair as the account, tests it when asked, and never answers the key")
    void set() throws Exception {
        String newKey = "pgw_new_key_0000000000ABCD";
        when(config.set("https://gw2.example", newKey, "usr_script")).thenReturn(stored("https://gw2.example", newKey));
        when(gateway.ping(anyString())).thenReturn(new AiGateway.Answer(null, 0, "req_7"));
        String body = mockMvc.perform(put(PATH + "?test=true").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://gw2.example\",\"apiKey\":\"" + newKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://gw2.example"))
                .andExpect(jsonPath("$.keyHint").value("…ABCD"))
                .andExpect(jsonPath("$.test.ok").value(true))
                .andExpect(jsonPath("$.test.requestId").value("req_7"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(newKey);
        verify(config).set("https://gw2.example", newKey, "usr_script");
    }

    @Test
    @DisplayName("a refused change is a 422 naming the code and the field")
    void refused() throws Exception {
        when(config.set(eq("https://elsewhere.example"), isNull(), any()))
                .thenThrow(new AiGatewayConfig.Refused("key_required_with_url", "apiKey"));
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://elsewhere.example\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("key_required_with_url"))
                .andExpect(jsonPath("$.field").value("apiKey"));
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    @DisplayName("DELETE goes back to the environment's pair")
    void clear() throws Exception {
        mockMvc.perform(delete(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNoContent());
        verify(config).clear();
    }
}
