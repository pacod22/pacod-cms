package io.pacod.cms.studio;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real management filter chain.
 *
 * <p>The console's chain signs people in, keeps a session and checks CSRF, and each of those can
 * fail silently in a way that only shows up in a browser -- which is why this runs the real chain
 * through {@code spring-security-test} rather than trusting the configuration to read right.
 *
 * <p>Every case here stops inside the filter chain, so no controller runs and no MongoDB is
 * needed. Anything that would reach a repository belongs in
 * {@link io.pacod.cms.studio.MgmtScreenRenderTest} instead, where the services are
 * mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class MgmtSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // springSecurity() is what installs the real filter chain AND the post-processor that
        // makes @WithMockUser visible to it. Without it every request arrives anonymous and the
        // role assertions below silently pass for the wrong reason.
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("an anonymous console request is sent to the sign-in page")
    @WithAnonymousUser
    void anonymousIsRedirectedToLogin() throws Exception {
        // A 302 to the sign-in page, never a bare 401. `make smoke` asserts the same.
        mockMvc.perform(get("/mgmt/articles"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the sign-in page is reachable without authentication")
    @WithAnonymousUser
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/mgmt/login"))
                .andExpect(status().isOk())
                // Signing in happens on pacod-id's pages: the studio only links there.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/mgmt/oauth2/authorization/pacod-id")));
    }

    @Test
    @DisplayName("the language switch works signed out, so the sign-in page can offer it")
    @WithAnonymousUser
    void languageSwitchIsPublic() throws Exception {
        // Authenticated-only, the request cache would save it and the operator would land on the
        // switch URL after signing in, instead of on the screen they asked for.
        mockMvc.perform(get("/mgmt/ui-language/en").header("Referer", "http://localhost/mgmt/login"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("console styling is reachable without authentication, so the sign-in page renders")
    @WithAnonymousUser
    void consoleAssetsArePublic() throws Exception {
        // This is why they live at /mgmt-assets/** and not under /mgmt/**.
        mockMvc.perform(get("/mgmt-assets/mgmt.css")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/mgmt.js")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the vendored editor is packaged in the jar and actually resolves")
    @WithMockUser(roles = "MGMT")
    void vendoredEditorIsServed() throws Exception {
        // An editor that cannot resolve its own skins and plugins fails silently. Asserting the
        // sub-paths, not just the entry script, is the point: TinyMCE loads these itself, relative
        // to tinymce.min.js, so a partial vendoring would still leave a broken editor with a
        // passing entry-point check.
        mockMvc.perform(get("/mgmt-assets/tinymce/tinymce.min.js")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/themes/silver/theme.min.js")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/models/dom/model.min.js")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/icons/default/icons.min.js")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/skins/ui/oxide/skin.min.css")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/plugins/code/plugin.min.js")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/plugins/table/plugin.min.js")).andExpect(status().isOk());
        // Bulgarian is a separate upstream download from the editor itself, so it is the piece
        // most likely to be forgotten on an upgrade.
        mockMvc.perform(get("/mgmt-assets/tinymce/langs/bg-BG.js")).andExpect(status().isOk());
        // German and Russian follow the console's own language (MgmtText.tinymceLanguage); English
        // is built into the editor and has no pack.
        mockMvc.perform(get("/mgmt-assets/tinymce/langs/de.js")).andExpect(status().isOk());
        mockMvc.perform(get("/mgmt-assets/tinymce/langs/ru.js")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a POST without a CSRF token is refused")
    @WithMockUser(roles = "MGMT")
    void postWithoutCsrfIsForbidden() throws Exception {
        // What the CSRF check is for. Rejected before any controller runs, so this needs
        // no MongoDB.
        mockMvc.perform(post("/mgmt/articles"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the image library is not readable anonymously, JSON endpoint included")
    @WithAnonymousUser
    void imageLibraryRequiresAuthentication() throws Exception {
        // The JSON one is the case worth pinning. It is the console's first fetch endpoint, and a
        // matcher that covered the screen but not its search would hand every filename on the
        // platform to anyone who asked -- with nothing on any screen to show for it.
        mockMvc.perform(get("/mgmt/images"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/images/search?q=x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // The detail screen names the articles an image appears in, and the caption POST writes.
        // Both are under /mgmt/, which is what puts them inside the authenticated matcher -- an
        // endpoint on a new prefix would fall outside it, into the permit-all chain.
        mockMvc.perform(get("/mgmt/images/9"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/images/9").param("caption", "x").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("an article's translation screen and its writes are not reachable anonymously")
    @WithAnonymousUser
    void translationRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/mgmt/articles/42/translations/en"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/articles/42/translations/en")
                        .param("title", "x").param("text", "x").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/articles/42/translations/en/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the editor's fetch endpoints are not exempt from CSRF")
    @WithMockUser(roles = "MGMT")
    void fetchEndpointsRequireCsrf() throws Exception {
        // These are POSTs made by JavaScript rather than by a form, so nothing writes the hidden
        // field for them; mgmt.js carries the token in a header through window.mgmtCsrf(). Being
        // JSON does not make them exempt, and this is what says so.
        mockMvc.perform(post("/mgmt/articles/42/images/arrange").param("ids", "1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/articles/42/images/3/attach"))
                .andExpect(status().isForbidden());

        // The upload's JSON twin. Pinned separately from the form POST beside it, because it is
        // the one a browser actually uses: the form is what «Качи» does with scripting off.
        mockMvc.perform(multipart("/mgmt/articles/42/images/upload")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1})))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the editor's image upload is not reachable anonymously, JSON endpoint included")
    @WithAnonymousUser
    void imageUploadRequiresAuthentication() throws Exception {
        // Two paths to one operation -- the form POST and the fetch mgmt.js makes instead of it --
        // so both are pinned. A matcher covering the screen but not the endpoint the browser uses
        // would let anyone who asked write files onto the content volume, with nothing on any
        // screen to show for it. The argument /mgmt/images/search already makes.
        mockMvc.perform(multipart("/mgmt/articles/42/images")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(multipart("/mgmt/articles/42/images/upload")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the monitoring screen is not readable anonymously, live poll included")
    @WithAnonymousUser
    void monitoringRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/mgmt/monitoring"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // Pinned SEPARATELY from the screen, and this is the case worth having. The poll reports
        // how many people are on the site right now, which is a business figure and not one to
        // answer for anyone who asks. A matcher covering the page but not its poll would hand it
        // out with nothing on any screen to show for it -- the argument /mgmt/images/search and
        // /mgmt/jobs/{id}/status both make.
        mockMvc.perform(get("/mgmt/monitoring/live"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the dashboard is not readable anonymously, live poll included")
    @WithAnonymousUser
    void dashboardRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/mgmt/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // Separately, for the monitoring poll's reason and one more: this answer names which
        // operators are signed in right now.
        mockMvc.perform(get("/mgmt/dashboard/live"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the crawler screen is not readable anonymously")
    @WithAnonymousUser
    void crawlersRequireAuthentication() throws Exception {
        // It lists the addresses crawlers came from and every path they asked for -- and a forged
        // Googlebot's address is as good as anyone's. Under /mgmt/, so the matcher covers it; this
        // pins that it stays there.
        mockMvc.perform(get("/mgmt/crawlers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
        mockMvc.perform(get("/mgmt/crawlers").param("bot", "googlebot").param("page", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the language list is not editable anonymously, every route of it")
    @WithAnonymousUser
    void languageScreensRequireAuthentication() throws Exception {
        // Pinned SEPARATELY from /mgmt/pages, and it is an EDIT surface, not a read one: an
        // unauthenticated POST here would let anyone add, rename or remove a language of the site.
        mockMvc.perform(get("/mgmt/pages/languages"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/pages/languages/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/pages/languages/de"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/pages/languages").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/pages/languages/de/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the language POSTs are not exempt from CSRF")
    @WithMockUser(roles = "MGMT")
    void languagePostsRequireCsrf() throws Exception {
        mockMvc.perform(post("/mgmt/pages/languages"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/pages/languages/de"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/pages/languages/de/delete"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a signed-in person holding no role is refused every website screen")
    @WithMockUser(roles = "SOMETHING-ELSE")
    void wrongRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/mgmt/articles").with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/mgmt/jobs").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the jobs screens are not readable anonymously, poll endpoint included")
    @WithAnonymousUser
    void jobsRequireAuthentication() throws Exception {
        String job = "/mgmt/jobs/0123456789abcdef01234567";
        mockMvc.perform(get("/mgmt/jobs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get(job))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // Pinned SEPARATELY from the screen: the poll returns the job's log and who queued it, and
        // a matcher covering the page but not its poll would hand that to anyone who asked, with
        // nothing on any screen to show for it. The same argument /mgmt/images/search makes.
        mockMvc.perform(get(job + "/status?since=0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("the writing help needs sign-in and CSRF: every call spends the installation's AI budget")
    @WithAnonymousUser
    void writingHelpRequiresAuthentication() throws Exception {
        for (String path : java.util.List.of("/mgmt/ai/assist", "/mgmt/ai/describe", "/mgmt/ai/tags",
                "/mgmt/ai/titles", "/mgmt/ai/draft", "/mgmt/ai/write", "/mgmt/ai/design-body")) {
            mockMvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mgmt/login"));
        }
    }

    @Test
    @DisplayName("queueing, cancelling, promoting and deleting a job all require CSRF")
    @WithMockUser(roles = "MGMT")
    void jobPostsRequireCsrf() throws Exception {
        // Each starts, stops or reorders work against a website from a browser: exactly the writes
        // a cross-site POST would want.
        String job = "/mgmt/jobs/0123456789abcdef01234567";
        mockMvc.perform(post("/mgmt/jobs/pdf").param("ids", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/mgmt/jobs/translate").param("ids", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/mgmt/jobs/translate-site").param("parts", "pages"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(job + "/cancel"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(job + "/promote"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(job + "/delete"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the PDF settings and the issue screens are not readable anonymously, every route of them")
    @WithAnonymousUser
    void pdfAndIssueScreensRequireAuthentication() throws Exception {
        // Pinned route by route, as the layouts are: a literal (`new`) must keep beating /{id}, and
        // the private PDF download must never be reachable without a session.
        for (String path : List.of("/mgmt/pdf", "/mgmt/issues", "/mgmt/issues/new", "/mgmt/issues/3",
                "/mgmt/issues/3/pdf/bg")) {
            mockMvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mgmt/login"));
        }
        for (String path : List.of("/mgmt/pdf", "/mgmt/issues", "/mgmt/issues/3", "/mgmt/issues/3/generate",
                "/mgmt/issues/3/publish", "/mgmt/issues/3/unpublish", "/mgmt/issues/3/delete",
                "/mgmt/issues/3/fill-month")) {
            mockMvc.perform(post(path).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mgmt/login"));
        }
    }

    @Test
    @DisplayName("every issue and PDF-settings write requires CSRF")
    @WithMockUser(roles = "MGMT")
    void pdfAndIssuePostsRequireCsrf() throws Exception {
        for (String path : List.of("/mgmt/pdf", "/mgmt/issues", "/mgmt/issues/3", "/mgmt/issues/3/generate",
                "/mgmt/issues/3/publish", "/mgmt/issues/3/delete")) {
            mockMvc.perform(post(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("the layout screens are not readable anonymously, every route of them")
    @WithAnonymousUser
    void layoutScreensRequireAuthentication() throws Exception {

        // Pinned route by route rather than once for the prefix. These live under /mgmt/banners/
        // so that MgmtSecurityConfig needs no new matcher -- but
        // that only holds while the literal `layouts` keeps beating /mgmt/banners/{id} on
        // specificity. Losing that race would not throw: it would 404 with a message about a
        // missing banner number, which is invisible from every screen.
        mockMvc.perform(get("/mgmt/banners/layouts"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/banners/layouts/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/banners/layouts/acme"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // The skeleton screens carry the markup every generated folder is built from, and an
        // uploaded one is arbitrary HTML the console then publishes. `skeletons` is a reserved
        // layout name precisely so this route cannot be shadowed by a document.
        mockMvc.perform(get("/mgmt/banners/layouts/skeletons"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/banners/layouts/skeletons/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/banners/layouts/skeletons/sidebar"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/banners/layouts/acme/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // The file editor is pinned separately from the builder for the reason /mgmt/images/search
        // is: it reads and writes arbitrary files inside the content volume's banner tree, and a
        // matcher covering the screens but not this one would hand every advertiser's markup to
        // anyone who asked, with nothing on any screen to show for it.
        mockMvc.perform(get("/mgmt/banners/layouts/acme/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(get("/mgmt/banners/layouts/acme/files?file=js/banner.js"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        // The builder's fetch uploads write into the banner tree on the shared volume.
        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/upload").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));

        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/0/image/upload").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/login"));
    }

    @Test
    @DisplayName("every layout write requires CSRF, including the two that touch the filesystem")
    @WithMockUser(roles = "MGMT")
    void layoutPostsRequireCsrf() throws Exception {

        mockMvc.perform(post("/mgmt/banners/layouts").param("id", "x").param("skeleton", "sidebar"))
                .andExpect(status().isForbidden());

        // These two unpack an archive and delete a directory tree on the shared content volume --
        // the writes a cross-site POST would most want.
        mockMvc.perform(post("/mgmt/banners/layouts/import").param("id", "x"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/delete"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/0/delete"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/0/move").param("delta", "1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/skeletons"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/skeletons/read"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/skeletons/sidebar"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/skeletons/sidebar/delete"))
                .andExpect(status().isForbidden());

        // Adoption takes over a folder on the shared volume and the file editor overwrites files
        // in it. Both are exactly the writes a cross-site POST would want.
        mockMvc.perform(post("/mgmt/banners/layouts/adopt").param("folder", "acme2"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/files")
                        .param("file", "js/banner.js").param("content", "x"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/files/replace").param("path", "a.png"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/0/image"))
                .andExpect(status().isForbidden());

        // The fetch halves of the two uploads, pinned separately from their redirect halves.
        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/upload"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme/slides/0/image/upload"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a static file under /mgmt/ is never served: the catch-all answers 404")
    @WithMockUser(roles = "MGMT")
    void staticFilesUnderMgmtAreUnreachable() throws Exception {
        // MgmtHomeController's catch-all 404s every unmapped GET under /mgmt/, so the default
        // static resource handler can never serve a file there, whatever sits under that name.
        mockMvc.perform(get("/mgmt/index.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/mgmt/app.js")).andExpect(status().isNotFound());
    }

    /**
     * Every /mgmt/social route, one by one. They are under /mgmt/ precisely so they fall inside
     * the authenticated matcher -- and the statistics are the one route
     * whose leak would show nothing on any screen: anyone could read the page's traffic.
     */
    @Test
    @DisplayName("the social page screens and statistics are not reachable anonymously, every route")
    @WithAnonymousUser
    void socialScreensRequireAuthentication() throws Exception {
        for (String path : new String[]{"/mgmt/social", "/mgmt/social/stats", "/mgmt/social/links/abcdefgh"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mgmt/login"));
        }
        for (String path : new String[]{"/mgmt/social", "/mgmt/social/links", "/mgmt/social/links/abcdefgh",
                "/mgmt/social/links/abcdefgh/move", "/mgmt/social/links/abcdefgh/toggle",
                "/mgmt/social/links/abcdefgh/delete"}) {
            mockMvc.perform(post(path).with(csrf()).param("delta", "1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mgmt/login"));
        }
    }

    @Test
    @DisplayName("the social page POSTs are not exempt from CSRF")
    @WithMockUser(roles = "MGMT")
    void socialPostsRequireCsrf() throws Exception {
        for (String path : new String[]{"/mgmt/social", "/mgmt/social/links", "/mgmt/social/links/abcdefgh",
                "/mgmt/social/links/abcdefgh/move", "/mgmt/social/links/abcdefgh/toggle",
                "/mgmt/social/links/abcdefgh/delete"}) {
            mockMvc.perform(post(path).param("delta", "1")).andExpect(status().isForbidden());
            // The in-place halves are the same URLs with a header, and are not exempt either.
            mockMvc.perform(post(path).param("delta", "1").header("X-Mgmt-Fragment", "social-links"))
                    .andExpect(status().isForbidden());
        }
    }
}
