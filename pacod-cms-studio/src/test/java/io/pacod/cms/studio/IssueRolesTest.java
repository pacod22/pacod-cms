package io.pacod.cms.studio;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.site.StudioSites;
import io.pacod.cms.studio.testing.TestSubjects;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The issue screens and the PDF settings through the real website filter and security chain,
 * with people holding real-shaped roles: an author writes an issue but neither publishes nor
 * deletes it, nor changes the PDF defaults it may read; an editor publishes.
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueRolesTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TenantDirectory directory;
    @MockitoBean
    private StudioSites sites;
    @MockitoBean
    private WebsiteRepository websites;
    @MockitoBean
    private MgmtLanguageService languages;
    @MockitoBean
    private MgmtIssueService issues;
    @MockitoBean
    private PdfSettingsRepository pdfSettings;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(directory.bySite(anyString())).thenReturn(Optional.empty());
        when(directory.bySite("alpha")).thenReturn(Optional.of(new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), "editorial")));
        when(sites.current()).thenAnswer(i -> io.pacod.cms.core.tenant.TenantContext.find().map(t ->
                new StudioSites.SiteView(t.siteId(), t.organisationId(), t.siteId(), WebsiteStatus.ACTIVE, "en",
                        List.of(t.siteId() + ".test"), "http://" + t.siteId() + ".test")));
        when(websites.findById("alpha")).thenReturn(Optional.of(new Website("alpha", "org_a", "Alpha")));
        Language en = new Language();
        en.setCode("en");
        en.setAdminName("English");
        when(languages.findAll()).thenReturn(List.of(en));
        JournalIssue issue = new JournalIssue();
        issue.setId(3);
        issue.setVolume(1);
        issue.setNumber(1);
        issue.setYear(2026);
        issue.setMonth(10);
        when(issues.find(3)).thenReturn(issue);
        when(issues.list(anyInt())).thenReturn(new PageImpl<>(List.of(issue)));

        Filter siteFilter = context.getBean("studioSiteFilter", FilterRegistrationBean.class).getFilter();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(siteFilter).apply(springSecurity()).build();
    }

    @AfterEach
    void reset() {
        TestSubjects.reset();
    }

    private static Subject at(String role) {
        return Subject.user(List.of(new Binding(role, List.of("platform", "organisation:org_a", "cms.website:alpha"))));
    }

    /** A POST with a real CSRF token, as PortalScreensTest explains. */
    private MockHttpServletRequestBuilder postCsrf(String path) throws Exception {
        var page = mockMvc.perform(get("/mgmt/login")).andReturn();
        var token = java.util.regex.Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"").matcher(page.getResponse().getContentAsString());
        assertThat(token.find()).isTrue();
        return post(path).cookie(page.getResponse().getCookie("XSRF-TOKEN")).param("_csrf", token.group(1));
    }

    @Test
    @DisplayName("an author edits an issue, but publishing, deleting and the PDF defaults are refused")
    void author() throws Exception {
        TestSubjects.use(at("cms.author"));
        String editor = mockMvc.perform(get("/sites/alpha/mgmt/issues/3").with(oidcLogin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(editor).contains("action=\"/sites/alpha/mgmt/issues/3\"")
                .doesNotContain("/issues/3/publish").doesNotContain("/issues/3/delete");

        mockMvc.perform(postCsrf("/sites/alpha/mgmt/issues/3/fill-month").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/issues/3"));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/issues/3/publish").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/issues/3/delete").with(oidcLogin())).andExpect(status().isForbidden());
        verify(issues, never()).publish(anyInt());
        verify(issues, never()).delete(anyInt());

        String settings = mockMvc.perform(get("/sites/alpha/mgmt/pdf").with(oidcLogin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(settings).contains("name=\"defaultFormat\" disabled=\"disabled\"");
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/pdf").param("marginMm", "15").with(oidcLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an editor publishes; an analyst does not even read the issues")
    void editorAndAnalyst() throws Exception {
        TestSubjects.use(at("cms.editor"));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/issues/3/publish").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/issues/3"));
        verify(issues).publish(3);

        TestSubjects.use(at("cms.analyst"));
        mockMvc.perform(get("/sites/alpha/mgmt/issues").with(oidcLogin())).andExpect(status().isForbidden());
    }
}
