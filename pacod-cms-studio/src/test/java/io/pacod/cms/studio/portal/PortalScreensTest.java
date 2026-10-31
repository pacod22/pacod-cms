package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.PlatformSettings;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The portal's screens through the real security chain and website filter, with people holding
 * real-shaped roles: who reaches which screen, who is refused, and that each screen renders -- in
 * the test profile's Bulgarian and again in English, with no key left unresolved and no Bulgarian
 * interface text on the English page.
 *
 * <p>The portal's services are mocked; what is under test is the routing, the permission
 * annotations, the controllers' models and the templates.
 */
@SpringBootTest
@ActiveProfiles("test")
class PortalScreensTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TenantDirectory directory;
    @MockitoBean
    private StudioSites sites;
    @MockitoBean
    private PortalOrganisations organisations;
    @MockitoBean
    private PortalWebsites websites;
    @MockitoBean
    private PortalMembers members;
    @MockitoBean
    private PortalSettings settings;
    @MockitoBean
    private PortalIdentity identity;
    @MockitoBean
    private io.pacod.cms.studio.ai.AiGateway gateway;
    @MockitoBean
    private io.pacod.cms.studio.ai.AiGatewayConfig gatewayConfig;
    @MockitoBean
    private io.pacod.cms.studio.ai.AiModels aiModels;
    @MockitoBean
    private io.pacod.cms.studio.ai.AiCatalog aiCatalog;
    @MockitoBean
    private io.pacod.cms.core.archive.SiteArchives archives;
    @MockitoBean
    private io.pacod.cms.studio.archive.PortalRestore restore;

    private MockMvc mockMvc;

    private static final Organisation ALPHA = org("org_alpha", "Alpha Media");
    private static final Organisation BETA = org("org_beta", "Beta Press");

    private static final String SECRET_KEY = "pgw_cms_0123456789abcdefWXYZ";

    private static io.pacod.cms.studio.ai.AiGatewayConfig.Effective gatewayOf(
            String url, String key, io.pacod.cms.studio.ai.AiGatewayConfig.Source source) {
        return new io.pacod.cms.studio.ai.AiGatewayConfig.Effective(url, key, source, null, null);
    }

    @BeforeEach
    void setUp() {
        when(gatewayConfig.effective()).thenReturn(gatewayOf("", "", io.pacod.cms.studio.ai.AiGatewayConfig.Source.NONE));
        when(aiModels.stored()).thenReturn(new io.pacod.cms.studio.ai.AiModels.Stored(null, java.util.Map.of(), null, null));
        when(aiModels.defaultModel()).thenReturn("@fast");
        when(aiModels.environmentDefault()).thenReturn("@fast");
        when(aiModels.resolve(any())).thenReturn(new io.pacod.cms.studio.ai.AiModels.Resolved("@fast",
                io.pacod.cms.studio.ai.AiModels.Source.ENVIRONMENT));
        when(aiCatalog.lookup()).thenReturn(new io.pacod.cms.studio.ai.AiCatalog.Lookup(null, null));
        when(directory.bySite(anyString())).thenReturn(Optional.empty());
        when(directory.bySite("alpha")).thenReturn(Optional.of(new Tenant("alpha", "org_alpha", "site_alpha", "en", ZoneId.of("UTC"))));
        when(directory.bySite("beta")).thenReturn(Optional.of(new Tenant("beta", "org_beta", "site_beta", "en", ZoneId.of("UTC"))));
        when(sites.all()).thenReturn(List.of(view("alpha", "org_alpha"), view("beta", "org_beta")));
        when(sites.current()).thenAnswer(i -> io.pacod.cms.core.tenant.TenantContext.find()
                .map(t -> view(t.siteId(), t.organisationId())));

        PortalOrganisations.OrgView alpha = new PortalOrganisations.OrgView(ALPHA, 1);
        PortalOrganisations.OrgView beta = new PortalOrganisations.OrgView(BETA, 1);
        when(organisations.all()).thenReturn(List.of(alpha, beta));
        when(organisations.visible()).thenReturn(List.of(alpha));
        when(organisations.creatable()).thenReturn(List.of(alpha));
        when(organisations.allowance()).thenReturn(new PortalOrganisations.Allowance(null, new Object[0]));
        when(organisations.require("org_alpha")).thenReturn(ALPHA);
        when(organisations.require("org_beta")).thenReturn(BETA);
        when(organisations.view(ALPHA)).thenReturn(alpha);
        when(organisations.view(BETA)).thenReturn(beta);

        Website site = new Website("alpha", "org_alpha", "Alpha News");
        site.setStatus(WebsiteStatus.ACTIVE);
        when(websites.require("alpha")).thenReturn(site);
        when(websites.domainsOf("alpha")).thenReturn(List.of(new Domain("alpha.example.com", "alpha", true),
                new Domain("news.alpha.example", "alpha", false)));
        when(websites.siteDomain()).thenReturn("localhost");

        when(settings.current()).thenReturn(new PlatformSettings());
        when(identity.enabled()).thenReturn(true);
        when(identity.canProvision()).thenReturn(true);

        when(members.available()).thenReturn(true);
        when(members.canManage(any())).thenReturn(true);
        when(members.grantable(any())).thenReturn(List.of(new PortalMembers.RoleOption("cms.editor", "Editor")));
        when(members.roleLabel(anyString())).thenAnswer(i -> i.getArgument(0));
        when(members.members(any())).thenReturn(List.of(
                new PortalMembers.Member("usr_1", "user", "alex@example.com", "Alex Morgan", "active",
                        List.of(new PortalMembers.MemberRole("rb_1", "cms.org_admin", "organisation:org_alpha", false))),
                new PortalMembers.Member("usr_2", "user", "sam@example.com", null, "active",
                        List.of(new PortalMembers.MemberRole("rb_2", "cms.editor", "organisation:org_alpha", true)))));
        when(members.invitations(any())).thenReturn(List.of(new PortalMembers.Invitation("inv_1", "kim@example.com",
                "cms.editor", "usr_1", "Alex Morgan", true, "2026-10-16", "2026-10-23", false)));

        Filter siteFilter = context.getBean("studioSiteFilter", FilterRegistrationBean.class).getFilter();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(siteFilter).apply(springSecurity()).build();
    }

    @AfterEach
    void reset() {
        TestSubjects.reset();
    }

    private static Organisation org(String id, String name) {
        return new Organisation(id, name);
    }

    private static StudioSites.SiteView view(String id, String org) {
        return new StudioSites.SiteView(id, org, id, WebsiteStatus.ACTIVE, "en", List.of(id + ".test"), "http://" + id + ".test");
    }

    private static Subject orgAdminOfAlpha() {
        return Subject.user(List.of(new Binding("cms.org_admin", List.of("platform", "organisation:org_alpha"))));
    }

    private static Subject websiteAdminOfAlpha() {
        return Subject.user(List.of(new Binding("cms.website_admin",
                List.of("platform", "organisation:org_alpha", "cms.website:alpha"))));
    }

    /**
     * A POST carrying a real CSRF token, taken from the sign-in page as a browser would. Spring's
     * {@code csrf()} helper picks the filter chain by the raw path, before the website filter strips
     * {@code /sites/<id>}, so on a prefixed POST it fills the wrong repository and the real chain
     * refuses with 403 -- which a test expecting 403 would take for the permission check.
     */
    private MockHttpServletRequestBuilder postCsrf(String path) throws Exception {
        org.springframework.test.web.servlet.MvcResult page = mockMvc.perform(get("/mgmt/login")).andReturn();
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"")
                .matcher(page.getResponse().getContentAsString());
        assertThat(token.find()).as("the sign-in page carries a CSRF token").isTrue();
        Cookie cookie = page.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return post(path).cookie(cookie).param("_csrf", token.group(1));
    }

    private String render(MockHttpServletRequestBuilder request) throws Exception {
        String html = mockMvc.perform(request.with(oidcLogin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("??");
        return html;
    }

    /** The same screen in English: lang="en", every key resolved, no Bulgarian portal text. */
    private String renderEnglish(String path) throws Exception {
        String html = render(get(path).cookie(new Cookie("mgmt-lang", "en")));
        assertThat(html).contains("lang=\"en\"");
        Properties bulgarian = MgmtText.load("i18n/portal_bg.properties");
        Properties english = MgmtText.load("i18n/portal.properties");
        for (String key : bulgarian.stringPropertyNames()) {
            String value = bulgarian.getProperty(key);
            if (value.length() > 8 && !value.contains("{") && !value.equals(english.getProperty(key))) {
                assertThat(html).as("Bulgarian %s on the English %s", key, path).doesNotContain(value);
            }
        }
        return html;
    }

    // ------------------------------------------------------------------ who reaches what

    @Test
    @DisplayName("every portal screen asks an anonymous visitor to sign in")
    @WithAnonymousUser
    void anonymousIsSentToSignIn() throws Exception {
        for (String path : List.of("/mgmt/organisations", "/mgmt/organisations/org_alpha", "/mgmt/platform",
                "/mgmt/sites/new")) {
            mockMvc.perform(get(path)).andExpect(redirectedUrl("/mgmt/login"));
        }
        mockMvc.perform(get("/sites/alpha/mgmt/settings")).andExpect(redirectedUrl("/sites/alpha/mgmt/login"));
        mockMvc.perform(get("/sites/alpha/mgmt/members")).andExpect(redirectedUrl("/sites/alpha/mgmt/login"));
    }

    @Test
    @DisplayName("someone holding no role sees the lists and opens nothing else")
    void noRoleOpensNothing() throws Exception {
        TestSubjects.use(Subject.user(List.of()));
        mockMvc.perform(get("/mgmt/organisations").with(oidcLogin())).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt/organisations/org_alpha").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/mgmt/platform").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/sites/alpha/mgmt/settings").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/sites/alpha/mgmt/members").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(postCsrf("/mgmt/organisations/org_alpha/members/invite").with(oidcLogin())
                        .param("email", "x@example.com").param("role", "cms.editor"))
                .andExpect(status().isForbidden());
        verify(members, never()).invite(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("an organisation admin opens their organisation and its websites, and nothing of another")
    void orgAdminStaysInTheirOrganisation() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        mockMvc.perform(get("/mgmt/organisations/org_alpha").with(oidcLogin())).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt/organisations/org_beta").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(postCsrf("/mgmt/organisations/org_beta").with(oidcLogin()).param("name", "Mine now"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/sites/alpha/mgmt/settings").with(oidcLogin())).andExpect(status().isOk());
        mockMvc.perform(get("/sites/beta/mgmt/settings").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/mgmt/platform").with(oidcLogin())).andExpect(status().isForbidden());
        verify(organisations, never()).rename(eq("org_beta"), anyString());
    }

    @Test
    @DisplayName("a website admin edits the website's settings but cannot delete it")
    void websiteAdminCannotDelete() throws Exception {
        TestSubjects.use(websiteAdminOfAlpha());
        String html = render(get("/sites/alpha/mgmt/settings"));
        assertThat(html).doesNotContain("/sites/alpha/mgmt/settings/delete");
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/settings/delete").with(oidcLogin())
                        .param("confirmation", "alpha"))
                .andExpect(status().isForbidden());
        verify(websites, never()).delete(anyString(), anyString());
        mockMvc.perform(get("/sites/alpha/mgmt/members").with(oidcLogin())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an organisation admin deletes a website with its id typed, and lands on the organisation")
    void orgAdminDeletesAWebsite() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/settings/delete").with(oidcLogin())
                        .param("confirmation", "alpha"))
                .andExpect(redirectedUrl("/mgmt/organisations/org_alpha"));
        verify(websites).delete("alpha", "alpha");
    }

    @Test
    @DisplayName("a new website lands on its own dashboard, outside any other website's prefix")
    void newWebsiteRedirectsToItsDashboard() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        Website created = new Website("gamma", "org_alpha", "Gamma");
        when(websites.create(any())).thenReturn(created);
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/sites/new").with(oidcLogin())
                        .param("organisationId", "org_alpha").param("id", "gamma").param("name", "Gamma"))
                .andExpect(redirectedUrl("/sites/gamma/mgmt/dashboard"));
    }

    // ------------------------------------------------------------------ the screens render

    @Test
    @DisplayName("the organisations list and its create form render in both languages")
    void organisationsRender() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        assertThat(render(get("/mgmt/organisations"))).contains("Alpha Media", "/mgmt/organisations/org_alpha", "name=\"name\"");
        assertThat(renderEnglish("/mgmt/organisations")).contains("New organisation");
    }

    @Test
    @DisplayName("a refused creation says why instead of offering the form")
    void organisationsRefusalIsSaid() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        when(organisations.allowance()).thenReturn(new PortalOrganisations.Allowance("portal.org.refused.limit", new Object[]{3}));
        String html = renderEnglish("/mgmt/organisations");
        assertThat(html).contains("You have created 3 organisations").doesNotContain("name=\"name\"");
    }

    @Test
    @DisplayName("an organisation's screen shows websites, members, invitations and the invite form")
    void organisationRenders() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        String html = render(get("/mgmt/organisations/org_alpha"));
        assertThat(html).contains("Alpha Media", "/sites/alpha/mgmt/dashboard", "Alex Morgan", "sam@example.com",
                "kim@example.com", "/mgmt/organisations/org_alpha/members/invite",
                "/mgmt/organisations/org_alpha/members/invitations/revoke", "/mgmt/sites/new?org=org_alpha");
        assertThat(renderEnglish("/mgmt/organisations/org_alpha")).contains("(inherited)", "Open invitations");
    }

    @Test
    @DisplayName("forms posting to absolute studio addresses still carry their CSRF field")
    void absoluteActionsCarryCsrf() throws Exception {
        TestSubjects.use(TestSubjects.platformAdmin());
        // th:action built on ${studioRoot}, not @{}: the processor must still add the hidden field,
        // or every such form fails in a browser while the tests (which post the meta tag) pass.
        java.util.regex.Pattern form = java.util.regex.Pattern.compile(
                "<form[^>]*action=\"([^\"]+)\"[^>]*>\\s*<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"");
        for (String path : List.of("/mgmt/organisations", "/mgmt/organisations/org_alpha", "/mgmt/platform", "/mgmt/sites/new")) {
            String html = render(get(path));
            int withField = 0;
            java.util.regex.Matcher m = form.matcher(html);
            while (m.find()) {
                withField++;
            }
            long forms = html.split("<form", -1).length - 1;
            assertThat(withField).as("forms with a CSRF field on %s", path).isEqualTo((int) forms).isPositive();
        }
    }

    @Test
    @DisplayName("pacod-id unreachable costs the members card, not the screen")
    void membersFailureKeepsTheScreen() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        when(members.members(any())).thenThrow(new io.pacod.cms.studio.MgmtOperationException("portal.members.error.unreachable"));
        String html = renderEnglish("/mgmt/organisations/org_alpha");
        assertThat(html).contains("pacod-id cannot be reached right now", "/sites/alpha/mgmt/dashboard");
    }

    @Test
    @DisplayName("the new-website form offers the organisations the person may create in")
    void newWebsiteRenders() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        when(websites.demoLanguages()).thenReturn("English");
        when(websites.templateSets()).thenReturn(new io.pacod.cms.core.design.TemplateSetCatalog().all());
        String html = render(get("/mgmt/sites/new"));
        assertThat(html).contains("name=\"templateSet\"", "value=\"minimal\"")
                .containsPattern("<option value=\"editorial\" selected=\"selected\">");
        assertThat(html).contains("value=\"org_alpha\"", "&lt;id&gt;.localhost", "name=\"hosts\"", "name=\"demo\"")
                .doesNotContain("org_beta");
        assertThat(renderEnglish("/mgmt/sites/new")).contains("Start with demo content", "Written in English");
    }

    @Test
    @DisplayName("a new website asked with demo content gets it after it is created, and the flash says what was added")
    void newWebsiteWithDemoContent() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        Website created = new Website("gamma", "org_alpha", "Gamma");
        when(websites.create(any())).thenReturn(created);
        when(websites.addDemo(eq(created), any())).thenReturn(new io.pacod.cms.studio.demo.StudioDemo.Outcome(
                new io.pacod.cms.core.seed.DemoContent.Result(20, 24, 6, 4, 12, true, true), 2, null));

        mockMvc.perform(postCsrf("/mgmt/sites/new").with(oidcLogin())
                        .param("organisationId", "org_alpha").param("id", "gamma").param("name", "Gamma")
                        .param("language", "en").param("demo", "true").param("templateSet", "minimal"))
                .andExpect(redirectedUrl("/sites/gamma/mgmt/dashboard"))
                .andExpect(flash().attribute("message", org.hamcrest.Matchers.containsString("20")));

        ArgumentCaptor<PortalWebsites.NewSite> request = ArgumentCaptor.forClass(PortalWebsites.NewSite.class);
        verify(websites).create(request.capture());
        assertThat(request.getValue().demo()).isTrue();
        assertThat(request.getValue().templateSet()).isEqualTo("minimal");
        verify(websites).addDemo(eq(created), any());
    }

    @Test
    @DisplayName("a website created without demo content gets none; one whose demo fails still lands on its dashboard, saying so")
    void demoContentIsAskedFor() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        Website created = new Website("gamma", "org_alpha", "Gamma");
        when(websites.create(any())).thenReturn(created);
        mockMvc.perform(postCsrf("/mgmt/sites/new").with(oidcLogin())
                        .param("organisationId", "org_alpha").param("id", "gamma").param("name", "Gamma"))
                .andExpect(redirectedUrl("/sites/gamma/mgmt/dashboard"));
        verify(websites, never()).addDemo(any(), any());

        when(websites.addDemo(eq(created), any())).thenThrow(new IllegalStateException("disk full"));
        mockMvc.perform(postCsrf("/mgmt/sites/new").with(oidcLogin())
                        .param("organisationId", "org_alpha").param("id", "gamma").param("name", "Gamma")
                        .param("demo", "true"))
                .andExpect(redirectedUrl("/sites/gamma/mgmt/dashboard"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("disk full")));
    }

    @Test
    @DisplayName("a website's settings show its addresses, the primary first, and the delete card for an organisation admin")
    void settingsRender() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        String html = render(get("/sites/alpha/mgmt/settings"));
        assertThat(html).contains("alpha.example.com", "news.alpha.example", "/sites/alpha/mgmt/settings/domains",
                "/sites/alpha/mgmt/settings/delete", "site_alpha");
        assertThat(renderEnglish("/sites/alpha/mgmt/settings")).contains("Make primary", "Delete the website");
    }

    @Test
    @DisplayName("a website's members post inside the website")
    void siteMembersRender() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        String html = render(get("/sites/alpha/mgmt/members"));
        assertThat(html).contains("/sites/alpha/mgmt/members/invite", "/sites/alpha/mgmt/members/role",
                "/sites/alpha/mgmt/members/invitations/revoke");
        renderEnglish("/sites/alpha/mgmt/members");
    }

    @Test
    @DisplayName("the installation screen tests the AI gateway: off without one; with one, the answer or the refusal in the flash")
    void aiConnectionTest() throws Exception {
        TestSubjects.use(TestSubjects.platformAdmin());
        assertThat(render(get("/mgmt/platform"))).doesNotContain("/mgmt/platform/ai-test").contains("PACOD_GW_URL");

        when(gatewayConfig.effective()).thenReturn(gatewayOf("http://pacod-gw:8080", SECRET_KEY,
                io.pacod.cms.studio.ai.AiGatewayConfig.Source.ENVIRONMENT));
        assertThat(renderEnglish("/mgmt/platform")).contains("/mgmt/platform/ai-test", "Test the connection", "@fast");
        when(gateway.ping("@fast")).thenReturn(new io.pacod.cms.studio.ai.AiGateway.Answer(null, 0, "req_1"));
        mockMvc.perform(postCsrf("/mgmt/platform/ai-test").with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("message", org.hamcrest.Matchers.containsString("req_1")));
        when(gateway.ping("@fast")).thenThrow(new io.pacod.cms.studio.ai.AiGateway.AiException("The AI gateway refused the key"));
        mockMvc.perform(postCsrf("/mgmt/platform/ai-test").with(oidcLogin()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("error", org.hamcrest.Matchers.containsString("refused the key")));

        // Not for an organisation's admin.
        TestSubjects.use(orgAdminOfAlpha());
        mockMvc.perform(postCsrf("/mgmt/platform/ai-test").with(oidcLogin())).andExpect(status().isForbidden());
        verify(gateway, org.mockito.Mockito.times(2)).ping(anyString());
    }

    @Test
    @DisplayName("the installation screen sets the gateway's address and key, never shows the key, and resets to the environment")
    void aiGatewaySettings() throws Exception {
        TestSubjects.use(TestSubjects.platformAdmin());
        when(gatewayConfig.effective()).thenReturn(gatewayOf("http://gw.example:8080", SECRET_KEY,
                io.pacod.cms.studio.ai.AiGatewayConfig.Source.STORED));
        String english = renderEnglish("/mgmt/platform");
        assertThat(english).contains("http://gw.example:8080", "…WXYZ", "set here", "/mgmt/platform/ai-gateway/reset")
                .doesNotContain(SECRET_KEY);
        assertThat(render(get("/mgmt/platform"))).doesNotContain(SECRET_KEY);

        when(gatewayConfig.set(eq("http://gw2.example"), eq("pgw_new"), any())).thenReturn(
                gatewayOf("http://gw2.example", "pgw_new", io.pacod.cms.studio.ai.AiGatewayConfig.Source.STORED));
        mockMvc.perform(postCsrf("/mgmt/platform/ai-gateway").param("url", "http://gw2.example").param("apiKey", "pgw_new")
                        .with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("message"));
        verify(gatewayConfig).set(eq("http://gw2.example"), eq("pgw_new"), any());
        verify(gateway, org.mockito.Mockito.never()).ping(anyString());

        // A refusal is a flash, in words.
        when(gatewayConfig.set(eq("http://gw3.example"), eq(""), any()))
                .thenThrow(new io.pacod.cms.studio.ai.AiGatewayConfig.Refused("key_required_with_url", "apiKey"));
        mockMvc.perform(postCsrf("/mgmt/platform/ai-gateway").param("url", "http://gw3.example").with(oidcLogin())
                        .cookie(new jakarta.servlet.http.Cookie("mgmt-lang", "en")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", org.hamcrest.Matchers.containsString("needs its key")));

        // Saving the prefilled address with no key changes nothing, said as such.
        when(gatewayConfig.set(eq("http://gw.example:8080"), eq(""), any()))
                .thenThrow(new io.pacod.cms.studio.ai.AiGatewayConfig.Refused("unchanged", "apiKey"));
        mockMvc.perform(postCsrf("/mgmt/platform/ai-gateway").param("url", "http://gw.example:8080").with(oidcLogin())
                        .cookie(new jakarta.servlet.http.Cookie("mgmt-lang", "en")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", org.hamcrest.Matchers.containsString("Nothing to save")));

        mockMvc.perform(postCsrf("/mgmt/platform/ai-gateway/reset").with(oidcLogin()))
                .andExpect(status().is3xxRedirection());
        verify(gatewayConfig).clear();

        // Not for an organisation's admin.
        TestSubjects.use(orgAdminOfAlpha());
        mockMvc.perform(postCsrf("/mgmt/platform/ai-gateway").param("url", "http://evil.example").param("apiKey", "k")
                .with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(postCsrf("/mgmt/platform/ai-gateway/reset").with(oidcLogin())).andExpect(status().isForbidden());
        verify(gatewayConfig, org.mockito.Mockito.never()).set(eq("http://evil.example"), any(), any());
        verify(gatewayConfig, org.mockito.Mockito.times(1)).clear();
    }

    @Test
    @DisplayName("the installation screen chooses the models from the gateway's list, labels a stale choice, and resets")
    void aiModelsCard() throws Exception {
        TestSubjects.use(TestSubjects.platformAdmin());
        // No gateway: no form, a sentence.
        assertThat(renderEnglish("/mgmt/platform")).contains("Set up the gateway to choose models")
                .doesNotContain("name=\"feature_vision\"");

        when(gatewayConfig.effective()).thenReturn(gatewayOf("http://pacod-gw:8080", SECRET_KEY,
                io.pacod.cms.studio.ai.AiGatewayConfig.Source.ENVIRONMENT));
        when(aiCatalog.lookup()).thenReturn(new io.pacod.cms.studio.ai.AiCatalog.Lookup(
                io.pacod.cms.studio.ai.AiCatalog.parse(io.pacod.cms.studio.ai.AiCatalogTest.fixture()), null));
        when(aiModels.stored()).thenReturn(new io.pacod.cms.studio.ai.AiModels.Stored(null,
                java.util.Map.of(io.pacod.cms.studio.ai.AiFeature.PDF, "gone/model"), null, null));
        String english = renderEnglish("/mgmt/platform");
        assertThat(english).contains("name=\"default\"", "name=\"feature_vision\"", "@fast", "anthropic/claude-dev",
                "Alt text", "gone/model (not offered by the gateway)", "/mgmt/platform/ai-models/reset");
        // Alt text is offered only what takes images.
        String vision = english.substring(english.indexOf("name=\"feature_vision\""));
        vision = vision.substring(0, vision.indexOf("</select>"));
        assertThat(vision).contains("openai/gpt-dev").doesNotContain("local/text-only", "@cheap", "local/no-json");
        assertThat(render(get("/mgmt/platform"))).contains("name=\"feature_vision\"");

        mockMvc.perform(postCsrf("/mgmt/platform/ai-models").param("default", "@fast").param("feature_vision", "openai/gpt-dev")
                        .param("feature_pdf", "gone/model").with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("message"));
        verify(aiModels).set(org.mockito.ArgumentMatchers.argThat(c -> "@fast".equals(c.defaultModel())
                && "openai/gpt-dev".equals(c.features().get(io.pacod.cms.studio.ai.AiFeature.VISION))
                && "gone/model".equals(c.features().get(io.pacod.cms.studio.ai.AiFeature.PDF))), any());

        // A refusal names the feature.
        when(aiModels.set(org.mockito.ArgumentMatchers.argThat(c -> "local/text-only".equals(c.defaultModel())), any()))
                .thenThrow(new io.pacod.cms.studio.ai.AiGatewayConfig.Refused("not_suitable", "vision"));
        mockMvc.perform(postCsrf("/mgmt/platform/ai-models").param("default", "local/text-only").with(oidcLogin())
                        .cookie(new jakarta.servlet.http.Cookie("mgmt-lang", "en")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", org.hamcrest.Matchers.containsString("Alt text")));

        mockMvc.perform(postCsrf("/mgmt/platform/ai-models/reset").with(oidcLogin())).andExpect(status().is3xxRedirection());
        verify(aiModels).clear();

        // A list that cannot be read: said, no form.
        when(aiCatalog.lookup()).thenReturn(new io.pacod.cms.studio.ai.AiCatalog.Lookup(null, "The AI gateway refused the key"));
        assertThat(renderEnglish("/mgmt/platform")).contains("cannot be read: The AI gateway refused the key")
                .doesNotContain("name=\"feature_vision\"");

        TestSubjects.use(orgAdminOfAlpha());
        mockMvc.perform(postCsrf("/mgmt/platform/ai-models").param("default", "@fast").with(oidcLogin())).andExpect(status().isForbidden());
        mockMvc.perform(postCsrf("/mgmt/platform/ai-models/reset").with(oidcLogin())).andExpect(status().isForbidden());
        verify(aiModels, org.mockito.Mockito.times(1)).clear();
    }

    @Test
    @DisplayName("the installation screen renders for a platform admin, with every organisation")
    void platformRenders() throws Exception {
        TestSubjects.use(TestSubjects.platformAdmin());
        String html = render(get("/mgmt/platform"));
        assertThat(html).contains("Alpha Media", "Beta Press", "/mgmt/platform/organisations/org_beta/ai");
        assertThat(renderEnglish("/mgmt/platform")).contains("Who may create organisations");
    }

    @Test
    @DisplayName("the sidebar offers the website's settings and members, and the installation only to its admins")
    void sidebarFollowsPermissions() throws Exception {
        TestSubjects.use(websiteAdminOfAlpha());
        String html = render(get("/sites/alpha/mgmt/members"));
        assertThat(html).contains("/sites/alpha/mgmt/settings", "/mgmt/organisations").doesNotContain("/mgmt/platform");
        TestSubjects.use(TestSubjects.platformAdmin());
        assertThat(render(get("/mgmt/organisations"))).contains("/mgmt/platform");
    }

    @Test
    @DisplayName("an author edits but neither deletes nor publishes; an editor may do both")
    void authorsDoNotDeleteOrPublish() throws Exception {
        TestSubjects.use(Subject.user(List.of(new Binding("cms.author",
                List.of("platform", "organisation:org_alpha", "cms.website:alpha")))));
        for (String path : List.of("/sites/alpha/mgmt/articles/1/delete", "/sites/alpha/mgmt/pages/about/delete",
                "/sites/alpha/mgmt/images/3/delete", "/sites/alpha/mgmt/categories/2/delete",
                "/sites/alpha/mgmt/authors/4/delete", "/sites/alpha/mgmt/social/links/abcdefgh/delete")) {
            mockMvc.perform(postCsrf(path).with(oidcLogin())).andExpect(status().isForbidden());
        }
        io.pacod.cms.studio.access.StudioAccess access = context.getBean(io.pacod.cms.studio.access.StudioAccess.class);
        // Outside a request: a signed-in principal for StudioAccess.subject() to ask TestSubjects about.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken("usr_a", null, "ROLE_USER"));
        io.pacod.cms.core.tenant.TenantContext.runAs(directory.bySite("alpha").orElseThrow(), () -> {
            assertThat(access.allowed("content.write")).isTrue();
            assertThat(access.allowed("content.publish")).isFalse();
        });

        TestSubjects.use(Subject.user(List.of(new Binding("cms.editor",
                List.of("platform", "organisation:org_alpha", "cms.website:alpha")))));
        io.pacod.cms.core.tenant.TenantContext.runAs(directory.bySite("alpha").orElseThrow(), () -> {
            assertThat(access.allowed("content.publish")).isTrue();
            assertThat(access.allowed("content.delete")).isTrue();
        });
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ export and restore

    private static Subject authorOfAlpha() {
        return Subject.user(List.of(new Binding("cms.author", List.of("platform", "organisation:org_alpha", "cms.website:alpha"))));
    }

    @Test
    @DisplayName("export: a website's archive for website.manage, an organisation's for org.manage; streamed as an attachment")
    void exports() throws Exception {
        TestSubjects.use(websiteAdminOfAlpha());
        mockMvc.perform(get("/sites/alpha/mgmt/settings/export").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"website-alpha-")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/zip"));
        verify(archives).exportSite(any(), any());
        // A website admin is not the organisation's admin.
        mockMvc.perform(get("/mgmt/organisations/org_alpha/export").with(oidcLogin())).andExpect(status().isForbidden());

        TestSubjects.use(authorOfAlpha());
        mockMvc.perform(get("/sites/alpha/mgmt/settings/export").with(oidcLogin())).andExpect(status().isForbidden());

        TestSubjects.use(orgAdminOfAlpha());
        mockMvc.perform(get("/mgmt/organisations/org_alpha/export").with(oidcLogin())).andExpect(status().isOk());
        verify(archives).exportOrganisation(eq(ALPHA), any());
        mockMvc.perform(get("/mgmt/organisations/org_beta/export").with(oidcLogin())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the restore screens and the export cards render in both languages, linked from the lists")
    void restoreScreensRender() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        when(archives.maxBytes()).thenReturn(2048L * 1024 * 1024);
        assertThat(render(get("/mgmt/sites/restore"))).contains("data-archive-restore", "data-archive-file", "Alpha Media",
                "/mgmt/sites/restore");
        assertThat(renderEnglish("/mgmt/sites/restore")).contains("Restore a website", "2048 MB");
        assertThat(render(get("/mgmt/organisations/restore"))).contains("data-archive-restore", "/mgmt/organisations/restore");
        assertThat(renderEnglish("/mgmt/organisations/restore")).contains("Restore an organisation");
        assertThat(render(get("/mgmt/sites"))).contains("/mgmt/sites/restore");
        assertThat(render(get("/mgmt/organisations"))).contains("/mgmt/organisations/restore");
        assertThat(render(get("/mgmt/organisations/org_alpha"))).contains("/mgmt/organisations/org_alpha/export");
        assertThat(renderEnglish("/sites/alpha/mgmt/settings")).contains("/sites/alpha/mgmt/settings/export", "Download");
    }

    @Test
    @DisplayName("a restore takes the archive as the body and answers where to go, or why not; the staged file always goes")
    void restorePosts() throws Exception {
        TestSubjects.use(orgAdminOfAlpha());
        java.nio.file.Path staged = java.nio.file.Path.of("target/staged.zip");
        when(archives.stage(any())).thenReturn(staged);
        Website restored = new Website("alpha-2", "org_alpha", "Alpha News");
        when(restore.restoreSite(staged, "org_alpha", "", "")).thenReturn(
                new io.pacod.cms.studio.archive.PortalRestore.RestoredSite(restored,
                        List.of(new io.pacod.cms.studio.archive.PortalRestore.Note("portal.archive.note.renamed", "alpha", "alpha-2"))));
        mockMvc.perform(postCsrf("/mgmt/sites/restore").param("org", "org_alpha").contentType("application/zip")
                        .content(new byte[]{1, 2, 3}).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.location")
                        .value("/sites/alpha-2/mgmt/dashboard"));
        verify(archives).discard(staged);

        when(restore.restoreOrganisation(staged, "")).thenThrow(
                new io.pacod.cms.studio.MgmtOperationException("portal.archive.error.isWebsite"));
        mockMvc.perform(postCsrf("/mgmt/organisations/restore").contentType("application/zip")
                        .content(new byte[]{1}).with(oidcLogin()).cookie(new Cookie("mgmt-lang", "en")))
                .andExpect(status().is(422))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error")
                        .value("This is a single website's archive: restore it under Websites."));

        when(archives.stage(any())).thenThrow(new io.pacod.cms.core.archive.ArchiveException("portal.archive.error.tooLarge", 1L));
        mockMvc.perform(postCsrf("/mgmt/sites/restore").contentType("application/zip").content(new byte[]{1}).with(oidcLogin()))
                .andExpect(status().is(413));
    }
}
