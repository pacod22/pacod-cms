package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.SiteFileKind;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteFileRulesTest {

    private static final Set<String> HOSTS = Set.of("demo.example.com", "www.demo.example.com");

    private static final String SITEMAP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://demo.example.com/</loc></url>
            </urlset>
            """;

    @Test
    void cleanUnifiesLineEndsAndDropsControlCharacters() {
        assertThat(SiteFileRules.clean("a\r\nb\rc\u0000d\te\u0007")).isEqualTo("a\nb\ncd\te");
    }

    @Test
    void blankIsNull() {
        assertThat(SiteFileRules.checkCustom(SiteFileKind.ROBOTS, " \n ")).isNull();
        assertThat(SiteFileRules.checkAdditions(SiteFileKind.ROBOTS, "", HOSTS)).isNull();
        assertThat(SiteFileRules.checkExclusions(null)).isNull();
    }

    @Test
    void aValidSitemapPasses() {
        assertThat(SiteFileRules.checkCustom(SiteFileKind.SITEMAP, SITEMAP)).isEqualTo(SITEMAP);
        assertThat(SiteFileRules.checkCustom(SiteFileKind.SITEMAP, """
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>https://demo.example.com/a.xml</loc></sitemap>
                </sitemapindex>""")).isNotNull();
    }

    @Test
    void aDoctypeIsRefusedSoNoEntityIsEverRead() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE urlset [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>&x;</loc></url></urlset>""";
        assertThatThrownBy(() -> SiteFileRules.checkCustom(SiteFileKind.SITEMAP, xxe))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.doctype"));
    }

    @Test
    void aSitemapMustBeWellFormedRootedAndAbsolute() {
        assertThatThrownBy(() -> SiteFileRules.checkCustom(SiteFileKind.SITEMAP, "<urlset>"))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.xml"));
        assertThatThrownBy(() -> SiteFileRules.checkCustom(SiteFileKind.SITEMAP, "<html/>"))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.sitemapRoot"));
        assertThatThrownBy(() -> SiteFileRules.checkCustom(SiteFileKind.SITEMAP,
                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>/p/about</loc></url></urlset>"))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.sitemapLoc"));
    }

    @Test
    void sizesAreCappedInBytes() {
        String big = "#" + "я".repeat(SiteFileKind.ROBOTS.customLimit() / 2);
        assertThatThrownBy(() -> SiteFileRules.checkCustom(SiteFileKind.ROBOTS, big))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.tooLarge"));
    }

    @Test
    void llmsFullTakesNoCustomText() {
        assertThatThrownBy(() -> SiteFileRules.checkCustom(SiteFileKind.LLMS_FULL, "# x"))
                .isInstanceOf(SiteFileRules.Refused.class);
    }

    @Test
    void robotsAdditionsAreFieldLines() {
        assertThat(SiteFileRules.checkAdditions(SiteFileKind.ROBOTS, "# comment\nUser-agent: x\nDisallow: /a", HOSTS))
                .isNotNull();
        assertThatThrownBy(() -> SiteFileRules.checkAdditions(SiteFileKind.ROBOTS, "Disallow: /a\n<script>", HOSTS))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> {
                    assertThat(r.code()).isEqualTo("siteFiles.error.robotsLine");
                    assertThat(r.args()).containsExactly(2, "<script>");
                });
    }

    @Test
    void sitemapAdditionsArePathsOrOwnAddresses() {
        assertThat(SiteFileRules.checkAdditions(SiteFileKind.SITEMAP, "/landing\nhttps://www.demo.example.com/x", HOSTS))
                .isNotNull();
        assertThatThrownBy(() -> SiteFileRules.checkAdditions(SiteFileKind.SITEMAP, "https://other.test/x", HOSTS))
                .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.foreignHost"));
        for (String bad : new String[]{"//evil.test/x", "/\\evil", "landing", "ftp://demo.example.com/"}) {
            assertThatThrownBy(() -> SiteFileRules.checkAdditions(SiteFileKind.SITEMAP, bad, HOSTS))
                    .as(bad).isInstanceOf(SiteFileRules.Refused.class);
        }
    }

    @Test
    void exclusionsArePathsWithAnOptionalTrailingStar() {
        assertThat(SiteFileRules.checkExclusions("/p/terms\n/list/*\n# note")).isNotNull();
        for (String bad : new String[]{"p/terms", "/a*b", "//x", "/a b"}) {
            assertThatThrownBy(() -> SiteFileRules.checkExclusions(bad)).as(bad)
                    .isInstanceOfSatisfying(SiteFileRules.Refused.class, r -> assertThat(r.code()).isEqualTo("siteFiles.error.exclusionLine"));
        }
        PathExclusions exclusions = PathExclusions.of("/p/terms\n/list/*\nnonsense");
        assertThat(exclusions.excludes("/p/terms")).isTrue();
        assertThat(exclusions.excludes("/p/terms-old")).isFalse();
        assertThat(exclusions.excludes("/list/3")).isTrue();
        assertThat(exclusions.excludes("/article/3")).isFalse();
    }
}
