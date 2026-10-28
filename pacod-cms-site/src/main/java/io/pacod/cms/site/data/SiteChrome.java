package io.pacod.cms.site.data;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.util.HttpUrls;
import io.pacod.cms.core.util.SocialTargets;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The website's identity as the templates see it: {@code ${site}} on every public page.
 *
 * <p>Built per request from the cached {@link SiteSettings} and {@link Menu}s plus the origin the
 * request arrived at, so absolute URLs name a host even before anyone has filled in
 * {@link SiteSettings#getBaseUrl()} ({@link SiteOrigin}).
 *
 * <p><b>Every third-party identifier is validated here, not in the template.</b> The tracking
 * container id is written into a script; an id that is not the shape its vendor issues renders
 * nothing at all rather than whatever was stored.
 */
public final class SiteChrome {

    private static final Pattern CSE_CX = Pattern.compile("[A-Za-z0-9:_-]{6,64}");
    private static final Pattern FB_APP_ID = Pattern.compile("\\d{5,20}");
    private static final Pattern VERIFICATION = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    /**
     * The region {@code og:locale} pairs with a bare language code. Open Graph wants a full
     * locale and the content only knows the language; this is the usual default region for each.
     * A language not listed gets its own code upper-cased, which is right for most of the rest.
     */
    private static final Map<String, String> OG_REGION = Map.ofEntries(
            Map.entry("en", "US"), Map.entry("zh", "CN"), Map.entry("el", "GR"),
            Map.entry("sr", "RS"), Map.entry("sv", "SE"), Map.entry("da", "DK"),
            Map.entry("cs", "CZ"), Map.entry("uk", "UA"), Map.entry("ja", "JP"),
            Map.entry("ko", "KR"), Map.entry("he", "IL"), Map.entry("ar", "SA"),
            Map.entry("hi", "IN"), Map.entry("fa", "IR"), Map.entry("sl", "SI"),
            Map.entry("et", "EE"), Map.entry("nb", "NO"), Map.entry("vi", "VN"));

    /** A language prefix on one of the three reader routes: {@code /de/article/5}. */
    private static final Pattern LANG_PREFIX = Pattern.compile("^/[a-z]{2,3}(/(?:article|list|p)/.*)$");

    private final SiteSettings settings;
    private final Map<String, Menu> menus;
    private final String origin;
    private final List<Language> languages;
    private final String path;
    private final String query;
    private final boolean tracking;

    private SiteChrome(SiteSettings settings, Map<String, Menu> menus, String origin,
                       List<Language> languages, String path, String query) {
        this.settings = settings != null ? settings : new SiteSettings();
        this.menus = menus != null ? menus : Map.of();
        this.origin = SiteOrigin.of(this.settings, origin);
        this.languages = languages != null ? languages : List.of();
        this.path = path != null && path.startsWith("/") ? path : "/";
        this.query = query;
        this.tracking = true;
    }

    private SiteChrome(SiteChrome chrome, boolean tracking) {
        this.settings = chrome.settings;
        this.menus = chrome.menus;
        this.origin = chrome.origin;
        this.languages = chrome.languages;
        this.path = chrome.path;
        this.query = chrome.query;
        this.tracking = tracking;
    }

    /**
     * The same chrome with no third party told about the page: no tag manager, no Facebook SDK,
     * no verification metas. For the studio's draft preview, which reloads on every saved draft
     * and would otherwise be a stream of page views in the website's analytics. Every template
     * set asks these methods, so none needs a preview branch of its own.
     */
    public SiteChrome withoutTracking() {
        return new SiteChrome(this, false);
    }

    /**
     * @param origin    scheme, host and port the request arrived at, used for absolute URLs when
     *                  the settings carry no base URL
     * @param languages what the language switcher offers, in order
     * @param path      the request's path, which the switcher's links point back at
     * @param query     the request's raw query string, or null; kept on the switcher's links
     */
    public static SiteChrome of(SiteSettings settings, Map<String, Menu> menus, String origin,
                                List<Language> languages, String path, String query) {
        return new SiteChrome(settings, menus, origin, languages, path, query);
    }

    /** No language switcher: for renders that are not a reader's page. */
    public static SiteChrome of(SiteSettings settings, Map<String, Menu> menus, String origin) {
        return new SiteChrome(settings, menus, origin, null, null, null);
    }

    /** No settings and no menus: what an unreadable database renders as. */
    public static SiteChrome empty(String origin) {
        return new SiteChrome(null, null, origin, null, null, null);
    }

    /* ------------------------------------------------------------------ identity */

    public String name(String lang) {
        String name = settings.name(LanguageChain.forRequest(lang));
        return name != null ? name : "";
    }

    public String tagline(String lang) {
        return settings.tagline(LanguageChain.forRequest(lang));
    }

    public String description(String lang) {
        return settings.description(LanguageChain.forRequest(lang));
    }

    public String footerNote(String lang) {
        return settings.footerNote(LanguageChain.forRequest(lang));
    }

    public String logoLight() {
        return asset(settings.getLogoLight());
    }

    public String logoDark() {
        String dark = asset(settings.getLogoDark());
        return dark != null ? dark : logoLight();
    }

    public String favicon() {
        return asset(settings.getFavicon());
    }

    public String ogImage() {
        return asset(settings.getOgImage());
    }

    /** {@code og:locale} for {@code lang}: {@code de_DE}, {@code en_US}, {@code zh_CN}. */
    public String ogLocale(String lang) {
        String code = LanguageChain.normalise(lang);
        if (code == null || !code.matches("[a-z]{2,3}")) {
            code = LanguageChain.original();
        }
        return code + "_" + OG_REGION.getOrDefault(code, code.toUpperCase(java.util.Locale.ROOT));
    }

    /* ------------------------------------------------------------------ addresses */

    /** Scheme and host, no trailing slash. */
    public String origin() {
        return origin;
    }

    /**
     * {@code path} made absolute against the website's address. An address that is already
     * absolute is returned unchanged, so an image hosted elsewhere stays where it is.
     */
    public String url(String path) {
        if (path == null || path.isBlank()) {
            return origin + "/";
        }
        if (HttpUrls.isHttpUrl(path)) {
            return path;
        }
        return origin + (path.startsWith("/") ? path : "/" + path);
    }

    /** The hosts a link must name to count as this website's own. */
    public Set<String> ownHosts() {
        return settings.ownHosts();
    }

    /* ------------------------------------------------------------------ contact */

    public String contactAddress() {
        return blankToNull(settings.getContactAddress());
    }

    /** The address split into its lines, blank lines dropped; empty when there is none. */
    public List<String> contactAddressLines() {
        String address = contactAddress();
        if (address == null) {
            return List.of();
        }
        return address.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    public String contactPhone() {
        return blankToNull(settings.getContactPhone());
    }

    public String contactEmail() {
        return blankToNull(settings.getContactEmail());
    }

    public boolean hasContact() {
        return contactAddress() != null || contactPhone() != null || contactEmail() != null;
    }

    /** The website's own profiles, those with an http(s) address and a label only. */
    public List<SiteSettings.SocialProfile> socialProfiles() {
        List<SiteSettings.SocialProfile> profiles = settings.getSocialProfiles();
        if (profiles == null) {
            return List.of();
        }
        return profiles.stream()
                .filter(p -> p != null && HttpUrls.isHttpUrl(p.getUrl())
                        && p.getNetwork() != null && !p.getNetwork().isBlank())
                .toList();
    }

    /* ------------------------------------------------------------------ third parties */

    public String gtmId() {
        return tracking ? valid(settings.getGtmId(), SiteSettings.GTM_ID) : null;
    }

    public String googleCseCx() {
        return valid(settings.getGoogleCseCx(), CSE_CX);
    }

    public String facebookAppId() {
        return tracking ? valid(settings.getFacebookAppId(), FB_APP_ID) : null;
    }

    public String googleSiteVerification() {
        return tracking ? valid(settings.getGoogleSiteVerification(), VERIFICATION) : null;
    }

    public String bingSiteVerification() {
        return tracking ? valid(settings.getBingSiteVerification(), VERIFICATION) : null;
    }

    /* ------------------------------------------------------------------ languages */

    /**
     * The language switcher: one link per language the website offers, back to this page with
     * {@code lang} replaced and every other query parameter kept -- a search keeps its term and
     * its page.
     *
     * <p>A language prefix in the path is dropped, because the path outranks {@code ?lang}:
     * {@code /de/article/5?lang=fr} would still render German. The bare path plus {@code ?lang}
     * is the one spelling every reader route answers.
     */
    public List<LanguageLink> languageLinks(String current) {
        String code = LanguageChain.normalise(current);
        Matcher prefixed = LANG_PREFIX.matcher(path);
        String bare = prefixed.matches() ? prefixed.group(1) : path;
        List<LanguageLink> links = new ArrayList<>(languages.size());
        for (Language language : languages) {
            if (language == null || language.getCode() == null) {
                continue;
            }
            String href = UriComponentsBuilder.fromPath(bare).query(query)
                    .replaceQueryParam("lang", language.getCode())
                    .build().toUriString();
            links.add(new LanguageLink(language.getCode(), language.nativeOrCode(), href,
                    language.getCode().equals(code)));
        }
        return links;
    }

    /**
     * The codes of the languages the website offers, in the switcher's order: what a page's
     * {@code hreflang} alternates are chosen from ({@link HeadMeta}).
     */
    public List<String> languageCodes() {
        List<String> codes = new ArrayList<>(languages.size());
        for (Language language : languages) {
            String code = language != null ? LanguageChain.normalise(language.getCode()) : null;
            if (code != null && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** Whether the website offers {@code code}: one of {@link #languageCodes()}. */
    public boolean offers(String code) {
        String normalised = LanguageChain.normalise(code);
        return normalised != null && languageCodes().contains(normalised);
    }

    /** One entry of the language switcher. */
    public record LanguageLink(String code, String name, String href, boolean current) {
    }

    /* ------------------------------------------------------------------ menus */

    /**
     * The menu called {@code key}, labels resolved for {@code lang} and every address built,
     * carrying {@code lang} for the website's own routes. Items whose label resolves to nothing,
     * or whose address is not one the website may link to, are left out rather than rendered
     * as an empty or dangerous link.
     */
    public List<MenuEntry> menu(String key, String lang) {
        Menu menu = menus.get(key);
        if (menu == null || menu.getItems() == null) {
            return List.of();
        }
        List<String> chain = LanguageChain.forRequest(lang);
        return entries(menu.getItems(), chain, lang, 0);
    }

    private List<MenuEntry> entries(List<MenuItem> items, List<String> chain, String lang, int depth) {
        List<MenuEntry> entries = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            if (item == null) {
                continue;
            }
            String label = item.label(chain);
            if (label == null) {
                continue;
            }
            List<MenuEntry> children = depth == 0 && item.isGroup()
                    ? entries(item.getChildren(), chain, lang, depth + 1)
                    : List.of();
            String href = href(item, lang);
            if (href == null && children.isEmpty()) {
                continue;
            }
            boolean external = href != null && HttpUrls.isHttpUrl(href) && !isOwn(href);
            entries.add(new MenuEntry(label, href, external, item.getColumns(), children));
        }
        return entries;
    }

    private String href(MenuItem item, String lang) {
        MenuLinkKind kind = item.getKind() != null ? item.getKind() : MenuLinkKind.NONE;
        String target = item.getTarget() != null ? item.getTarget().strip() : "";
        return switch (kind) {
            case NONE -> null;
            case HOME -> withLang("/", lang);
            case LATEST -> withLang("/latest", lang);
            case SEARCH -> withLang("/search", lang);
            case SOCIAL -> withLang("/social", lang);
            case CATEGORY -> target.matches("\\d{1,9}") ? withLang("/list/" + target, lang) : null;
            case PAGE -> target.matches("[a-z0-9][a-z0-9-]{0,63}") ? withLang("/p/" + target, lang) : null;
            case URL -> SocialTargets.isValidTarget(target) ? target : null;
        };
    }

    private boolean isOwn(String href) {
        String host = UriComponentsBuilder.fromUriString(href).build().getHost();
        return host != null && ownHosts().contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    private static String withLang(String path, String lang) {
        String code = LanguageChain.normalise(lang);
        return code == null ? path : path + "?lang=" + code;
    }

    /* ------------------------------------------------------------------ helpers */

    private static String asset(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return HttpUrls.isHttpUrl(trimmed) || SocialTargets.isSitePath(trimmed) ? trimmed : null;
    }

    private static String valid(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return pattern.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * One rendered menu entry. A group has children and may have no {@code href}; a leaf always
     * has one.
     */
    public record MenuEntry(String label, String href, boolean external, Integer columns,
                            List<MenuEntry> children) {

        public boolean group() {
            return !children.isEmpty();
        }

        /** Columns for a group's panel: as configured, else one per eight children, at most three. */
        public int panelColumns() {
            if (columns != null && columns > 0) {
                return Math.min(columns, 4);
            }
            return Math.max(1, Math.min(3, (children.size() + 7) / 8));
        }
    }
}
