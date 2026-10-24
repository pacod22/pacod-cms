package io.pacod.cms.core.seed;

import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.util.SocialTargets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every shipped demo ({@link DemoContent.Demo}: the journal, {@value DemoContent#SEED}, and the
 * space travels), read through the real mapper: every reference resolves, every text is there in
 * the languages it claims, and every address it writes is one the site serves. A broken reference
 * here would surface as a blank card on a fresh website's home page, at 200.
 */
class SeedIntegrityTest {

    private static final Pattern SITE_ADDRESS = Pattern.compile("^/(article|list|p)/([^/?#]+)$");

    private static final Map<DemoContent.Demo, DemoContent.Seed> SEEDS = new java.util.EnumMap<>(DemoContent.Demo.class);
    private static Set<String> pageSlugs;
    private static DemoContent.Seed seed;

    @BeforeAll
    static void read() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        for (DemoContent.Demo demo : DemoContent.Demo.values()) {
            try (InputStream in = new ClassPathResource(demo.resource()).getInputStream()) {
                SEEDS.put(demo, mapper.readValue(in, DemoContent.Seed.class));
            }
        }
        try (InputStream in = new ClassPathResource("seed/pages.json").getInputStream()) {
            List<PageBootstrap.Seed> pages = mapper.readerForListOf(PageBootstrap.Seed.class).readValue(in);
            pageSlugs = pages.stream().map(PageBootstrap.Seed::getSlug).collect(Collectors.toSet());
        }
    }

    private static Set<Integer> categoryIds() {
        return seed.getCategories().stream().map(DemoContent.CategorySeed::getId).collect(Collectors.toSet());
    }

    private static Set<Integer> articleIds() {
        return seed.getArticles().stream().map(DemoContent.ArticleSeed::getId).collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("the seed is complete in English, and only there: no website gets English under another code")
    void completeInEnglish(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        assertThat(DemoContent.completeLanguages(seed)).containsExactly("en");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("ids are unique, and an article's is positive")
    void idsAreUnique(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        assertThat(seed.getCategories()).extracting(DemoContent.CategorySeed::getId).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(seed.getAuthors()).extracting(DemoContent.AuthorSeed::getId).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(seed.getImages()).extracting(DemoContent.ImageSeed::getKey).doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key).matches("[a-z0-9][a-z0-9-]*"));
        assertThat(seed.getArticles()).hasSizeGreaterThanOrEqualTo(18)
                .extracting(DemoContent.ArticleSeed::getId).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isPositive());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("a newer article has a higher id: the latest list orders by id, as the studio's max-plus-one ids do")
    void idsFollowTheDates(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        List<DemoContent.ArticleSeed> byId = seed.getArticles().stream()
                .sorted(java.util.Comparator.comparing(DemoContent.ArticleSeed::getId)).toList();
        for (int i = 1; i < byId.size(); i++) {
            DemoContent.ArticleSeed older = byId.get(i - 1);
            DemoContent.ArticleSeed newer = byId.get(i);
            boolean later = newer.getDaysAgo() < older.getDaysAgo()
                    || newer.getDaysAgo() == older.getDaysAgo() && newer.getHour() > older.getHour();
            assertThat(later).as("article %d is newer than %d", newer.getId(), older.getId()).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("every article names sections, authors and pictures that exist, and every picture is used")
    void referencesResolve(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        Set<Integer> authors = seed.getAuthors().stream().map(DemoContent.AuthorSeed::getId).collect(Collectors.toSet());
        Set<String> pictures = seed.getImages().stream().map(DemoContent.ImageSeed::getKey).collect(Collectors.toSet());
        Set<String> used = new HashSet<>();
        for (DemoContent.ArticleSeed article : seed.getArticles()) {
            assertThat(article.getCategories()).as("article %d", article.getId()).isNotEmpty().isSubsetOf(categoryIds());
            assertThat(article.getAuthors()).as("article %d", article.getId()).isNotEmpty().isSubsetOf(authors);
            assertThat(article.getImages()).as("article %d", article.getId()).isNotEmpty().isSubsetOf(pictures);
            assertThat(article.getTags()).as("article %d", article.getId()).isNotEmpty()
                    .allSatisfy(tag -> assertThat(seed.getTags()).containsKey(tag));
            assertThat(article.getDaysAgo()).isBetween(0, 27);
            assertThat(article.getHour()).isBetween(0, 23);
            used.addAll(article.getImages());
        }
        assertThat(used).containsExactlyInAnyOrderElementsOf(pictures);
        // Every section has something to list, or its page would be the empty state.
        assertThat(seed.getArticles().stream().flatMap(a -> a.getCategories().stream()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(categoryIds());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("an article translated into a language has its title, description and body there -- never one alone")
    void translationsAreWhole(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        int translated = 0;
        for (DemoContent.ArticleSeed article : seed.getArticles()) {
            Set<String> languages = article.getTitles().keySet();
            assertThat(article.getDescriptions().keySet()).as("article %d", article.getId()).isEqualTo(languages);
            assertThat(article.getBodies().keySet()).as("article %d", article.getId()).isEqualTo(languages);
            if (languages.size() > 1) {
                translated++;
            }
        }
        assertThat(translated).as("articles with a translation").isGreaterThanOrEqualTo(4);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("bodies are the editor's markup: no script, no inline style, no outside address")
    void bodiesAreClean(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        for (DemoContent.ArticleSeed article : seed.getArticles()) {
            for (Map.Entry<String, String> body : article.getBodies().entrySet()) {
                assertThat(body.getValue()).as("article %d in %s", article.getId(), body.getKey())
                        .startsWith("<p").doesNotContain("<script", "style=", "<iframe", "http://", "https://", "src=");
                // A class is one of the body's styled blocks, which every template set styles.
                Matcher classes = Pattern.compile("class=\"([^\"]*)\"").matcher(body.getValue());
                while (classes.find()) {
                    for (String name : classes.group(1).split("\\s+")) {
                        assertThat(io.pacod.cms.core.design.BodyBlocks.byClass(name)).as("article %d: class %s",
                                article.getId(), name).isPresent();
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("the pictures name a motif the generator draws and colours it can parse")
    void picturesAreDrawable(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        for (DemoContent.ImageSeed image : seed.getImages()) {
            DemoImages.Motif.of(image.getMotif());
            assertThat(image.getColours()).as(image.getKey()).hasSizeGreaterThanOrEqualTo(2)
                    .allSatisfy(colour -> assertThat(colour).matches("#[0-9a-f]{6}"));
        }
        DemoContent.Promotions promotions = seed.getPromotions();
        DemoImages.Motif.of(promotions.getImage().getMotif());
        promotions.getRotator().getSlides().forEach(slide -> DemoImages.Motif.of(slide.getMotif()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("the menu group links demo sections; the link page and the promotions name addresses the site serves")
    void addressesResolve(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        assertThat(seed.getMenu().getCategories()).isNotEmpty().isSubsetOf(categoryIds());

        assertThat(seed.getSocial().getLinks()).isNotEmpty().allSatisfy(link -> {
            assertThat(SocialTargets.isValidTarget(link.getUrl())).as(link.getUrl()).isTrue();
            assertThat(link.getTitles()).as(link.getUrl()).containsKey("en");
            if (link.getPlacement() == SocialLinkPlacement.ICON) {
                assertThat(link.getIcon()).as("an icon link needs its icon").isNotNull();
            }
            resolves(link.getUrl());
        });
        assertThat(seed.getSocial().getLatestCount()).isBetween(1, 10);

        DemoContent.Promotions promotions = seed.getPromotions();
        assertThat(promotions.getImage().getPath()).startsWith("/");
        resolves(promotions.getImage().getPath());
        assertThat(promotions.getRotator().getSkeleton()).isIn("sidebar", "top");
        assertThat(promotions.getRotator().getSlides()).isNotEmpty()
                .extracting(DemoContent.SlideSeed::getKey).doesNotHaveDuplicates();
        promotions.getRotator().getSlides().forEach(slide -> resolves(slide.getPath()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("a seed's theme passes the theme editor's own checks, every value of it")
    void themeIsValid(DemoContent.Demo demo) {
        Map<String, String> theme = SEEDS.get(demo).getTheme();
        if (theme != null) {
            assertThat(io.pacod.cms.core.design.ThemeCss.clean(theme)).isEqualTo(theme);
        }
    }

    /** A site address the seed writes: the article, section or page it names is in the seed. */
    private static void resolves(String path) {
        Matcher match = SITE_ADDRESS.matcher(path);
        if (!match.matches()) {
            assertThat(path).as("a site address").isIn("/", "/latest");
            return;
        }
        String reference = match.group(2);
        switch (match.group(1)) {
            case "article" -> assertThat(articleIds()).as(path).contains(Integer.valueOf(reference));
            case "list" -> assertThat(categoryIds()).as(path).contains(Integer.valueOf(reference));
            default -> assertThat(pageSlugs).as(path).contains(reference);
        }
    }
}
