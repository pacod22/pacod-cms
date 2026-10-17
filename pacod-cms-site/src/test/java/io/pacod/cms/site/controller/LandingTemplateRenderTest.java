package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.site.render.Regions;
import io.pacod.cms.site.render.RenderedSection;
import java.util.Map;
import io.pacod.cms.site.data.LandingSlot;
import io.pacod.cms.site.data.ListItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders landing.html for real.
 *
 * <p>The other controller tests use a standalone MockMvc, which asserts the view <em>name</em>
 * and never invokes Thymeleaf -- so without this test the landing page and its five fragments
 * would first execute against a running stack, long after they were written. This catches
 * fragment signature mismatches, expressions that blow up on nulls, and property names the
 * view models do not actually expose.
 *
 * <p>No MongoDB and no application context worth the name -- just the template engine over the
 * classpath templates. It is a {@link SpringTemplateEngine} rather than a bare
 * {@code TemplateEngine} on purpose: the Spring variant evaluates expressions with SpEL, which
 * is what actually runs in production. A plain engine would use OGNL and could disagree about
 * property resolution -- notably on the {@code RenderedSection} / {@code LandingSlot} records.
 */
class LandingTemplateRenderTest {

    private SpringTemplateEngine engine;

    @BeforeEach
    void setUp() {
        // The application's own factory: the editorial set's folder, then the shared one, exactly as
        // TemplateSetViewResolver builds it.
        engine = io.pacod.cms.site.render.TemplateSets.engine("editorial",
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
    }

    private static ListItem item(int id, boolean withImage, String embedded) {
        Article article = new Article(id);
        article.setTitle("Title " + id);
        article.setCounter(id * 10);
        article.setDate(LocalDateTime.of(2026, 3, 4, 12, 0));
        article.setEmbedded(embedded);

        if (withImage) {
            ImageMedia image = new ImageMedia(id);
            image.setFilename("image-" + id + ".jpg");
            image.setCaption("Caption " + id);
            article.setImages(List.of(image));
        }

        return new ListItem(article, "bg");
    }

    private static LandingSlot slot(int categoryId, int count) {
        List<ListItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(item(categoryId * 100 + i, true, null));
        }
        return new LandingSlot(categoryId, "Category " + categoryId, items);
    }

    /** An article-list section as PageComposer hands it over: settings checked, slot looked up. */
    private static RenderedSection list(String style, LandingSlot slot) {
        return new RenderedSection("s" + style + slot.categoryId(), "article-list", "articleList",
                Map.of("source", "category", "style", style, "count", slot.items().size(), "offset", 0), slot);
    }

    /** A full front page, as a layout: what the regions render when every widget is there. */
    private static Regions fullModel() {
        LandingSlot feed = slot(1000, 3);
        return new Regions(Map.of(
                "main", List.of(
                        list("hero", new LandingSlot(1000, "", List.of(item(1, true, null)))),
                        // The cards under the hero: the same category, and no heading of their own.
                        list("cards", new LandingSlot(1000, "", feed.items())),
                        list("cards", slot(2, 3)), list("cards", slot(101, 6)),
                        list("cards", slot(3, 3)), list("cards", slot(4, 3))),
                "aside", List.of(list("ranked", slot(100001, 3))),
                "wide", List.of(list("video", slot(10000, 3)),
                        list("list", slot(11, 3)), list("list", slot(10, 3)), list("list", slot(5, 3)))));
    }

    private static Regions only(String region, RenderedSection... sections) {
        return new Regions(Map.of(region, List.of(sections)));
    }

    private static Regions empty() {
        return new Regions(Map.of());
    }

    private static Banner html5Banner(int id, String source, BannerPlacement placement, BannerSize size) {
        Banner banner = new Banner(id);
        banner.setName("Advertiser " + id);
        banner.setType(BannerType.HTML5);
        banner.setSource(source);
        banner.setPlacement(placement);
        banner.setSize(size);
        return banner;
    }

    private static Banner imageBanner(int id, String source, String target) {
        Banner banner = new Banner(id);
        banner.setName("Advertiser " + id);
        banner.setType(BannerType.IMAGE);
        banner.setSource(source);
        banner.setTargetUrl(target);
        banner.setAltText("Advertiser " + id);
        banner.setPlacement(BannerPlacement.SIDEBAR);
        banner.setSize(BannerSize.AUTO);
        return banner;
    }

    /**
     * A web exchange is required, not incidental: the templates use {@code @{/article/{id}}}
     * link expressions, and Thymeleaf refuses to build a context-relative URL without one.
     *
     * <p>Supplies advertising by default. That matters more than it looks: the ad fragments loop
     * over model attributes, so an <em>absent</em> attribute renders an empty column -- and
     * every "does not contain this advertiser" assertion in this class would then pass for the
     * wrong reason. Rendering with banners present is what makes those assertions mean something.
     */
    private String render(Regions landing, String lang) {
        return render(landing, lang,
                List.of(html5Banner(90, "/resources/banners/leaderboard/index.html",
                        BannerPlacement.TOP, BannerSize.TOP)),
                List.of(html5Banner(91, "/resources/banners/square/index.html",
                                BannerPlacement.SIDEBAR, BannerSize.SQUARE),
                        imageBanner(92, "/assets/img/partners/advertiser.gif", "https://advertiser.example/")));
    }

    private String render(Regions landing, String lang,
                          List<Banner> topBanners, List<Banner> sidebarBanners) {
        MockServletContext servletContext = new MockServletContext();
        IWebExchange exchange = JakartaServletWebApplication
                .buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse());

        WebContext context = new WebContext(exchange, java.util.Locale.forLanguageTag(lang));
        context.setVariable("site", io.pacod.cms.site.testing.TestSites.chrome());
        context.setVariable("regions", landing);
        context.setVariable("lang", lang);
        context.setVariable("topBanners", topBanners);
        context.setVariable("sidebarBanners", sidebarBanners);

        return engine.process("landing", context);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            total++;
        }
        return total;
    }

    @Test
    @DisplayName("The landing page renders every widget in its expected shape")
    void rendersAllWidgets() {
        String html = render(fullModel(), "bg");

        // One hero.
        assertThat(count(html, "class=\"lead\"")).isEqualTo(1);
        // Cards: three under the hero, fifteen across the four grids, three in the video panel.
        assertThat(count(html, "class=\"card\"")).isEqualTo(21);
        // Three verticals of three.
        assertThat(count(html, "class=\"stack__item\"")).isEqualTo(9);
        // Most read, which renders without thumbnails and therefore without an image guard.
        assertThat(count(html, "class=\"ranked__item\"")).isEqualTo(3);
        // Four grid headings, three vertical buttons, one video heading. The cards under the
        // hero are the same category as the hero and render with no heading of their own.
        assertThat(count(html, ">Всички статии<")).isEqualTo(8);
    }

    @Test
    @DisplayName("The only stylesheets are the site's own and the web fonts'")
    void loadsOnlyTheSiteStylesheet() {
        String html = render(fullModel(), "bg");

        assertThat(html).contains("/site-assets/site.css");
        // No stylesheet from anywhere else -- a CSS framework, a CDN -- is layered under it.
        assertThat(java.util.regex.Pattern.compile("<link rel=\"stylesheet\"[^>]*href=\"([^\"]*)\"")
                .matcher(html).results().map(m -> m.group(1)).toList())
                .isNotEmpty()
                .allMatch(href -> href.startsWith("/site-assets/")
                        || href.startsWith("https://fonts.googleapis.com/"));
    }

    @Test
    @DisplayName("No script re-fetches the widgets over /web/v1")
    void doesNotLoadAWidgetScript() {
        // A script that fetched the widgets over /web/v1 would overwrite this server-rendered
        // markup; the page names that API nowhere.
        assertThat(render(fullModel(), "bg")).doesNotContain("/web/v1");
    }

    @Test
    @DisplayName("Chrome fragments are resolved")
    void rendersTheSharedChrome() {
        String html = render(fullModel(), "bg");

        assertThat(html).contains("site-nav__items");     // navbar
        assertThat(html).contains("site-footer");         // footer
        assertThat(html).contains("class=\"partner-card\"");    // ad sidebar
        assertThat(html).contains("theme-toggle");        // masthead
        // The website's identity comes from its settings, not from the template.
        assertThat(html).contains(io.pacod.cms.site.testing.TestSites.NAME_BG);
        assertThat(html).contains("href=\"/list/2?lang=bg\"");   // the main menu, from data
    }

    /**
     * The ad column is whatever the model carries: the fragments hardcode no booking.
     *
     * <p>Asserted positively on purpose. The fragments loop over a model attribute, so an absent
     * or empty attribute renders nothing at all: a bare {@code doesNotContain} for an advertiser
     * that was removed would pass whether the mechanism works or is simply broken.
     */
    @Test
    @DisplayName("The advertising is whatever the model supplies, on both surfaces")
    void rendersTheSuppliedBanners() {
        String html = render(fullModel(), "bg");

        assertThat(html).contains("/resources/banners/leaderboard/index.html");   // TOP
        assertThat(html).contains("/resources/banners/square/index.html");        // SIDEBAR, html5
        assertThat(html).contains("/assets/img/partners/advertiser.gif");         // SIDEBAR, image
        assertThat(count(html, "class=\"partner-card\"")).isEqualTo(3);

        // The image banner links through the click counter, not straight at the advertiser.
        assertThat(html).contains("/b/92");
        assertThat(html).doesNotContain("https://advertiser.example/");

        // Each creative frame tells site.js which booking it is; see PublicTemplateRenderTest.
        assertThat(html).contains("data-partner=\"90\"", "data-partner=\"91\"", "data-partner=\"92\"");
    }

    @Test
    @DisplayName("No bookings renders no ad boxes and no empty leaderboard grid")
    void rendersNothingWithoutBanners() {
        String html = render(fullModel(), "bg", List.of(), List.of());

        assertThat(html).doesNotContain("class=\"partner-card\"");
        // The .partner-row wrapper carries padding, so an empty one would leave a gap above the column.
        assertThat(html).doesNotContain("class=\"partner-row\"");
    }

    /**
     * The landing page's half of {@code advertisingCarriesNoBlockerBaitClassNames} in
     * {@code PublicTemplateRenderTest} -- see that test for the full reasoning. In short: EasyList
     * hides {@code .ad-box} and {@code .ad-row} with generic all-domain cosmetic filters, so an ad
     * box under either name is invisible to any reader running a blocker while the server is
     * completely healthy and every assertion in this class passes.
     *
     * <p>This is the busiest route on the site, and it is also the one page here that renders both
     * surfaces from its own template rather than through {@code article-view}.
     */
    @Test
    @DisplayName("No advertising class begins with 'ad-', which ad blockers hide generically")
    void advertisingCarriesNoBlockerBaitClassNames() {
        String html = render(fullModel(), "bg");

        // The positive half: an empty ad column would satisfy the negative assertions on its own.
        assertThat(html).contains("class=\"partner-card\"");
        assertThat(html).contains("class=\"partner-row\"");

        assertThat(html).doesNotContain("class=\"ad-", "class=\"ad ", " ad-box", " ad-row");
    }

    @Test
    @DisplayName("Article links carry the id and the language")
    void buildsArticleLinks() {
        String html = render(fullModel(), "bg");

        assertThat(html).contains("/article/1?lang=bg");
        assertThat(html).contains("/list/2?lang=bg");
    }

    @Test
    @DisplayName("The date label follows the requested language")
    void localizesTheDateLabel() {
        assertThat(render(fullModel(), "en")).contains("Date: ");
        assertThat(render(fullModel(), "bg")).contains("Дата: ");
    }

    @Test
    @DisplayName("An entirely empty model still renders a page")
    void rendersWithNoContent() {
        String html = render(empty(), "bg");

        assertThat(html).contains("site-footer");
        assertThat(count(html, "class=\"lead\"")).isZero();
        assertThat(count(html, "class=\"card\"")).isZero();
        assertThat(count(html, "widget_show_all")).isZero();
    }

    @Test
    @DisplayName("An article with no image is skipped rather than rendered broken")
    void skipsImagelessArticles() {
        LandingSlot grid = new LandingSlot(2, "Category 2",
                List.of(item(21, true, null), item(22, false, null), item(23, true, null)));

        String html = render(only("main", list("cards", grid)), "bg");

        assertThat(html).contains("image-21.jpg").contains("image-23.jpg");
        // The empty filename would otherwise produce src="/assets/img/articles/".
        assertThat(html).doesNotContain("\"/assets/img/articles/\"");
        assertThat(html).doesNotContain("/article/22?lang=bg");
    }

    @Test
    @DisplayName("The hero renders an embedded video when the article has one")
    void rendersEmbeddedHero() {
        String html = render(only("main", list("hero",
                new LandingSlot(1000, "", List.of(item(1, false, "<iframe src=\"video\"></iframe>"))))), "bg");

        assertThat(html).contains("video-container");
        // th:utext, so the markup is not escaped into visible text.
        assertThat(html).contains("<iframe src=\"video\"></iframe>");
    }

    @Test
    @DisplayName("A null view counter renders as zero, not as 'null'")
    void nullCounterRendersAsZero() {
        Article article = new Article(9);
        article.setTitle("No counter");
        ImageMedia image = new ImageMedia(9);
        image.setFilename("image-9.jpg");
        article.setImages(List.of(image));

        LandingSlot grid = new LandingSlot(2, "Category 2", List.of(new ListItem(article, "bg")));
        assertThat(render(only("main", list("cards", grid)), "bg")).doesNotContain(">null<");
    }

    @Test
    @DisplayName("The other section kinds render from their settings alone")
    void rendersTheOtherSectionKinds() {
        Regions regions = new Regions(Map.of(
                "main", List.of(
                        new RenderedSection("t1", "rich-text", "richText",
                                Map.of("heading", "About us", "html", "<p>We write <em>things</em>.</p>"), null),
                        new RenderedSection("c1", "call-to-action", "callToAction",
                                Map.of("heading", "Subscribe", "label", "Sign up", "url", "/p/contact"), null)),
                "aside", List.of(
                        new RenderedSection("q1", "search-box", "searchBox", Map.of(), null),
                        new RenderedSection("f1", "social-links", "socialLinks", Map.of(), null))));

        String html = render(regions, "en");

        assertThat(html).contains("About us", "<p>We write <em>things</em>.</p>");
        assertThat(html).contains("class=\"callout__button\" href=\"/p/contact\"", ">Sign up<");
        assertThat(html).contains("class=\"section-search__form\" action=\"/search\"");
        // The navbar's form keeps its id to itself: one site-search-form on the page.
        assertThat(count(html, "id=\"site-search-form\"")).isEqualTo(1);
        assertThat(html).contains("section-follow__list", "Follow us");
    }

    @Test
    @DisplayName("The wide region renders only when the layout puts something in it")
    void wideRegionOnlyWhenUsed() {
        assertThat(render(only("main", list("cards", slot(2, 3))), "bg")).doesNotContain("region-wide");
        assertThat(render(only("wide", list("video", slot(10000, 3))), "bg")).contains("region-wide");
    }
}
