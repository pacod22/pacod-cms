package io.pacod.cms.core.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A folder's seed is checked whole before anything of it is written: {@link FolderSeed} as written
 * passes, and each thing broken in it is named -- with the others still found.
 */
class SeedRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);
    private static final Set<String> STANDING = Set.of("about", "contact", "advertise", "terms", "privacy");

    @TempDir
    Path dir;

    private List<String> check(String json, String language) throws IOException {
        DirectorySeed source = FolderSeed.write(dir, json);
        DemoContent.Seed seed = JsonMapper.builder().build().readValue(source.read("seed.json"), DemoContent.Seed.class);
        return SeedRules.check(seed, source, language,
                new io.pacod.cms.core.design.TemplateSetCatalog().find("magazine").orElseThrow(), STANDING, NOW);
    }

    @Test
    @DisplayName("the folder seed as written has no problem: outside links, tables and a code block are a website's own")
    void validSeedPasses() throws IOException {
        assertThat(check(FolderSeed.JSON, "en")).isEmpty();
    }

    @Test
    @DisplayName("a seed not complete in the website's language is refused for it")
    void otherLanguage() throws IOException {
        assertThat(check(FolderSeed.JSON, "bg")).singleElement().asString().contains("not in the website's language bg");
    }

    @ParameterizedTest(name = "{2}")
    @CsvSource(delimiter = '|', value = {
            "\"categories\": [2], \"authors\"|\"categories\": [9], \"authors\"|article 1: its sections [9]",
            "\"photo\": \"photos/screen.png\"|\"photo\": \"photos/none.png\"|picture screen: The seed file photos/none.png is missing",
            "\"file\": \"tall.html\"|\"file\": \"wide.html\"|the folder has no wide.html",
            "\"pages\": [\"ARTICLE\"]|\"pages\": [\"SOCIAL\"]|the page SOCIAL cannot be booked",
            "\"size\": \"TALL\"|\"size\": \"AUTO\"|an HTML5 frame needs a height",
            "\"categories\": [1]}]}]|\"categories\": [7]}]}]|booking pacod-gw (tall): its sections [7]",
            "on GitHub</a>|on GitHub</a><script>x()</script>|no script, frame or event handler",
            "<p>Six projects|<p class=\\\"hero\\\">Six projects|the class hero",
            "\"date\": \"2025-01-10T09:00\"|\"date\": \"2027-01-10T09:00\"|a published article is not dated in the future",
            "\"date\": \"2025-02-03T10:30\"|\"date\": \"2024-12-03T10:30\"|article 2 is older than article 1",
            "pacod-screen.jpg|pacod-other.jpg|is not one of the seed",
            "\"target\": \"pacod-gw\"|\"target\": \"pacod-kb\"|no page pacod-kb",
            "\"url\": \"/p/pacod-gw\"|\"url\": \"/article/99\"|/article/99 names nothing the seed has",
            "\"fit\": \"pad\"|\"fit\": \"stretch\"|fit is crop or pad",
            "\"shareFile\": \"photos/screen.png\"|\"shareFile\": \"../screen.png\"|may not",
            "\"baseUrl\": \"https://ai.pacod.io\"|\"baseUrl\": \"ai.pacod.io\"|baseUrl ai.pacod.io is not an http(s) address",
            "\"code\": \"bg\"|\"code\": \"Bulgarian\"|a code is two or three lowercase letters",
    })
    @DisplayName("each thing broken is named")
    void eachProblemIsNamed(String from, String to, String expected) throws IOException {
        assertThat(FolderSeed.JSON).contains(from);
        assertThat(check(FolderSeed.JSON.replace(from, to), "en")).anySatisfy(problem -> assertThat(problem).contains(expected));
    }

    @Test
    @DisplayName("every problem at once: two broken things, two problems")
    void everyProblemAtOnce() throws IOException {
        String json = FolderSeed.JSON.replace("\"categories\": [2], \"authors\"", "\"categories\": [9], \"authors\"")
                .replace("\"file\": \"tall.html\"", "\"file\": \"wide.html\"");
        assertThat(check(json, "en")).hasSize(2);
    }

    @Test
    @DisplayName("layouts for a set the installation does not have are one problem, not a crash")
    void layoutsForAnUnknownSet() throws IOException {
        DirectorySeed source = FolderSeed.write(dir, FolderSeed.JSON.replace("\"imagePrefix\"",
                "\"layoutSet\": \"broadsheet\", \"layouts\": {\"HOME\": {\"main\": [{\"type\": \"primary\"}]}}, \"imagePrefix\""));
        DemoContent.Seed seed = JsonMapper.builder().build().readValue(Files.readAllBytes(dir.resolve("seed.json")), DemoContent.Seed.class);
        assertThat(SeedRules.check(seed, source, "en", null, STANDING, NOW))
                .singleElement().asString().contains("broadsheet");
    }
}
