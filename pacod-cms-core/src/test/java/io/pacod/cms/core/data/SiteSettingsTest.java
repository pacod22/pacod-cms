package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteSettingsTest {

    @Test
    @DisplayName("the website's own hosts are its base URL's host, with and without www.")
    void ownHostsCoverBothSpellings() {
        SiteSettings settings = new SiteSettings();

        settings.setBaseUrl("https://www.Example.org/");
        assertThat(settings.ownHosts()).containsExactlyInAnyOrder("www.example.org", "example.org");

        settings.setBaseUrl("http://example.org:8080");
        assertThat(settings.ownHosts()).containsExactlyInAnyOrder("example.org", "www.example.org");
    }

    @Test
    @DisplayName("no base URL, or one that does not parse, means no own hosts")
    void noBaseUrlMeansNoHosts() {
        SiteSettings settings = new SiteSettings();
        assertThat(settings.ownHosts()).isEmpty();

        settings.setBaseUrl("not a url");
        assertThat(settings.ownHosts()).isEmpty();
        settings.setBaseUrl("  ");
        assertThat(settings.ownHosts()).isEmpty();
    }

    @Test
    @DisplayName("the base URL is used without trailing slashes")
    void baseUrlIsTrimmed() {
        SiteSettings settings = new SiteSettings();
        assertThat(settings.baseUrlOrEmpty()).isEmpty();
        settings.setBaseUrl(" https://www.example.org// ");
        assertThat(settings.baseUrlOrEmpty()).isEqualTo("https://www.example.org");
    }

    @Test
    @DisplayName("names resolve through the language chain like every other translated text")
    void namesResolve() {
        SiteSettings settings = new SiteSettings();
        settings.setNames(TextItems.put(TextItems.put(null, "en", "Example"), "de", "Beispiel"));

        assertThat(settings.name(LanguageChain.forRequest("de"))).isEqualTo("Beispiel");
        assertThat(settings.name(LanguageChain.forRequest("fr"))).isEqualTo("Example");
    }
}
