package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsBuilderTest {

    private static final String ORIGIN = "https://demo.example.com";
    private final RobotsBuilder builder = new RobotsBuilder();

    @Test
    void theDefaultIsTodaysFile() {
        assertThat(builder.build(ORIGIN, new SiteFiles())).isEqualTo("""
                User-agent: *
                Disallow: /article-print/
                Disallow: /issue-print/
                Disallow: /b/
                Disallow: /social/go/
                Disallow: /social/read/
                Disallow: /search
                Disallow: /web/v1/

                Sitemap: https://demo.example.com/sitemap.xml
                """);
    }

    @Test
    void aiGroupsComeFirstAndTheStarGroupLast() {
        SiteFiles files = new SiteFiles();
        files.setBlockAiTraining(true);
        files.setBlockAiAnswering(true);
        String robots = builder.build(ORIGIN, files);
        assertThat(robots).startsWith("User-agent: GPTBot\n");
        assertThat(robots.indexOf("User-agent: OAI-SearchBot")).isGreaterThan(robots.indexOf("User-agent: cohere-ai"));
        assertThat(robots.indexOf("User-agent: *")).isGreaterThan(robots.indexOf("User-agent: Perplexity-User"));
        assertThat(robots).contains("User-agent: cohere-ai\nDisallow: /\n\n");
    }

    @Test
    void additionsFollowTheStarGroupSoAGroupLessRuleIsEveryCrawlers() {
        SiteFiles files = new SiteFiles();
        files.setBlockAiTraining(true);
        files.getRobots().setMode(SiteFileMode.AUTO_PLUS);
        files.getRobots().setAdditions("Disallow: /private/\n");
        String robots = builder.build(ORIGIN, files);
        int star = robots.indexOf("User-agent: *");
        int addition = robots.indexOf("Disallow: /private/");
        assertThat(addition).isGreaterThan(star);
        assertThat(robots.substring(star, addition)).doesNotContain("User-agent: GPTBot");
        assertThat(addition).isLessThan(robots.indexOf("Sitemap:"));
    }

    @Test
    void additionsAreIgnoredInPlainAutomatic() {
        SiteFiles files = new SiteFiles();
        files.getRobots().setAdditions("Disallow: /private/");
        assertThat(builder.build(ORIGIN, files)).doesNotContain("/private/");
    }

    @Test
    void noSitemapLineWhileTheSitemapIsOff() {
        SiteFiles files = new SiteFiles();
        files.setting(SiteFileKind.SITEMAP).setMode(SiteFileMode.OFF);
        assertThat(builder.build(ORIGIN, files)).doesNotContain("Sitemap:");
    }
}
