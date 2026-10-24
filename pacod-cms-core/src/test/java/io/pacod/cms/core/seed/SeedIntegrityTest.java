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

    /** The one shape a picture may take inside a body: a demo picture, by its stored name. */
    private static final Pattern PICTURE = Pattern.compile("src=\"/assets/img/articles/demo-([a-z0-9-]+)\\.jpg\"");

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

    /** The standing pages every website has, and the seed's own. */
    private static Set<String> pageSlugs() {
        Set<String> slugs = new HashSet<>(pageSlugs);
        seed.getPages().forEach(page -> slugs.add(page.getSlug()));
        return slugs;
    }

    private static Set<String> pictureKeys() {
        return seed.getImages().stream().map(DemoContent.ImageSeed::getKey).collect(Collectors.toSet());
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
            // A picture placed inside the body is hidden from the gallery, so another must lead it.
            assertThat(article.getInline()).as("article %d", article.getId()).isSubsetOf(article.getImages())
                    .doesNotContain(article.getImages().get(0));
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
        // The journal shows translations off; the space demo is written in English alone, and narrows
        // the website's languages to it.
        assertThat(translated).as("articles with a translation").isGreaterThanOrEqualTo(demo == DemoContent.Demo.JOURNAL ? 4 : 0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("bodies are the editor's markup: no script, no inline style, no outside address")
    void bodiesAreClean(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        for (DemoContent.ArticleSeed article : seed.getArticles()) {
            for (Map.Entry<String, String> body : article.getBodies().entrySet()) {
                String as = "article " + article.getId() + " in " + body.getKey();
                clean(as, body.getValue());
                // A picture inside the body is one of the article's own, hidden from its gallery.
                Matcher pictures = PICTURE.matcher(body.getValue());
                while (pictures.find()) {
                    assertThat(article.getInline()).as(as).contains(pictures.group(1));
                }
            }
        }
        for (DemoContent.PageSeed page : seed.getPages()) {
            assertThat(page.getTitles()).as(page.getSlug()).containsKey("en");
            for (Map.Entry<String, String> body : page.getBodies().entrySet()) {
                String as = "page " + page.getSlug() + " in " + body.getKey();
                clean(as, body.getValue());
                Matcher pictures = PICTURE.matcher(body.getValue());
                while (pictures.find()) {
                    assertThat(pictureKeys()).as(as).contains(pictures.group(1));
                }
                // A link inside the website is to something the website has.
                Matcher links = Pattern.compile("href=\"([^\"]*)\"").matcher(body.getValue());
                while (links.find()) {
                    resolves(links.group(1));
                }
            }
        }
    }

    /**
     * The editor's markup: a paragraph first, no script, inline style, frame or outside address, a
     * picture only in the one shape {@link #PICTURE} allows, and a class only one of the body's styled
     * blocks, which every template set styles.
     */
    private static void clean(String as, String html) {
        assertThat(html).as(as).startsWith("<p").doesNotContain("<script", "style=", "<iframe", "http://", "https://");
        assertThat(html.split("src=", -1).length - 1).as(as + ": every src is a demo picture's")
                .isEqualTo((int) PICTURE.matcher(html).results().count());
        Matcher classes = Pattern.compile("class=\"([^\"]*)\"").matcher(html);
        while (classes.find()) {
            for (String name : classes.group(1).split("\\s+")) {
                assertThat(io.pacod.cms.core.design.BodyBlocks.byClass(name)).as(as + ": class " + name).isPresent();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("a picture is drawn -- a motif and colours -- or a shipped photograph with its credit, never both")
    void picturesAreDrawableOrShipped(DemoContent.Demo demo) throws IOException {
        seed = SEEDS.get(demo);
        for (DemoContent.ImageSeed image : seed.getImages()) {
            if (image.getPhoto() == null) {
                DemoImages.Motif.of(image.getMotif());
                assertThat(image.getColours()).as(image.getKey()).hasSizeGreaterThanOrEqualTo(2)
                        .allSatisfy(colour -> assertThat(colour).matches("#[0-9a-f]{6}"));
                continue;
            }
            assertThat(image.getMotif()).as(image.getKey() + ": a photograph is not drawn").isNull();
            // Where it came from and whose it is: VENDORED.md beside the photographs states the rule.
            assertThat(image.getCredit()).as(image.getKey()).isNotBlank();
            assertThat(image.getNasaId()).as(image.getKey()).isNotBlank();
            assertThat(image.getSource()).as(image.getKey()).isEqualTo("https://images.nasa.gov/details/" + image.getNasaId());
            assertThat(image.getPhoto()).as(image.getKey()).isEqualTo("demo/space/photos/" + image.getKey() + ".jpg");
            java.awt.image.BufferedImage photo = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(DemoPhotos.read(image.getPhoto())));
            // Shipped in the default rule's shape and at least its size, so a default install stores
            // it as it is.
            assertThat(io.pacod.cms.core.services.ImageService.fitsDimensions(photo.getWidth(), photo.getHeight(), 600, 400))
                    .as("%s is %dx%d", image.getKey(), photo.getWidth(), photo.getHeight()).isTrue();
            assertThat(photo.getWidth()).as(image.getKey()).isLessThanOrEqualTo(1500);
        }
        DemoContent.Promotions promotions = seed.getPromotions();
        if (promotions != null) {
            DemoImages.Motif.of(promotions.getImage().getMotif());
            promotions.getRotator().getSlides().forEach(slide -> DemoImages.Motif.of(slide.getMotif()));
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("every shipped photograph is one a seed uses: nothing is carried that nothing shows")
    void everyShippedPhotographIsUsed() throws IOException {
        Set<String> named = new HashSet<>();
        SEEDS.values().forEach(s -> s.getImages().forEach(image -> {
            if (image.getPhoto() != null) {
                named.add(image.getPhoto().substring(image.getPhoto().lastIndexOf('/') + 1));
            }
        }));
        Set<String> shipped = new HashSet<>();
        for (org.springframework.core.io.Resource resource : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:demo/*/photos/*.jpg")) {
            shipped.add(resource.getFilename());
        }
        assertThat(shipped).isNotEmpty().containsExactlyInAnyOrderElementsOf(named);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("the menu group links demo sections; the link page and the promotions name addresses the site serves")
    void addressesResolve(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        if (seed.getMenus().isEmpty()) {
            assertThat(seed.getMenu().getCategories()).isNotEmpty().isSubsetOf(categoryIds());
        } else {
            assertThat(seed.getMenus()).containsKey("main");
            seed.getMenus().values().forEach(items -> items.forEach(SeedIntegrityTest::menuItemResolves));
        }

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
        if (promotions != null) {
            assertThat(promotions.getImage().getPath()).startsWith("/");
            resolves(promotions.getImage().getPath());
            assertThat(promotions.getRotator().getSkeleton()).isIn("sidebar", "top");
            assertThat(promotions.getRotator().getSlides()).isNotEmpty()
                    .extracting(DemoContent.SlideSeed::getKey).doesNotHaveDuplicates();
            promotions.getRotator().getSlides().forEach(slide -> resolves(slide.getPath()));
        }

        // Each advertiser has its page in the seed, and books only what the advertising screens allow.
        assertThat(seed.getPartners()).extracting(DemoContent.PartnerSeed::getId).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).matches("[a-z][a-z0-9-]{1,63}"));
        for (DemoContent.PartnerSeed partner : seed.getPartners()) {
            assertThat(seed.getPages()).extracting(DemoContent.PageSeed::getSlug).as(partner.getId()).contains(partner.getPage());
            assertThat(partner.getPhotos().values()).as(partner.getId()).isSubsetOf(pictureKeys());
            assertThat(partner.getBookings()).as(partner.getId()).isNotEmpty().allSatisfy(booking -> {
                io.pacod.cms.core.data.BannerType type = io.pacod.cms.core.data.BannerType.valueOf(booking.getType());
                io.pacod.cms.core.data.BannerSize size = io.pacod.cms.core.data.BannerSize.valueOf(booking.getSize());
                io.pacod.cms.core.data.BannerPlacement.valueOf(booking.getPlacement());
                booking.getPages().forEach(page ->
                        assertThat(io.pacod.cms.core.data.BannerPage.bookable()).as(page)
                                .contains(io.pacod.cms.core.data.BannerPage.valueOf(page)));
                if (type == io.pacod.cms.core.data.BannerType.HTML5) {
                    assertThat(size).as("an HTML5 frame needs a height").isNotEqualTo(io.pacod.cms.core.data.BannerSize.AUTO);
                    assertThat(booking.getFile()).matches("[a-z0-9-]+\\.html");
                } else {
                    assertThat(booking.getImage()).isNotBlank();
                    assertThat(booking.getAlt()).isNotBlank();
                }
            });
        }
        if (seed.getBrand() != null) {
            assertThat(pictureKeys()).contains(seed.getBrand().getShare());
        }
    }

    private static void menuItemResolves(io.pacod.cms.core.seed.SiteBootstrap.ItemSeed item) {
        assertThat(item.getLabels()).containsKey("en");
        switch (item.getKind()) {
            case CATEGORY -> assertThat(categoryIds()).contains(Integer.valueOf(item.getTarget()));
            case PAGE -> assertThat(pageSlugs()).contains(item.getTarget());
            case URL -> resolves(item.getTarget());
            default -> { }
        }
        item.getChildren().forEach(SeedIntegrityTest::menuItemResolves);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("every layout resolves on the set it is for without an issue, naming sections and addresses that exist")
    void layoutsResolve(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        if (seed.getLayouts().isEmpty()) {
            return;
        }
        assertThat(seed.getLayoutSet()).isEqualTo(demo.templateSet());
        io.pacod.cms.core.design.TemplateSetManifest set = new io.pacod.cms.core.design.TemplateSetCatalog()
                .find(seed.getLayoutSet()).orElseThrow();
        seed.getLayouts().forEach((pageType, regions) -> {
            assertThat(io.pacod.cms.core.design.LayoutResolver.resolve(set, io.pacod.cms.core.design.PageType.valueOf(pageType),
                    DemoContent.toRegions(regions)).issues()).as(pageType).isEmpty();
            regions.values().forEach(sections -> sections.forEach(section -> {
                Map<String, Object> settings = section.getSettings();
                if (settings.get("category") != null) {
                    assertThat(categoryIds()).as(pageType).contains((Integer) settings.get("category"));
                }
                // A list or a ranking always prints its heading bar; without a heading it is empty.
                if ("list".equals(settings.get("style")) || "ranked".equals(settings.get("style"))) {
                    assertThat((String) settings.get("heading")).as(pageType).isNotBlank();
                }
                if (settings.get("url") instanceof String url) {
                    resolves(url);
                }
                if (settings.get("html") instanceof String html) {
                    clean(pageType, html);
                }
            }));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DemoContent.Demo.class)
    @DisplayName("journal issues name articles and a cover the seed has, each number once")
    void issuesResolve(DemoContent.Demo demo) {
        seed = SEEDS.get(demo);
        assertThat(seed.getIssues()).extracting(i -> i.getVolume() + "/" + i.getNumber()).doesNotHaveDuplicates();
        for (DemoContent.IssueSeed issue : seed.getIssues()) {
            assertThat(issue.getArticles()).isNotEmpty().isSubsetOf(articleIds()).doesNotHaveDuplicates();
            assertThat(pictureKeys()).contains(issue.getCover());
            assertThat(issue.getTitles()).containsKey("en");
            issue.getEditorials().values().forEach(html -> clean("issue " + issue.getNumber(), html));
        }
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
            assertThat(path).as("a site address").isIn("/", "/latest", "/issues");
            return;
        }
        String reference = match.group(2);
        switch (match.group(1)) {
            case "article" -> assertThat(articleIds()).as(path).contains(Integer.valueOf(reference));
            case "list" -> assertThat(categoryIds()).as(path).contains(Integer.valueOf(reference));
            default -> assertThat(pageSlugs()).as(path).contains(reference);
        }
    }
}
