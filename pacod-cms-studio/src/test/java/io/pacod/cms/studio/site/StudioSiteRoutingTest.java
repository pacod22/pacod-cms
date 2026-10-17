package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.MgmtMonitoringService;
import io.pacod.cms.studio.testing.TestSubjects;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The website filter in front of the real security chain, with people signed in through pacod-id.
 *
 * <p>{@code webAppContextSetup} runs no servlet filters of its own accord, so every other studio
 * test reaches the controllers with the test website bound by {@code TestTenantExtension} and the
 * routing bypassed entirely. This one adds the filter by hand, ahead of {@code springSecurity()}
 * as the container orders them, and pins what the two do together -- including that a role at one
 * website opens nothing at another, which the blanket test subject would otherwise hide.
 */
@SpringBootTest
@ActiveProfiles("test")
class StudioSiteRoutingTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TenantDirectory directory;

    @MockitoBean
    private StudioSites sites;

    @MockitoBean
    private MgmtMonitoringService monitoring;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(directory.bySite(anyString())).thenReturn(Optional.empty());
        for (String id : List.of("alpha", "beta")) {
            when(directory.bySite(id)).thenReturn(Optional.of(new Tenant(id, "org_" + id, "site_" + id, null, ZoneId.of("UTC"))));
        }
        when(sites.all()).thenReturn(List.of(view("alpha"), view("beta")));
        when(monitoring.liveVisitors()).thenReturn(MgmtMonitoringService.LiveCount.unavailable());

        Filter siteFilter = context.getBean("studioSiteFilter", FilterRegistrationBean.class).getFilter();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(siteFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void resetSubjects() {
        TestSubjects.reset();
    }

    private static StudioSites.SiteView view(String id) {
        return new StudioSites.SiteView(id, "org_" + id, id, WebsiteStatus.ACTIVE, "en", List.of(id + ".test"),
                "http://" + id + ".test");
    }

    /** A person who is website admin of alpha and holds nothing anywhere else. */
    private static Subject adminOfAlphaOnly() {
        return Subject.user(List.of(new Binding("cms.website_admin",
                List.of("platform", "organisation:org_alpha", "cms.website:alpha"))));
    }

    @Test
    @DisplayName("anonymous on a website's screen is sent to sign in, inside that website")
    @WithAnonymousUser
    void anonymousPrefixedGoesToLogin() throws Exception {
        mockMvc.perform(get("/sites/alpha/mgmt/articles"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/sites/alpha/mgmt/login"));
    }

    @Test
    @DisplayName("signing in from inside a website goes to pacod-id with the registered, absolute callback")
    @WithAnonymousUser
    void signInFromInsideAWebsite() throws Exception {
        String location = mockMvc.perform(get("/sites/alpha/mgmt/oauth2/authorization/pacod-id"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        Map<String, List<String>> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(location).startsWith("http://id.test/oidc/authorize?");
        // {baseUrl} here would have named /sites/alpha/mgmt/oauth2/code/..., which pacod-id refuses.
        assertThat(java.net.URLDecoder.decode(query.get("redirect_uri").getFirst(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("http://studio.test/mgmt/oauth2/code/pacod-id");
        assertThat(query.get("code_challenge_method")).containsExactly("S256");
        assertThat(query.get("code_challenge").getFirst()).hasSize(43);
        assertThat(query).containsKeys("state", "nonce");
        assertThat(query.get("client_id")).containsExactly("pacod-cms-studio");
        assertThat(query.get("scope").getFirst()).contains("openid");
        assertThat(query).doesNotContainKey("prompt");
    }

    @Test
    @DisplayName("the create-an-account link asks pacod-id for its sign-up page, in the studio's language")
    @WithAnonymousUser
    void signUpAsksForPromptCreate() throws Exception {
        String location = mockMvc.perform(get("/mgmt/oauth2/authorization/pacod-id").param("signup", "1")
                        .cookie(new jakarta.servlet.http.Cookie("mgmt-lang", "de")))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        Map<String, List<String>> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(query.get("prompt")).containsExactly("create");
        assertThat(query.get("ui_locales")).containsExactly("de");
    }

    @Test
    @DisplayName("a website that does not exist is a 404")
    @WithMockUser(roles = "MGMT")
    void unknownWebsiteIs404() throws Exception {
        mockMvc.perform(get("/sites/nobody/mgmt/articles")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a website's screen with no website named goes to the website list")
    @WithMockUser(roles = "MGMT")
    void unprefixedScreenGoesToTheList() throws Exception {
        mockMvc.perform(get("/mgmt/articles")).andExpect(redirectedUrl("/mgmt/sites"));
    }

    @Test
    @DisplayName("the studio's image previews and the website list are behind sign-in")
    @WithAnonymousUser
    void previewsAndListNeedSignIn() throws Exception {
        mockMvc.perform(get("/assets/img/articles/a.jpg")).andExpect(redirectedUrl("/mgmt/login"));
        mockMvc.perform(get("/sites/alpha/assets/img/articles/a.jpg")).andExpect(redirectedUrl("/sites/alpha/mgmt/login"));
        mockMvc.perform(get("/resources/banners/acme/index.html")).andExpect(redirectedUrl("/mgmt/login"));
        mockMvc.perform(get("/mgmt/sites")).andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("someone who may read a website's content reaches its image previews; someone else is refused")
    void previewsFollowContentRead() throws Exception {
        TestSubjects.use(adminOfAlphaOnly());
        // 404: the permission passed and the file is simply not there. A 403 here is the
        // annotation missing again (HandlersDeclarePermissionsTest).
        mockMvc.perform(get("/sites/alpha/assets/img/articles/missing.jpg").with(oidcLogin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/sites/beta/assets/img/articles/missing.jpg").with(oidcLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a role at one website opens that website and nothing at another")
    void roleAtOneWebsiteOnly() throws Exception {
        TestSubjects.use(adminOfAlphaOnly());

        mockMvc.perform(get("/sites/alpha/mgmt/monitoring/live").with(oidcLogin()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/sites/beta/mgmt/monitoring/live").with(oidcLogin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/sites/beta/mgmt/articles").with(oidcLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the website list shows only websites the person holds a role on")
    void listShowsOnlyOwnWebsites() throws Exception {
        TestSubjects.use(adminOfAlphaOnly());

        String html = mockMvc.perform(get("/mgmt/sites").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/sites/alpha/mgmt/dashboard").doesNotContain("/sites/beta/");
    }

    @Test
    @DisplayName("a person holding no role sees no website and opens none")
    void noRoleSeesNothing() throws Exception {
        TestSubjects.use(Subject.user(List.of()));

        String html = mockMvc.perform(get("/mgmt/sites").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("/sites/alpha/", "/sites/beta/");

        mockMvc.perform(get("/sites/alpha/mgmt/monitoring/live").with(oidcLogin()))
                .andExpect(status().isForbidden());
    }
}
