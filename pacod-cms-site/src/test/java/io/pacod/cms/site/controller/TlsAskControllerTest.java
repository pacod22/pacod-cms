package io.pacod.cms.site.controller;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZoneId;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TlsAskControllerTest {

    private TenantDirectory directory;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        directory = mock(TenantDirectory.class);
        when(directory.byHost(anyString())).thenReturn(Optional.empty());
        when(directory.byHost("news.example.com"))
                .thenReturn(Optional.of(new Tenant("news", "org_n", "site_news", "en", ZoneId.of("UTC"))));
        mvc = MockMvcBuilders.standaloneSetup(new TlsAskController(directory)).build();
    }

    @Test
    @DisplayName("a host of an active website may have a certificate; any other host may not")
    void answers() throws Exception {
        mvc.perform(get("/_internal/tls-ask").param("domain", "news.example.com"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/_internal/tls-ask").param("domain", "scanner.example.net")).andExpect(status().isNotFound());
        mvc.perform(get("/_internal/tls-ask")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an over-long name is refused without a lookup")
    void overLongIsRefused() throws Exception {
        mvc.perform(get("/_internal/tls-ask").param("domain", "a".repeat(254))).andExpect(status().isNotFound());
        verify(directory, never()).byHost("a".repeat(254));
    }
}
