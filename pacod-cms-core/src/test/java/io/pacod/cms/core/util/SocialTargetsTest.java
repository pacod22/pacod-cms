package io.pacod.cms.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule {@code MgmtSocialService} applies on save and {@code SocialController} applies again
 * before writing {@code Location}. Site-relative paths are what it adds over {@link HttpUrls}, so
 * that is where the cases are.
 */
class SocialTargetsTest {

    @Test
    @DisplayName("absolute http(s) URLs and paths on this site are accepted")
    void acceptsHttpAndSitePaths() {
        assertThat(SocialTargets.isValidTarget("https://www.instagram.com/example/")).isTrue();
        assertThat(SocialTargets.isValidTarget("http://example.com/?utm_source=x&a=1")).isTrue();
        assertThat(SocialTargets.isValidTarget("/article/3150?lang=de")).isTrue();
        assertThat(SocialTargets.isValidTarget("/p/subscribe")).isTrue();
        assertThat(SocialTargets.isValidTarget("  /p/about  ")).isTrue();
    }

    /** The website's own hosts, as SiteSettings.ownHosts() derives them from its base URL. */
    private static final Set<String> OWN = Set.of("www.example.org", "example.org");

    @Test
    @DisplayName("an article address is recognised however it was pasted, and nothing else is")
    void articleAddressesAreRecognised() {
        assertThat(SocialTargets.articleIdOf("/article/3150", OWN)).isEqualTo(3150);
        assertThat(SocialTargets.articleIdOf(" /article/3150?lang=de ", OWN)).isEqualTo(3150);
        assertThat(SocialTargets.articleIdOf("/de/article/3150", OWN)).isEqualTo(3150);
        assertThat(SocialTargets.articleIdOf("https://www.example.org/article/3150#gallery", OWN)).isEqualTo(3150);
        assertThat(SocialTargets.articleIdOf("http://example.org/en/article/7/", OWN)).isEqualTo(7);
        // The port is not part of a host's identity.
        assertThat(SocialTargets.articleIdOf("http://example.org:8080/article/7", OWN)).isEqualTo(7);

        assertThat(SocialTargets.articleIdOf("/article-print/3150", OWN)).isNull();
        assertThat(SocialTargets.articleIdOf("/list/2", OWN)).isNull();
        assertThat(SocialTargets.articleIdOf("https://example.com/article/3150", OWN)).isNull();
        assertThat(SocialTargets.articleIdOf("https://example.org.evil.example/article/1", OWN)).isNull();
        assertThat(SocialTargets.articleIdOf("/article/0", OWN)).isNull();
        assertThat(SocialTargets.articleIdOf(null, OWN)).isNull();
    }

    @Test
    @DisplayName("with no own hosts, only site-relative addresses are the website's")
    void noOwnHostsMeansRelativeOnly() {
        assertThat(SocialTargets.articleIdOf("/article/5", Set.of())).isEqualTo(5);
        assertThat(SocialTargets.articleIdOf("https://www.example.org/article/5", Set.of())).isNull();
        assertThat(SocialTargets.articleIdOf("https://www.example.org/article/5", null)).isNull();
    }

    @Test
    @DisplayName("standing pages and category listings are recognised too")
    void pageAndCategoryAddressesAreRecognised() {
        assertThat(SocialTargets.pageSlugOf("/p/subscribe", OWN)).isEqualTo("subscribe");
        assertThat(SocialTargets.pageSlugOf("/de/p/About/?lang=de", OWN)).isEqualTo("about");
        assertThat(SocialTargets.pageSlugOf("https://www.example.org/p/terms", OWN)).isEqualTo("terms");
        assertThat(SocialTargets.categoryIdOf("/list/17", OWN)).isEqualTo(17);
        assertThat(SocialTargets.categoryIdOf("/en/list/17?page=2", OWN)).isEqualTo(17);

        assertThat(SocialTargets.pageSlugOf("https://example.com/p/subscribe", OWN)).isNull();
        assertThat(SocialTargets.pageSlugOf("/p/", OWN)).isNull();
        assertThat(SocialTargets.pageSlugOf("/about.html", OWN)).isNull();
        assertThat(SocialTargets.categoryIdOf("/list/news", OWN)).isNull();
        assertThat(SocialTargets.categoryIdOf("/news/list/2", OWN)).isNull();

        assertThat(SocialTargets.isSiteContent("/p/subscribe", OWN)).isTrue();
        assertThat(SocialTargets.isSiteContent("/list/3", OWN)).isTrue();
        assertThat(SocialTargets.isSiteContent("/article/3150", OWN)).isTrue();
        assertThat(SocialTargets.isSiteContent("https://example.com/issue.pdf", OWN)).isFalse();
        assertThat(SocialTargets.isSiteContent("/resources/pdf/issue-12.pdf", OWN)).isFalse();
    }

    @Test
    @DisplayName("everything that resolves to another origin or a script is refused")
    void refusesOpenRedirectsAndScripts() {
        assertThat(SocialTargets.isValidTarget("javascript:alert(1)")).isFalse();
        // Protocol-relative: reads like a path, resolves to another host.
        assertThat(SocialTargets.isValidTarget("//evil.example/")).isFalse();
        // A browser normalises the backslash to a slash in the authority, so this is //evil.example.
        assertThat(SocialTargets.isValidTarget("/\\evil.example/")).isFalse();
        assertThat(SocialTargets.isValidTarget("https://ok.example\\@evil.example/")).isFalse();
        // Header splitting and the tab trick browsers strip before parsing.
        assertThat(SocialTargets.isValidTarget("/p/about\r\nSet-Cookie: x=1")).isFalse();
        assertThat(SocialTargets.isValidTarget("/\t/evil.example")).isFalse();
        assertThat(SocialTargets.isValidTarget("mailto:redakcia@example.com")).isFalse();
        assertThat(SocialTargets.isValidTarget("article/12")).isFalse();
        assertThat(SocialTargets.isValidTarget("")).isFalse();
        assertThat(SocialTargets.isValidTarget(null)).isFalse();
        assertThat(SocialTargets.isValidTarget("/" + "a".repeat(SocialTargets.MAX_LENGTH))).isFalse();
    }
}
