package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.site.services.SiteChromeReader;
import io.pacod.cms.site.services.SiteFilesReader;
import io.pacod.cms.site.testing.TestSites;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What each mode answers, with the generation mocked: core's builders have their own tests, and
 * {@code PublicArticlesIT} runs the whole chain against MongoDB.
 */
class SiteFilesControllerTest {

    private SiteFilesReader reader;
    private SiteFiles files;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reader = mock(SiteFilesReader.class);
        SiteChromeReader chrome = mock(SiteChromeReader.class);
        when(chrome.settings()).thenReturn(TestSites.settings());
        files = new SiteFiles();
        when(reader.settings()).thenReturn(files);
        when(reader.generated(any(), any(), anyString(), any()))
                .thenAnswer(call -> "generated " + call.<SiteFileKind>getArgument(0).name());
        mockMvc = MockMvcBuilders.standaloneSetup(new SiteFilesController(reader, chrome)).build();
    }

    @Test
    void automaticIsGeneratedWithItsTypeAndNosniff() throws Exception {
        mockMvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(content().string("generated ROBOTS"));
        mockMvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/xml;charset=UTF-8"));
        mockMvc.perform(get("/llms.txt")).andExpect(content().string("generated LLMS"));
        mockMvc.perform(get("/llms-full.txt")).andExpect(content().string("generated LLMS_FULL"));
    }

    @Test
    void customIsServedVerbatimAndEmptyCustomIsNotFound() throws Exception {
        files.getRobots().setMode(SiteFileMode.CUSTOM);
        files.getRobots().setCustom("User-agent: *\nDisallow:\n");
        mockMvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string("User-agent: *\nDisallow:\n"));
        verify(reader, never()).generated(eq(SiteFileKind.ROBOTS), any(), anyString(), any());

        files.getRobots().setCustom("  ");
        mockMvc.perform(get("/robots.txt")).andExpect(status().isNotFound());
    }

    @Test
    void offIsNotFound() throws Exception {
        files.getLlms().setMode(SiteFileMode.OFF);
        mockMvc.perform(get("/llms.txt")).andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void llmsFullIsNeverCustom() throws Exception {
        files.getLlmsFull().setMode(SiteFileMode.CUSTOM);
        files.getLlmsFull().setCustom("hand written");
        mockMvc.perform(get("/llms-full.txt")).andExpect(content().string("generated LLMS_FULL"));
    }

    @Test
    void anUnreadableDatabaseIsA503NeverAGeneratedFile() throws Exception {
        when(reader.settings()).thenThrow(new DataAccessResourceFailureException("down"));
        for (String path : SiteFilesController.PATHS) {
            mockMvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "600"));
        }
        verify(reader, never()).generated(any(), any(), anyString(), any());
    }
}
