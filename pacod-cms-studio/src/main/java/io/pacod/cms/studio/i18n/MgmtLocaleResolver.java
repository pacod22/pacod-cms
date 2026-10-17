package io.pacod.cms.studio.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Duration;
import java.util.Locale;

/**
 * The console's UI language, from the {@code mgmt-lang} cookie and nothing else.
 *
 * <p>Three things are deliberate:
 * <ul>
 *   <li><b>Not {@code lang}.</b> That name already means content language: a request parameter on
 *       the page and social editors, a model attribute on the translation editor, and the public
 *       site's own cookie on path {@code /} of the same host. Sharing it would switch the console's
 *       chrome whenever an operator opened a German translation.</li>
 *   <li><b>The default is configured ({@code pacod.studio.default-language}, English unless
 *       set), never {@code request.getLocale()}.</b> Accept-Language would hand an operator a
 *       console in whatever their browser says -- and MockMvc's default request locale is
 *       English, which would silently decide the language of every render test.</li>
 *   <li><b>An allow-list.</b> A cookie reading {@code fr} is the default language, not
 *       {@code Locale("fr")}: the bundles would fall back to English text anyway, but
 *       {@code <html lang>} and the editor's language pack would claim French.</li>
 * </ul>
 *
 * <p>The cookie's path is {@code /}: the studio's screens are under {@code /mgmt/} and under every
 * website's {@code /sites/<id>/mgmt/}, and one choice has to reach all of them. The studio has a
 * host of its own, so that is not the public site's {@code /} -- and the cookie carries no
 * hardcoded {@code Secure}: behind Caddy {@code request.isSecure()} follows
 * {@code X-Forwarded-Proto}, and on the plain-HTTP local stack a Secure cookie would never come
 * back.
 */
public class MgmtLocaleResolver implements LocaleResolver {

    public static final String COOKIE = "mgmt-lang";
    static final String PATH = "/";
    static final Duration MAX_AGE = Duration.ofDays(365);

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName()) && MgmtText.isSupported(cookie.getValue())) {
                    return Locale.of(cookie.getValue());
                }
            }
        }
        return Locale.of(MgmtText.defaultLanguage());
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        String code = locale == null ? null : locale.getLanguage();
        if (!MgmtText.isSupported(code)) {
            code = MgmtText.defaultLanguage();
        }
        ResponseCookie cookie = ResponseCookie.from(COOKIE, code)
                .path(PATH)
                .maxAge(MAX_AGE)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
