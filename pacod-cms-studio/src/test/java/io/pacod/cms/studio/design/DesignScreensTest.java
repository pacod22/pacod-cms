package io.pacod.cms.studio.design;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.Theme;
import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.ThemeRepository;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.MgmtCategoryService;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.site.StudioSites;
import io.pacod.cms.studio.testing.TestSubjects;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
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
 * The design screens through the real security chain and website filter: each renders, in the test
 * profile's Bulgarian and in English; a designer edits, publishes and switches; an editor or author
 * opens none of it.
 */
@SpringBootTest
@ActiveProfiles("test")
class DesignScreensTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TenantDirectory directory;
    @MockitoBean
    private StudioSites sites;
    @MockitoBean
    private LayoutRepository layouts;
    @MockitoBean
    private ThemeRepository themes;
    @MockitoBean
    private MenuRepository menus;
    @MockitoBean
    private MgmtCategoryService categories;
    @MockitoBean
    private MgmtLanguageService languages;
    @MockitoBean
    private WebsiteRepository websites;
    @MockitoBean
    private io.pacod.cms.core.design.PreviewTokens tokens;
    @MockitoBean
    private io.pacod.cms.studio.ai.AiAccess aiAccess;
    @MockitoBean
    private io.pacod.cms.studio.ai.DesignAssist designAssist;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(directory.bySite(anyString())).thenReturn(Optional.empty());
        when(directory.bySite("alpha")).thenReturn(Optional.of(new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), "editorial")));
        when(sites.current()).thenAnswer(i -> io.pacod.cms.core.tenant.TenantContext.find().map(t ->
                new StudioSites.SiteView(t.siteId(), t.organisationId(), t.siteId(), WebsiteStatus.ACTIVE, "en",
                        List.of(t.siteId() + ".test"), "http://" + t.siteId() + ".test")));

        Layout home = new Layout(io.pacod.cms.core.design.PageType.HOME);
        home.setDraft(Map.of("main", List.of(new Section("s1", "article-list", Map.of("source", "latest", "count", 3,
                        "style", "cards", "heading", "Newest")), new Section("s2", "search-box", Map.of())),
                "gone", List.of(new Section("s3", "carousel", Map.of()))));
        when(layouts.findById(anyString())).thenReturn(Optional.empty());
        when(layouts.findById("HOME")).thenReturn(Optional.of(home));
        when(layouts.save(any(Layout.class))).thenAnswer(i -> i.getArgument(0));

        Theme theme = new Theme();
        theme.setDraft(Map.of("accent", "#1d4e6f"));
        when(themes.findById(Theme.ID)).thenReturn(Optional.of(theme));

        Menu main = new Menu(Menu.MAIN);
        MenuItem item = new MenuItem();
        item.setLabels(new ArrayList<>(List.of(TextItem.builder().lang("en").text("Travel").build())));
        item.setKind(MenuLinkKind.CATEGORY);
        item.setTarget("4");
        MenuItem child = new MenuItem();
        child.setLabels(new ArrayList<>(List.of(TextItem.builder().lang("en").text("About").build())));
        child.setKind(MenuLinkKind.PAGE);
        child.setTarget("about");
        item.setChildren(new ArrayList<>(List.of(child)));
        main.setItems(new ArrayList<>(List.of(item)));
        when(menus.findById(anyString())).thenReturn(Optional.empty());
        when(menus.findById(Menu.MAIN)).thenReturn(Optional.of(main));

        Category travel = new Category();
        travel.setId(4);
        travel.setName("Travel");
        when(categories.findAll(any(), any(), any(), any())).thenReturn(List.of(travel));
        Language en = new Language();
        en.setCode("en");
        en.setAdminName("English");
        en.setNativeName("English");
        when(languages.findAll()).thenReturn(List.of(en));
        when(websites.findById("alpha")).thenReturn(Optional.of(new Website("alpha", "org_a", "Alpha")));

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

    private String render(String path) throws Exception {
        String html = mockMvc.perform(get(path).with(oidcLogin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).as(path).doesNotContain("??");
        String english = mockMvc.perform(get(path).with(oidcLogin()).cookie(new Cookie("mgmt-lang", "en")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(english).contains("lang=\"en\"").doesNotContain("??");
        Properties bg = MgmtText.load("i18n/design_bg.properties");
        Properties en2 = MgmtText.load("i18n/design.properties");
        for (String key : bg.stringPropertyNames()) {
            String value = bg.getProperty(key);
            if (value.length() > 8 && !value.contains("{") && !value.equals(en2.getProperty(key))) {
                assertThat(english).as("Bulgarian %s on the English %s", key, path).doesNotContain(value);
            }
        }
        return english;
    }

    @Test
    @DisplayName("every design screen renders for a designer, in both languages")
    void screensRender() throws Exception {
        TestSubjects.use(at("cms.designer"));
        assertThat(render("/sites/alpha/mgmt/design")).contains("Editorial", "/sites/alpha/mgmt/design/layouts/home",
                "/sites/alpha/mgmt/design/template");
        String home = render("/sites/alpha/mgmt/design/layouts/home");
        assertThat(home).contains("Newest", "Search box", "f_source", "f_count", "data-arrange=\"/sites/alpha/mgmt/design/layouts/home/arrange\"",
                "not on this template", "this template has no such region", "carousel", "value=\"4\"");
        assertThat(render("/sites/alpha/mgmt/design/layouts/article")).contains("own content", "It can be moved, not removed.");
        // The swatches read the preset by key: SpEL takes map[k] as the literal key "k", hence .get(k).
        assertThat(render("/sites/alpha/mgmt/design/theme")).contains("value=\"#1d4e6f\"", "Harbour", "name=\"css\"",
                "class=\"swatch\" style=\"background:#1d4e6f\"");
        assertThat(render("/sites/alpha/mgmt/design/template?set=minimal"))
                .contains("What the switch would leave out", "Search box", "this template does not render this kind of section");
        String menu = render("/sites/alpha/mgmt/design/menus/main");
        assertThat(menu).contains("Travel", "About", "/sites/alpha/mgmt/design/menus/main/items/0.0", "name=\"label_en\"");
        render("/sites/alpha/mgmt/design/menus/footer");
    }

    @Test
    @DisplayName("an editor and an author open nothing of the design; the sidebar does not offer it")
    void contentRolesAreKeptOut() throws Exception {
        for (String role : List.of("cms.editor", "cms.author")) {
            TestSubjects.use(at(role));
            mockMvc.perform(get("/sites/alpha/mgmt/design").with(oidcLogin())).andExpect(status().isForbidden());
            mockMvc.perform(get("/sites/alpha/mgmt/design/theme").with(oidcLogin())).andExpect(status().isForbidden());
            mockMvc.perform(get("/sites/alpha/mgmt/design/preview").with(oidcLogin())).andExpect(status().isForbidden());
            mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/layouts/home/publish").with(oidcLogin()))
                    .andExpect(status().isForbidden());
            String articles = mockMvc.perform(get("/sites/alpha/mgmt/dashboard").with(oidcLogin()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(articles).doesNotContain("/sites/alpha/mgmt/design");
        }
        verify(layouts, never()).save(any());
        verify(tokens, never()).mint(anyString(), any());
    }

    @Test
    @DisplayName("an article's Preview goes to the website's own origin with a token minted at the click; content.read only")
    void articlePreview() throws Exception {
        TestSubjects.use(at("cms.author"));
        when(tokens.enabled()).thenReturn(true);
        when(tokens.mint("alpha", null)).thenReturn("p1.alpha.-.1.mac");
        mockMvc.perform(get("/sites/alpha/mgmt/articles/42/preview").param("lang", "de").with(oidcLogin()))
                .andExpect(redirectedUrl("http://alpha.test/article/42?lang=de&_preview=p1.alpha.-.1.mac"));
        // A language that is not a code is dropped, never written into the address.
        mockMvc.perform(get("/sites/alpha/mgmt/articles/42/preview").param("lang", "x&y=1").with(oidcLogin()))
                .andExpect(redirectedUrl("http://alpha.test/article/42?_preview=p1.alpha.-.1.mac"));
        // Without the shared secret: the public address, where a published article answers.
        when(tokens.enabled()).thenReturn(false);
        mockMvc.perform(get("/sites/alpha/mgmt/articles/42/preview").with(oidcLogin()))
                .andExpect(redirectedUrl("http://alpha.test/article/42"));

        TestSubjects.use(at("cms.analyst"));
        mockMvc.perform(get("/sites/alpha/mgmt/articles/42/preview").with(oidcLogin())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the preview frames the website's own origin with a fresh token, sandboxed, and says when it is off")
    void previewScreen() throws Exception {
        TestSubjects.use(at("cms.designer"));
        when(tokens.enabled()).thenReturn(true);
        when(tokens.mint("alpha", null)).thenReturn("p1.alpha.-.1.mac");
        when(tokens.mint("alpha", "minimal")).thenReturn("p1.alpha.minimal.1.mac");

        String home = render("/sites/alpha/mgmt/design/preview");
        assertThat(home).contains("src=\"http://alpha.test/?_preview=p1.alpha.-.1.mac\"",
                        "sandbox=\"allow-scripts allow-same-origin allow-forms allow-popups\"", "referrerpolicy=\"no-referrer\"",
                        "/sites/alpha/mgmt/design/preview?page=latest")
                // Never top navigation: the website's own script must not be able to move the studio.
                .doesNotContain("allow-top-navigation");
        assertThat(render("/sites/alpha/mgmt/design/preview?page=latest&set=minimal&width=phone"))
                .contains("src=\"http://alpha.test/latest?_preview=p1.alpha.minimal.1.mac\"", "preview-stage--phone",
                        "Rendered with the Minimal template -- the website still uses Editorial.");
        // A set the installation does not have is not minted into a token.
        assertThat(render("/sites/alpha/mgmt/design/preview?set=nonesuch")).contains("_preview=p1.alpha.-.1.mac");

        when(tokens.enabled()).thenReturn(false);
        assertThat(render("/sites/alpha/mgmt/design/preview")).contains("PACOD_PREVIEW_SECRET").doesNotContain("<iframe");

        // The screens that lead to it.
        assertThat(render("/sites/alpha/mgmt/design/layouts/home")).contains("/sites/alpha/mgmt/design/preview?page=home");
        assertThat(render("/sites/alpha/mgmt/design/template?set=minimal")).contains("/sites/alpha/mgmt/design/preview?set=minimal");
    }

    @Test
    @DisplayName("design assist: offered with AI, its outcome flashed, its refusal said; the draft is all it touches")
    void designAssist() throws Exception {
        TestSubjects.use(at("cms.designer"));
        assertThat(render("/sites/alpha/mgmt/design/theme")).doesNotContain("/design/theme/assist");
        when(aiAccess.availableHere()).thenReturn(true);
        assertThat(render("/sites/alpha/mgmt/design/theme")).contains("/sites/alpha/mgmt/design/theme/assist");
        assertThat(render("/sites/alpha/mgmt/design/layouts/home")).contains("/sites/alpha/mgmt/design/layouts/home/assist");

        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        when(designAssist.theme("calm")).thenReturn(new io.pacod.cms.studio.ai.DesignAssist.Outcome(2, List.of("ink")));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/theme/assist").param("brief", "calm").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/design/theme"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("message"));
        when(designAssist.layout(io.pacod.cms.core.design.PageType.HOME, "x"))
                .thenThrow(new io.pacod.cms.studio.ai.AiGateway.AiException("The answer held no section the template could take"));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/layouts/home/assist").param("brief", "x").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/design/layouts/home"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("error"));

        when(aiAccess.refusalHere()).thenReturn(Optional.of(io.pacod.cms.studio.ai.AiRefusal.ORGANISATION_OFF));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/theme/assist").param("brief", "calm").with(oidcLogin()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("error"));
        verify(designAssist, org.mockito.Mockito.times(1)).theme(any());
    }

    @Test
    @DisplayName("the preview, which mints a token, is behind sign-in")
    void previewNeedsSignIn() throws Exception {
        mockMvc.perform(get("/sites/alpha/mgmt/design/preview")).andExpect(status().is3xxRedirection());
        verify(tokens, never()).mint(anyString(), any());
    }

    @Test
    @DisplayName("a designer edits, publishes and switches the template")
    void designerWorks() throws Exception {
        TestSubjects.use(at("cms.designer"));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/layouts/home/sections").with(oidcLogin())
                        .param("region", "aside").param("type", "rich-text"))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/design/layouts/home"));
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/layouts/home/publish").with(oidcLogin()))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/design/layouts/home"));
        verify(layouts, atLeastOnce()).save(any());
        mockMvc.perform(postCsrf("/sites/alpha/mgmt/design/template").with(oidcLogin()).param("set", "minimal"))
                .andExpect(redirectedUrl("/sites/alpha/mgmt/design"));
        verify(websites).save(org.mockito.ArgumentMatchers.argThat(w -> "minimal".equals(w.getTemplateSet())));
        mockMvc.perform(get("/sites/alpha/mgmt/design/layouts/nonsense").with(oidcLogin())).andExpect(status().isNotFound());
    }
}
