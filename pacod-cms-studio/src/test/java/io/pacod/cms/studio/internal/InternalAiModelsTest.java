package io.pacod.cms.studio.internal;

import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.studio.access.ServiceTokenCheck;
import io.pacod.cms.studio.ai.AiCatalog;
import io.pacod.cms.studio.ai.AiCatalogTest;
import io.pacod.cms.studio.ai.AiFeature;
import io.pacod.cms.studio.ai.AiGatewayConfig;
import io.pacod.cms.studio.ai.AiModels;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /internal/v1/ai-models} through the real chains. */
@SpringBootTest
@ActiveProfiles("test")
class InternalAiModelsTest {

    private static final String PATH = InternalAiModelsController.PATH;
    private static final String TOKEN = "pidt_tok1_secretsecretsecret";

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private ServiceTokenCheck tokens;
    @MockitoBean
    private AiModels models;
    @MockitoBean
    private AiCatalog catalog;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(tokens.subjectOf(anyString())).thenReturn(Optional.empty());
        when(tokens.subjectOf(TOKEN)).thenReturn(Optional.of("usr_script"));
        when(models.stored()).thenReturn(new AiModels.Stored(null, Map.of(AiFeature.VISION, "openai/gpt-dev"), null, null));
        when(models.defaultModel()).thenReturn("@fast");
        when(models.resolve(any())).thenReturn(new AiModels.Resolved("@fast", AiModels.Source.ENVIRONMENT));
        when(models.resolve(AiFeature.VISION)).thenReturn(new AiModels.Resolved("openai/gpt-dev", AiModels.Source.STORED_FEATURE));
        when(catalog.lookup()).thenReturn(new AiCatalog.Lookup(AiCatalog.parse(AiCatalogTest.fixture()), null));
        TestSubjects.use(TestSubjects.platformAdmin());
    }

    @AfterEach
    void tearDown() {
        TestSubjects.reset();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    @Test
    @DisplayName("without a token, or without platform.manage, nothing is shown or changed")
    void refused() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        TestSubjects.use(Subject.user(List.of(new Binding("cms.org_admin", List.of("platform", "organisation:org_a")))));
        mockMvc.perform(authed(put(PATH)).contentType(MediaType.APPLICATION_JSON).content("{\"default\":\"@fast\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/sites/demo" + PATH).contextPath("/sites/demo").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
        verify(models, never()).set(any(), any());
    }

    @Test
    @DisplayName("GET shows each feature's model and where it comes from, with the gateway's list and what each entry suits")
    void show() throws Exception {
        mockMvc.perform(authed(get(PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default.model").value("@fast"))
                .andExpect(jsonPath("$.default.source").value("environment"))
                .andExpect(jsonPath("$.features.vision.model").value("openai/gpt-dev"))
                .andExpect(jsonPath("$.features.vision.source").value("stored_feature"))
                .andExpect(jsonPath("$.features.vision.override").value("openai/gpt-dev"))
                .andExpect(jsonPath("$.features.translate.override").doesNotExist())
                .andExpect(jsonPath("$.catalog.templates[0].id").value("@fast"))
                .andExpect(jsonPath("$.catalog.models[?(@.id == 'local/text-only')].suits[*]")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("vision"))))
                .andExpect(jsonPath("$.catalogError").doesNotExist());
    }

    @Test
    @DisplayName("PUT changes the named features as the account; null clears an override")
    void set() throws Exception {
        mockMvc.perform(authed(put(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default\":\"openai/gpt-dev\",\"features\":{\"vision\":\"anthropic/claude-dev\",\"pdf\":null}}"))
                .andExpect(status().isOk());
        verify(models).set(argThat(c -> "openai/gpt-dev".equals(c.defaultModel())
                && "anthropic/claude-dev".equals(c.features().get(AiFeature.VISION))
                && "".equals(c.features().get(AiFeature.PDF))
                && !c.features().containsKey(AiFeature.TRANSLATE)), org.mockito.ArgumentMatchers.eq("usr_script"));
    }

    @Test
    @DisplayName("an unknown feature, a wrong type and a refused model are 422s naming the code and field")
    void unprocessable() throws Exception {
        mockMvc.perform(authed(put(PATH)).contentType(MediaType.APPLICATION_JSON).content("{\"features\":{\"chat\":\"@fast\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown_feature"));
        mockMvc.perform(authed(put(PATH)).contentType(MediaType.APPLICATION_JSON).content("{\"default\":5}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("default"));
        when(models.set(argThat(c -> "local/text-only".equals(c.defaultModel())), any()))
                .thenThrow(new AiGatewayConfig.Refused("not_suitable", "vision"));
        mockMvc.perform(authed(put(PATH)).contentType(MediaType.APPLICATION_JSON).content("{\"default\":\"local/text-only\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("not_suitable"))
                .andExpect(jsonPath("$.field").value("vision"));
    }

    @Test
    @DisplayName("DELETE goes back to the properties")
    void clear() throws Exception {
        mockMvc.perform(authed(delete(PATH))).andExpect(status().isNoContent());
        verify(models).clear();
    }
}
