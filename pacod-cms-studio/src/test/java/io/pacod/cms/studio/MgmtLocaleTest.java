package io.pacod.cms.studio;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's UI language: the {@code mgmt-lang} cookie, the switch endpoint, and the default.
 *
 * <p>The default case is the one that matters most to the rest of the suite. MockMvc requests
 * carry {@code Locale.ENGLISH} unless told otherwise, so a resolver that consulted
 * {@code request.getLocale()} would render every screen in the render tests in English and every
 * Bulgarian assertion there would fail -- or, worse, be rewritten.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithAnonymousUser
class MgmtLocaleTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private jakarta.validation.Validator validator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String login(Cookie... cookies) throws Exception {
        var request = get("/mgmt/login");
        if (cookies.length > 0) {
            request = request.cookie(cookies);
        }
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("no cookie, no Accept-Language: Bulgarian")
    void defaultIsBulgarian() throws Exception {
        String html = login();
        assertThat(html).contains("<html lang=\"bg\">").contains("Създаване на акаунт");
    }

    @Test
    @DisplayName("Accept-Language is ignored: the operator chooses, the browser does not")
    void acceptLanguageIsIgnored() throws Exception {
        String html = mockMvc.perform(get("/mgmt/login").header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE,de;q=0.9"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<html lang=\"bg\">");
    }

    @Test
    @DisplayName("the mgmt-lang cookie selects the language, the <html lang> and the editor's pack")
    void cookieSelectsLanguage() throws Exception {
        assertThat(login(new Cookie("mgmt-lang", "en")))
                .contains("<html lang=\"en\">").contains("Create an account").doesNotContain("Създаване на акаунт")
                .doesNotContain("data-tinymce");
        assertThat(login(new Cookie("mgmt-lang", "de")))
                .contains("<html lang=\"de\">").contains("Konto erstellen").contains("data-tinymce=\"de\"");
        assertThat(login(new Cookie("mgmt-lang", "ru")))
                .contains("<html lang=\"ru\">").contains("Создать учётную запись").contains("data-tinymce=\"ru\"");
    }

    @Test
    @DisplayName("an unsupported cookie value is Bulgarian, not a Locale nobody offered")
    void unsupportedCookieFallsBack() throws Exception {
        assertThat(login(new Cookie("mgmt-lang", "fr"))).contains("<html lang=\"bg\">");
        assertThat(login(new Cookie("mgmt-lang", "../x"))).contains("<html lang=\"bg\">");
    }

    @Test
    @DisplayName("the public site's `lang` cookie does not change the console's language")
    void publicLangCookieIsNotTheUiLanguage() throws Exception {
        assertThat(login(new Cookie("lang", "en"))).contains("<html lang=\"bg\">");
    }

    @Test
    @DisplayName("the switcher is on the sign-in page, with the current language marked")
    void switcherOnLoginPage() throws Exception {
        String html = login(new Cookie("mgmt-lang", "de"));
        assertThat(html)
                .contains("href=\"/mgmt/ui-language/bg\"")
                .contains("href=\"/mgmt/ui-language/en\"")
                .contains("href=\"/mgmt/ui-language/ru\"");
        assertThat(html).containsPattern("href=\"/mgmt/ui-language/de\"[^>]*aria-current=\"true\"");
        assertThat(html).doesNotContainPattern("href=\"/mgmt/ui-language/en\"[^>]*aria-current");
    }

    @Test
    @DisplayName("switching sets a year-long cookie on / and goes back to the referring screen")
    void switchSetsCookieAndReturns() throws Exception {
        MvcResult result = mockMvc.perform(get("/mgmt/ui-language/ru")
                        .header(HttpHeaders.REFERER, "http://localhost/mgmt/images?q=a%20b&size=48"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/mgmt/images?q=a%20b&size=48"))
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("mgmt-lang=ru")
                .contains("Path=/;")
                .contains("Max-Age=31536000")
                .contains("HttpOnly")
                .doesNotContain("Secure");
    }

    @Test
    @DisplayName("a website's screen is returned to with its /sites/<id> prefix")
    void prefixedRefererKeepsTheWebsite() throws Exception {
        mockMvc.perform(get("/mgmt/ui-language/de")
                        .header(HttpHeaders.REFERER, "http://localhost/sites/demo/mgmt/articles?page=2"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/sites/demo/mgmt/articles?page=2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example/somewhere",
            "http://localhost//evil.example/mgmt/",
            "http://localhost/mgmt/../actuator",
            "http://localhost/mgmt/%2e%2e/x",
            "http://localhost/other",
            "http://localhost/mgmt/ui-language/en",
            "/\\evil.example",
            "not a uri at all",
    })
    @DisplayName("anything that is not a console screen goes back to /mgmt/")
    void refusedReferersGoHome(String referer) throws Exception {
        mockMvc.perform(get("/mgmt/ui-language/en").header(HttpHeaders.REFERER, referer))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/mgmt/"));
    }

    @Test
    @DisplayName("no Referer goes to /mgmt/")
    void noRefererGoesHome() throws Exception {
        mockMvc.perform(get("/mgmt/ui-language/en"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/mgmt/"));
    }

    @Test
    @DisplayName("a foreign Referer's console path is kept, its host never is")
    void foreignHostIsDropped() throws Exception {
        mockMvc.perform(get("/mgmt/ui-language/en").header(HttpHeaders.REFERER, "https://evil.example/mgmt/articles"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/mgmt/articles"));
    }

    @Test
    @DisplayName("a language the console is not offered in is a 404 and sets nothing")
    void unknownLanguageIs404() throws Exception {
        MvcResult result = mockMvc.perform(get("/mgmt/ui-language/fr"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    /** A stand-in form: what the console's forms do with message = "{key}". */
    record Probe(@jakarta.validation.constraints.NotBlank(message = "{chrome.close}") String name) {
    }

    @Test
    @DisplayName("a validation message written as {key} resolves through the console's bundles, per language")
    void validationMessagesResolveThroughTheBundles() {
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.of("de"));
            assertThat(validator.validate(new Probe(""))).extracting(v -> v.getMessage())
                    .containsExactly("Schließen");
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.of("bg"));
            assertThat(validator.validate(new Probe(""))).extracting(v -> v.getMessage())
                    .containsExactly("Затвори");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
}
