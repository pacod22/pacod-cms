package io.pacod.cms.studio;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.site.StudioSites;
import io.pacod.cms.studio.testing.TestSubjects;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /sites/<id>/mgmt/site-files/**} through the real security chain and website filter: every
 * file's screen renders in Bulgarian and English, as text (a file is never markup on the studio's
 * page); whoever reads the website sees it, only {@code website.manage} saves; the multipart form
 * reaches the service with what it posted, a file upload replacing the text.
 */
@SpringBootTest
@ActiveProfiles("test")
class SiteFilesScreensTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TenantDirectory directory;
    @MockitoBean
    private StudioSites sites;
    @MockitoBean
    private WebsiteRepository websites;
    @MockitoBean
    private MgmtSiteFilesService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(directory.bySite(anyString())).thenReturn(Optional.empty());
        when(directory.bySite("alpha")).thenReturn(Optional.of(new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), "editorial")));
        when(sites.current()).thenAnswer(i -> io.pacod.cms.core.tenant.TenantContext.find().map(t ->
                new StudioSites.SiteView(t.siteId(), t.organisationId(), t.siteId(), WebsiteStatus.ACTIVE, "en",
                        List.of(t.siteId() + ".test"), "http://" + t.siteId() + ".test")));
        when(websites.findById("alpha")).thenReturn(Optional.of(new Website("alpha", "org_a", "Alpha")));

        SiteFiles files = new SiteFiles();
        files.getLlms().setMode(SiteFileMode.OFF);
        files.getRobots().setAdditions("Disallow: /drafts/");
        when(service.load()).thenReturn(files);
        when(service.origin()).thenReturn("http://alpha.test");
        when(service.ownHosts()).thenReturn(Set.of("alpha.test"));
        when(service.preview(any(), any())).thenReturn(new MgmtSiteFilesService.Preview(
                "User-agent: *\nDisallow: /search\n<script>alert(1)</script>\n", 3, 48, false, false, false));

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

    /** The sign-in page's CSRF cookie and token, as PortalScreensTest explains. */
    private String[] csrf() throws Exception {
        var page = mockMvc.perform(get("/mgmt/login")).andReturn();
        var token = java.util.regex.Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"").matcher(page.getResponse().getContentAsString());
        assertThat(token.find()).isTrue();
        return new String[] {page.getResponse().getCookie("XSRF-TOKEN").getValue(), token.group(1)};
    }

    private MockMultipartHttpServletRequestBuilder multipartCsrf(String path) throws Exception {
        String[] csrf = csrf();
        MockMultipartHttpServletRequestBuilder request = multipart(path);
        request.cookie(new Cookie("XSRF-TOKEN", csrf[0])).param("_csrf", csrf[1]);
        return request;
    }

    private String render(String path) throws Exception {
        String html = mockMvc.perform(get(path).with(oidcLogin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).as(path).doesNotContain("??").contains("lang=\"bg\"");
        String english = mockMvc.perform(get(path).with(oidcLogin()).cookie(new Cookie("mgmt-lang", "en")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(english).contains("lang=\"en\"").doesNotContain("??");
        Properties bg = MgmtText.load("i18n/ops_bg.properties");
        Properties en = MgmtText.load("i18n/ops.properties");
        for (String key : bg.stringPropertyNames()) {
            String value = bg.getProperty(key);
            if (key.startsWith("siteFiles.") && value.length() > 8 && !value.contains("{") && !value.equals(en.getProperty(key))) {
                assertThat(english).as("Bulgarian %s on the English %s", key, path).doesNotContain(value);
            }
        }
        return english;
    }

    @Test
    @DisplayName("every file's screen renders for a website admin, in both languages, the preview as text")
    void screensRender() throws Exception {
        TestSubjects.use(at("cms.website_admin"));
        for (SiteFileKind kind : SiteFileKind.values()) {
            String html = render("/sites/alpha/mgmt/site-files/" + kind.param());
            assertThat(html).as(kind.param())
                    .contains("action=\"/sites/alpha/mgmt/site-files/" + kind.param() + "\"", "enctype=\"multipart/form-data\"",
                            "href=\"http://alpha.test" + kind.path() + "\"", "&lt;script&gt;alert(1)&lt;/script&gt;")
                    .doesNotContain("<script>alert(1)</script>");
            // llms-full.txt has no custom text: neither the mode nor the card nor the copy button.
            assertThat(html.contains("value=\"CUSTOM\"")).as(kind.param()).isEqualTo(kind.customisable());
            assertThat(html.contains("/customise\"")).as(kind.param()).isEqualTo(kind.customisable());
        }
        assertThat(render("/sites/alpha/mgmt/site-files/robots")).contains("Disallow: /drafts/", "name=\"blockAiTraining\"",
                "name=\"options\"");
        assertThat(render("/sites/alpha/mgmt/site-files/sitemap")).doesNotContain("name=\"options\"");
    }

    @Test
    @DisplayName("the bare address goes to robots.txt, an unknown file is a 404")
    void addresses() throws Exception {
        TestSubjects.use(at("cms.website_admin"));
        mockMvc.perform(get("/sites/alpha/mgmt/site-files").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/site-files/robots"));
        mockMvc.perform(get("/sites/alpha/mgmt/site-files/humans").with(oidcLogin())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a role that reads the website sees the files with nothing to change; it cannot save or customise")
    void readersCannotChange() throws Exception {
        for (String role : List.of("cms.author", "cms.analyst", "cms.designer")) {
            TestSubjects.use(at(role));
            String html = mockMvc.perform(get("/sites/alpha/mgmt/site-files/robots").with(oidcLogin()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).as(role).contains("User-agent: *").doesNotContain("name=\"options\"", "/customise\"",
                    "name=\"customFile\"");
            mockMvc.perform(multipartCsrf("/sites/alpha/mgmt/site-files/robots").param("mode", "OFF").with(oidcLogin()))
                    .andExpect(status().isForbidden());
            String[] csrf = csrf();
            mockMvc.perform(post("/sites/alpha/mgmt/site-files/robots/customise").cookie(new Cookie("XSRF-TOKEN", csrf[0]))
                    .param("_csrf", csrf[1]).with(oidcLogin())).andExpect(status().isForbidden());
        }
        verify(service, never()).save(any(), any());
        verify(service, never()).customise(any());
    }

    @Test
    @DisplayName("a save posts the form's values, options only when carried; an uploaded file replaces the text")
    void savePostsTheForm() throws Exception {
        TestSubjects.use(at("cms.website_admin"));
        mockMvc.perform(multipartCsrf("/sites/alpha/mgmt/site-files/robots").param("mode", "AUTO_PLUS")
                        .param("additions", "Disallow: /x/").param("options", "1").param("blockAiTraining", "true").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/site-files/robots"));
        ArgumentCaptor<MgmtSiteFilesService.Change> change = ArgumentCaptor.forClass(MgmtSiteFilesService.Change.class);
        verify(service).save(eq(SiteFileKind.ROBOTS), change.capture());
        assertThat(change.getValue().mode()).isEqualTo(SiteFileMode.AUTO_PLUS);
        assertThat(change.getValue().additions()).isEqualTo("Disallow: /x/");
        assertThat(change.getValue().withOptions()).isTrue();
        assertThat(change.getValue().blockAiTraining()).isTrue();
        assertThat(change.getValue().blockAiAnswering()).isFalse();

        MockMultipartFile upload = new MockMultipartFile("customFile", "llms.txt", "text/plain",
                "# Alpha\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipartCsrf("/sites/alpha/mgmt/site-files/llms").file(upload).param("mode", "CUSTOM")
                        .param("custom", "typed").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/site-files/llms"));
        verify(service).save(eq(SiteFileKind.LLMS), change.capture());
        assertThat(change.getValue().custom()).isEqualTo("# Alpha\n");
        assertThat(change.getValue().withOptions()).isFalse();
    }

    @Test
    @DisplayName("a refused save comes back to the file's screen with the refusal")
    void refusalIsFlashed() throws Exception {
        TestSubjects.use(at("cms.website_admin"));
        org.mockito.Mockito.doThrow(new MgmtOperationException("siteFiles.error.customEmpty"))
                .when(service).save(eq(SiteFileKind.ROBOTS), any());
        var result = mockMvc.perform(multipartCsrf("/sites/alpha/mgmt/site-files/robots").param("mode", "CUSTOM").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/site-files/robots")).andReturn();
        assertThat(result.getFlashMap().get("error")).isNotNull();
    }
}
