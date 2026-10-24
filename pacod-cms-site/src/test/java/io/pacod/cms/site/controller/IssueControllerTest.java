package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.IssueRendition;
import io.pacod.cms.core.data.IssueStatus;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.site.render.SitePrint;
import io.pacod.cms.site.services.BannerService;
import io.pacod.cms.site.services.IssueReader;
import io.pacod.cms.site.services.SiteMetricCollector;
import io.pacod.cms.site.services.SitePdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The issue routes' contract, standalone like ArticleTemplateControllerTest. */
class IssueControllerTest {

    private final IssueReader reader = mock(IssueReader.class);
    private final SiteMetricCollector metrics = mock(SiteMetricCollector.class);
    private MockMvc mockMvc;
    private JournalIssue issue;

    @BeforeEach
    void setUp() {
        issue = new JournalIssue();
        issue.setId(3);
        issue.setStatus(IssueStatus.PUBLISHED);
        issue.setArticleIds(List.of(12));
        issue.setRenditions(Map.of("bg", new IssueRendition()));
        when(reader.view(any(), anyString())).thenAnswer(i -> new IssueReader.IssueView(i.getArgument(0), null, null,
                null, null, "Октомври", null));
        when(reader.published(anyInt())).thenReturn(new PageImpl<>(List.of(issue)));
        when(reader.published((Integer) 3)).thenReturn(Optional.of(issue));
        when(reader.published((Integer) 4)).thenReturn(Optional.empty());
        when(reader.anyForPrint(3)).thenReturn(Optional.of(issue));
        InternalResourceViewResolver views = new InternalResourceViewResolver("/WEB-INF/", ".html");
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new IssueController(reader, new SitePdfs(""), mock(BannerService.class), metrics),
                        new IssuePrintController(reader))
                .setViewResolvers(views).build();
    }

    @Test
    @DisplayName("/issues and /issues/{id} render published issues, counted as a listing; a draft or unknown id is a 404")
    void publicRoutes() throws Exception {
        mockMvc.perform(get("/issues")).andExpect(status().isOk()).andExpect(view().name("issues"))
                .andExpect(model().attributeExists("views", "issues", "lang", "topBanners"));
        mockMvc.perform(get("/issues/3").param("lang", "en")).andExpect(status().isOk()).andExpect(view().name("issue"))
                .andExpect(model().attribute("lang", "en")).andExpect(model().attributeExists("view", "entries", "pdfs"));
        mockMvc.perform(get("/issues/4")).andExpect(status().isNotFound());
        verify(metrics, org.mockito.Mockito.times(2)).recordPageView(eq(BannerPage.LIST), any());
        verify(reader).pageContents(anyList(), eq("en"));
        verify(reader, never()).contents(anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("the print front answers only behind a print token, with number lists of one length")
    void printFront() throws Exception {
        mockMvc.perform(get("/issue-print/3/front").param("ids", "12").param("pages", "3"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/issue-print/3/front").param("ids", "12").param("pages", "3")
                        .requestAttr(SitePrint.ATTRIBUTE, true))
                .andExpect(status().isOk()).andExpect(view().name("issue-print-front"));
        mockMvc.perform(get("/issue-print/3/front").param("ids", "12,13").param("pages", "3")
                .requestAttr(SitePrint.ATTRIBUTE, true)).andExpect(status().isNotFound());
        mockMvc.perform(get("/issue-print/3/front").param("ids", "12;x").param("pages", "3")
                .requestAttr(SitePrint.ATTRIBUTE, true)).andExpect(status().isNotFound());
        verify(reader).contents(eq(List.of(12)), eq(List.of(3)), anyString());
        verify(reader, never()).contents(eq(List.of(12, 13)), anyList(), anyString());
    }
}
