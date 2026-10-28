package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who the website is: its name, its look on a share card, how to reach it, and the third-party
 * identifiers its pages carry.
 *
 * <p>One document per website, id {@link #ID}. None of it belongs in a template -- not the name
 * in the masthead, the tracking container in the head or the address in the footer -- because
 * every website shares the templates of its set, and a value written there would be every
 * website's.
 *
 * <p>The translatable fields are {@link TextItem} lists resolved through {@link LanguageChain},
 * the {@link Page} rules: an absent entry rather than {@code ""}, so "not translated, fall back"
 * and "deliberately empty" stay two different facts.
 *
 * <p>The identifiers ({@link #gtmId}, {@link #googleCseCx}, ...) render <em>nothing</em> when
 * blank. A page carrying another website's tracking container is worse than one carrying none.
 */
@Data
@Document(collection = SiteSettings.COLLECTION)
public class SiteSettings {

    public static final String COLLECTION = "siteSettings";

    /** The single document's id. */
    public static final String ID = "site";

    /**
     * The shape of a Tag Manager container id: {@code GTM-} and four to twelve capitals or digits.
     * One pattern for what the site renders ({@code SiteChrome}) and what a seed may carry
     * ({@code SeedRules}), so a seed cannot pass with an id the site then silently drops.
     */
    public static final Pattern GTM_ID = Pattern.compile("GTM-[A-Z0-9]{4,12}");

    @Id
    private String id = ID;

    /** The website's name: masthead alt text, {@code og:site_name}, the document title suffix. */
    private List<TextItem> names = new ArrayList<>();

    /** One line under the name in the masthead. */
    private List<TextItem> taglines = new ArrayList<>();

    /** The default {@code <meta name="description">} for pages that have none of their own. */
    private List<TextItem> descriptions = new ArrayList<>();

    /** The copyright line at the foot of every page. */
    private List<TextItem> footerNotes = new ArrayList<>();

    /**
     * Scheme and host the website is published at, e.g. {@code https://www.example.com}, with no
     * trailing slash. Absolute URLs -- {@code og:url}, {@code og:image}, the structured data --
     * are built from it. Blank renders them site-relative rather than naming a host.
     */
    private String baseUrl;

    /** Site-relative paths or absolute URLs of the brand images; blank means none. */
    private String logoLight;
    private String logoDark;
    private String favicon;
    private String ogImage;

    /** Footer contact block. Plain text; the address may span lines. */
    private String contactAddress;
    private String contactPhone;
    private String contactEmail;

    /** The website's own profiles, linked from the footer. */
    private List<SocialProfile> socialProfiles = new ArrayList<>();

    /**
     * Google Tag Manager container, {@code GTM-XXXXXXX}. Never in the server's HTML as a script:
     * the page carries it as data, and the reader's browser loads the container only after the
     * reader consents ({@code fragments/consent}, {@code site.js}).
     */
    private String gtmId;

    /** Google Programmable Search engine id; blank hides the Google search screen. */
    private String googleCseCx;

    /** Facebook application id for {@code fb:app_id}. */
    private String facebookAppId;

    /** Search-console ownership tokens. */
    private String googleSiteVerification;
    private String bingSiteVerification;

    private LocalDateTime lastModifiedOn;

    public String name(List<String> chain) {
        return TextItems.resolve(names, chain);
    }

    public String tagline(List<String> chain) {
        return TextItems.resolve(taglines, chain);
    }

    public String description(List<String> chain) {
        return TextItems.resolve(descriptions, chain);
    }

    public String footerNote(List<String> chain) {
        return TextItems.resolve(footerNotes, chain);
    }

    /**
     * The hosts this website answers at, lowercase, with and without {@code www.}: the set a link
     * must point at to count as the website's own. Empty when {@link #baseUrl} is blank or
     * unparseable -- then only site-relative links are the website's own.
     */
    public Set<String> ownHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        String host = hostOf(baseUrl);
        if (host != null) {
            hosts.add(host);
            hosts.add(host.startsWith("www.") ? host.substring(4) : "www." + host);
        }
        return hosts;
    }

    /** {@link #baseUrl} without a trailing slash, or {@code ""} when blank. */
    public String baseUrlOrEmpty() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One of the website's own social-network profiles. */
    @Data
    public static class SocialProfile {
        /** Display label, e.g. {@code Instagram}. */
        private String network;
        private String url;

        public SocialProfile() {
        }

        public SocialProfile(String network, String url) {
            this.network = network;
            this.url = url;
        }
    }
}
