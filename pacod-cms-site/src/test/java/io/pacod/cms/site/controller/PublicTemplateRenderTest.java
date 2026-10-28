package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.site.data.ArticleListPageable;
import io.pacod.cms.site.data.ArticleModel;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.site.data.LinkTarget;
import io.pacod.cms.site.data.SocialView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the public templates other than landing.html for real: article.html, index.html,
 * list.html and the rest.
 *
 * <p>{@code ArticleTemplateControllerTest} uses a standalone MockMvc, which asserts the view
 * <em>name</em> and never invokes Thymeleaf. The templates sit on shared, parameterised
 * fragments, so a fragment signature mismatch or a property the view model does not expose would
 * otherwise first surface against a running stack.
 *
 * <p>Same engine setup as {@link LandingTemplateRenderTest}, and for the same reason: a
 * {@link SpringTemplateEngine} evaluates with SpEL, which is what production does. A plain
 * engine would use OGNL and could disagree about property resolution.
 */
class PublicTemplateRenderTest {

    private SpringTemplateEngine engine;

    @BeforeEach
    void setUp() {
        // The application's own factory: the editorial set's folder, then the shared one, exactly as
        // TemplateSetViewResolver builds it.
        engine = io.pacod.cms.site.render.TemplateSets.engine("editorial",
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
    }

    /**
     * The locale a render runs in: the model's {@code lang}, which is what LocaleConfig's
     * interceptor makes the request's locale in production; English when there is none.
     */
    private static java.util.Locale localeOf(Map<String, Object> model) {
        Object lang = model.get("lang");
        return java.util.Locale.forLanguageTag(lang instanceof String s && !s.isBlank() ? s : "en");
    }

    /**
     * Renders {@code template} with {@code variables}, defaulting the advertising to a standard
     * pair of bookings.
     *
     * <p>The default matters more than it looks. The ad fragments loop over model attributes,
     * so an <em>absent</em> attribute renders an empty column -- and every "this page does not
     * carry that booking" assertion in this class would pass whether the mechanism works or is
     * simply broken. Supplying banners unless a test says otherwise is what keeps those
     * assertions honest; a test that cares passes its own lists.
     */
    private String render(String template, Map<String, Object> variables) {
        Map<String, Object> model = new java.util.HashMap<>(variables);
        // What SiteChromeAdvice adds to every real render: the website's settings and menus.
        model.putIfAbsent("site", io.pacod.cms.site.testing.TestSites.chrome());
        // What PageComposer adds to every page with content of its own, when no layout says more.
        model.putIfAbsent("regions", io.pacod.cms.site.render.Regions.primaryOnly());
        model.putIfAbsent("topBanners", List.of(
                banner(90, BannerType.HTML5, "/resources/banners/leaderboard/index.html", null)));
        model.putIfAbsent("sidebarBanners", List.of(
                banner(91, BannerType.HTML5, "/resources/banners/square/index.html", null),
                banner(92, BannerType.IMAGE, "/assets/img/partners/advertiser.gif", "https://advertiser.example/")));

        MockServletContext servletContext = new MockServletContext();
        IWebExchange exchange = JakartaServletWebApplication
                .buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse());

        WebContext context = new WebContext(exchange, localeOf(model));
        model.forEach(context::setVariable);

        return engine.process(template, context);
    }

    private static Banner banner(int id, BannerType type, String source, String target) {
        Banner banner = new Banner(id);
        banner.setName("Advertiser " + id);
        banner.setType(type);
        banner.setSource(source);
        banner.setTargetUrl(target);
        banner.setAltText("Advertiser " + id);
        // Placement is not read by the fragment -- BannerService has already decided which list a
        // banner belongs in by the time the template sees it.
        banner.setPlacement(BannerPlacement.SIDEBAR);
        banner.setSize(BannerSize.AUTO);
        return banner;
    }

    private static Article article(int id, boolean withImage, String embedded) {
        Article article = new Article(id);
        article.setTitle("Заглавие " + id);
        article.setDescription("Кратко описание " + id);
        article.setText("<p>Тяло на статията</p>");
        article.setCounter(42);
        article.setDate(LocalDateTime.of(2026, 3, 4, 12, 0));
        article.setEmbedded(embedded);

        Author author = new Author();
        author.setFirstname("Иван");
        author.setLastname("Петров");
        article.setAuthors(List.of(author));

        Category category = new Category();
        category.setId(9);
        category.setName("Култура");
        article.setCategories(List.of(category));

        if (withImage) {
            ImageMedia image = new ImageMedia(id);
            image.setFilename("image-" + id + ".jpg");
            image.setCaption("Описание на снимката");
            image.setContent("image/jpeg");
            article.setImages(List.of(image));
        }

        return article;
    }

    private static ArticleListPageable list(int totalItems) {
        List<ListItem> items = new java.util.ArrayList<>(totalItems);
        for (int i = 0; i < totalItems; i++) {
            items.add(new ListItem(article(100 + i, true, null), "bg"));
        }

        // @Builder, and the all-args constructor Lombok generates alongside it is
        // package-private -- the builder is the only way in from here.
        return ArticleListPageable.builder()
                .id(9)
                .name("Култура")
                .page(new PageImpl<>(items, PageRequest.of(0, 20), totalItems))
                .build();
    }

    /* ---------------------------------------------------------------- search ----------- */

    /**
     * A results page renders its rows, its count and a pager that carries the query.
     *
     * <p>The pager assertion is the one that matters: every link has to carry {@code q=}, or page
     * two of a search is page two of nothing — it would answer 200 with the "type a word" state
     * and look like the site had lost the results.
     */
    @Test
    @DisplayName("Search renders its results, and every pager link carries the query")
    void rendersSearchResults() {
        String html = render("search", Map.of(
                "lang", "bg", "q", "фестивал", "searched", true, "results", list(60)));

        assertThat(html).contains("value=\"фестивал\"");       // the box keeps what was typed
        assertThat(html).contains("60");                       // the count
        assertThat(html).contains("class=\"list-row\"");       // the rows
        assertThat(html).contains("class=\"pager\"");

        // Stated as the invariant rather than as a count of links. Thymeleaf URL-encodes the
        // Cyrillic so the spelling of the value is not worth asserting, and counting
        // `class="pager__item"` undercounts by one: the current page renders
        // `class="pager__item pager__item--current"` and the closing quote does not match.
        assertThat(count(html, "/search?q=")).isGreaterThan(2);   // prev, next, and the numbers
        // A pager link that lost the query. `action="/search"` on the two forms is not an href,
        // so this cannot fire on them.
        assertThat(html).doesNotContain("href=\"/search\"");
    }

    /**
     * "Not searched yet" and "nothing matched" are different states and must not render the same.
     *
     * <p>Telling a reader who has typed nothing that nothing matched is the same class of wrong
     * answer as the monitoring chart drawing a flat zero line for a period with no data.
     */
    @Test
    @DisplayName("An unsearched page invites a query; an empty result set reports no matches")
    void rendersSearchEmptyStatesDistinctly() {
        String fresh = render("search", Map.of("lang", "bg", "q", "", "searched", false));
        assertThat(fresh).contains("Въведете дума");
        assertThat(fresh).doesNotContain("Няма намерени материали");
        // The form is the point of the page and must render before any search has happened.
        assertThat(fresh).contains("class=\"search-form\"");
        assertThat(fresh).contains("name=\"q\"");

        String none = render("search", Map.of(
                "lang", "bg", "q", "щщщ", "searched", true,
                "results", ArticleListPageable.builder()
                        .page(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0)).build()));
        assertThat(none).contains("Няма намерени материали");
        assertThat(none).doesNotContain("Въведете дума");
        assertThat(none).doesNotContain("class=\"pager\"");
    }

    /**
     * The Google screen's whole reason for being a template rather than a {@code Page} document.
     *
     * <p>TinyMCE strips {@code <script>} on save and {@code Page} has no {@code previousText}, so
     * a body holding this tag would be one studio visit away from deleting Google search with a
     * 200 response. Pinned by content, not just by the tag: the {@code cx} identifies the site's
     * own search engine, and a page carrying somebody else's is not a failure anything else sees.
     */
    @Test
    @DisplayName("The Google screen carries the CSE script and its containers")
    void rendersGoogleSearch() {
        String html = render("search-google", Map.of("lang", "bg"));

        // The engine is the website's own, from its settings -- never one written into the page.
        assertThat(html).contains("cse.google.com/cse.js?cx=" + io.pacod.cms.site.testing.TestSites.CSE_CX);
        assertThat(html).contains("class=\"gcse-searchbox\"");
        assertThat(html).contains("class=\"gcse-searchresults\"");
        // With scripting off those two divs stay empty, so the page has to point somewhere that
        // works rather than render as a blank column.
        assertThat(html).contains("<noscript>");
        assertThat(html).contains("/search");
    }

    /**
     * The navbar's one search box reaches both destinations, and neither control needs a script.
     *
     * <p>All three assertions cover a way the term can be silently dropped. A ⌕ that is a
     * decorative {@code <span>} looks like a search button and does nothing when clicked. A Google
     * control that is an {@code <a href="/search-google">} carries no query at all, so typing a
     * word and clicking G loses it. And a box that does not echo {@code q} is blank on the page
     * you land on.
     *
     * <p>Rendered through a page rather than the fragment directly, because the navbar is only
     * ever seen inside one and {@code form=} reaching out of the form is the kind of thing a
     * refactor breaks by moving an element.
     */
    @Test
    @DisplayName("The navbar search box submits to both search screens and echoes the query")
    void navbarSearchReachesBothScreens() {
        String html = render("search", Map.of("lang", "bg", "q", "фестивал", "searched", true,
                "results", ArticleListPageable.builder()
                        .page(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0)).build()));

        // The form the Google button reaches from outside itself.
        assertThat(html).contains("id=\"site-search-form\"");
        // The magnifier submits rather than decorating. It must also be the FIRST submit button,
        // or Enter would go to Google instead of to the site's own search.
        assertThat(html).contains("<button class=\"site-search__icon\" type=\"submit\"");
        assertThat(html.indexOf("site-search__icon"))
                .as("the magnifier must precede the Google button, so Enter submits to /search")
                .isLessThan(html.indexOf("formaction=\"/search-google\""));
        // The Google control carries the same box's term instead of being a bare link.
        assertThat(html).contains("form=\"site-search-form\"");
        assertThat(html).contains("formaction=\"/search-google\"");
        assertThat(html).doesNotContain("href=\"/search-google\"");
        // The box keeps what was typed.
        assertThat(html).contains("id=\"site-search-input\"");
        assertThat(html).contains("value=\"фестивал\"");

        // And it submits `lang`, so the results come back in the language the reader was reading.
        assertThat(html).contains("<input type=\"hidden\" name=\"lang\" value=\"bg\"/>");
    }

    /**
     * The chrome is translated on the server, from {@code messages*.properties}: no page loads a
     * translation script, and none carries a {@code data-i18n} attribute for one to fill in.
     */
    @Test
    @DisplayName("No page loads a client-side translation script")
    void chromeIsTranslatedOnTheServer() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()), "lang", "bg"));

        assertThat(html).doesNotContain("data-i18n");
        // Of the site's own scripts, the page loads site.js alone.
        assertThat(html).contains("<script defer src=\"/site-assets/site.js\"></script>")
                .doesNotContainPattern("<script[^>]*src=\"/site-assets/(?!site\\.js\")");
    }

    /**
     * Prism comes only with a body that holds a code sample, on every set and every view that
     * renders a body -- an article, the latest article, a standing page -- and in manual mode, run
     * over the body alone. Any other page loads site.js alone ({@link #chromeIsTranslatedOnTheServer}),
     * so its 34 KB are not every reader's.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"editorial", "minimal", "magazine"})
    @DisplayName("The code highlighter loads only where the body holds a <pre>")
    void codeHighlighterOnlyWithCode(String set) {
        engine = io.pacod.cms.site.render.TemplateSets.engine(new io.pacod.cms.core.design.TemplateSetCatalog().chain(set),
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
        String prism = "<script defer data-manual src=\"/site-assets/vendor/prism/prism.min.js\"></script>";
        Article withCode = article(7, true, null);
        withCode.setText("<p>Пуснете:</p><pre class=\"language-bash\"><code>make verify</code></pre>");
        io.pacod.cms.core.data.Page about = new io.pacod.cms.core.data.Page("about");

        Map<String, String> withPre = Map.of(
                "article", render("article", Map.of("article", new ArticleModel(withCode, "en", List.of()), "lang", "en")),
                "index", render("index", Map.of("article", new ArticleModel(withCode, "en", List.of()), "lang", "en", "tags", List.of())),
                // Older bodies may spell it in capitals.
                "page", render("page", Map.of("lang", "en", "page", new io.pacod.cms.site.services.PageService.Rendered(
                        about, "en", false, false, null, "About", "<PRE>make verify</PRE>", null))));
        withPre.forEach((view, html) -> assertThat(html).as("%s on %s", view, set)
                .contains(prism, "highlightAllUnder(body)", "class=\"article-body")
                .containsSubsequence("class=\"article-body", prism, "<script defer src=\"/site-assets/site.js\"></script>"));

        Map<String, String> without = Map.of(
                "article", render("article", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()), "lang", "en")),
                "page", render("page", Map.of("lang", "en", "page", new io.pacod.cms.site.services.PageService.Rendered(
                        about, "en", false, false, null, "About", "<p>Only <code>inline</code> code, and &lt;pre&gt; as text.</p>", null))));
        without.forEach((view, html) -> assertThat(html).as("%s on %s", view, set)
                .doesNotContain("prism", "highlightAllUnder").contains("/site-assets/site.js"));
    }

    /**
     * A page section wherever a layout may put it -- every region of every page type, on every set,
     * magazine's full-width {@code wide} on the home page among them -- is the standing page's body
     * in the language the page itself would serve, in {@code .article-body} (its blocks styled as on
     * {@code /p/{slug}}) and without its title; a page with nothing to show is nothing at all.
     * Composed for real, PageComposer over PageService, so the language is chosen where production
     * chooses it.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"editorial", "minimal", "magazine"})
    @DisplayName("A page section shows its page's body in every region of every page, in the reader's language")
    void pageSectionsInEveryRegion(String set) {
        io.pacod.cms.core.design.TemplateSetCatalog catalog = new io.pacod.cms.core.design.TemplateSetCatalog();
        org.springframework.context.MessageSource messages = new io.pacod.cms.site.config.ThymeleafConfig().messageSource();
        engine = io.pacod.cms.site.render.TemplateSets.engine(catalog.chain(set), messages, false, List.of());

        io.pacod.cms.core.data.Page platform = new io.pacod.cms.core.data.Page("platform");
        platform.putTitle("en", "The platform");
        platform.putTitle("de", "Die Plattform");
        platform.putBody("en", "<p class=\"body-lead\">One platform.</p><div class=\"body-columns\"><div><h3>Gateway</h3></div></div>");
        platform.putBody("de", "<p class=\"body-lead\">Eine Plattform.</p><div class=\"body-columns\"><div><h3>Gateway</h3></div></div>");
        io.pacod.cms.core.data.Page titled = new io.pacod.cms.core.data.Page("titled");
        titled.putTitle("en", "A title alone");
        io.pacod.cms.site.services.PageReader reader = org.mockito.Mockito.mock(io.pacod.cms.site.services.PageReader.class);
        org.mockito.Mockito.when(reader.findAll()).thenReturn(List.of(platform, titled));

        io.pacod.cms.core.design.TemplateSetManifest manifest = catalog.find(set).orElseThrow();
        io.pacod.cms.site.render.LayoutReader layouts = org.mockito.Mockito.mock(io.pacod.cms.site.render.LayoutReader.class);
        for (io.pacod.cms.core.design.PageType type : io.pacod.cms.core.design.PageType.values()) {
            Map<String, List<io.pacod.cms.core.design.Section>> stored = new java.util.LinkedHashMap<>();
            for (String region : manifest.spec(type).regions()) {
                stored.put(region, List.of(
                        new io.pacod.cms.core.design.Section(region + "-p", "page", Map.of("page", "platform")),
                        new io.pacod.cms.core.design.Section(region + "-t", "page", Map.of("page", "titled")),
                        new io.pacod.cms.core.design.Section(region + "-m", "page", Map.of("page", "missing"))));
            }
            org.mockito.Mockito.when(layouts.published(type)).thenReturn(new io.pacod.cms.site.render.LayoutReader.Stored(stored));
        }
        io.pacod.cms.site.render.PageComposer composer = new io.pacod.cms.site.render.PageComposer(catalog, layouts,
                org.mockito.Mockito.mock(io.pacod.cms.site.services.CategoryWidgetReader.class),
                new io.pacod.cms.site.services.PageService(reader,
                        org.mockito.Mockito.mock(io.pacod.cms.site.services.LanguageReader.class)), messages);
        // A Bulgarian website: German is asked for and there; French is not, and English is served.
        io.pacod.cms.core.tenant.Tenant tenant = new io.pacod.cms.core.tenant.Tenant("alpha", "org_a", "site_alpha", "bg",
                java.time.ZoneId.of("UTC"), set);

        for (String lang : List.of("de", "fr")) {
            String served = lang.equals("de") ? "de" : "en";
            String body = lang.equals("de") ? "<p class=\"body-lead\">Eine Plattform.</p>" : "<p class=\"body-lead\">One platform.</p>";
            io.pacod.cms.core.data.Page about = new io.pacod.cms.core.data.Page("about");
            Map<io.pacod.cms.core.design.PageType, Map.Entry<String, Map<String, Object>>> views = Map.of(
                    io.pacod.cms.core.design.PageType.HOME, Map.entry("landing", Map.of()),
                    io.pacod.cms.core.design.PageType.ARTICLE, Map.entry("article",
                            Map.of("article", new ArticleModel(article(7, true, null), lang, List.of()))),
                    io.pacod.cms.core.design.PageType.LATEST, Map.entry("index",
                            Map.of("article", new ArticleModel(article(7, true, null), lang, List.of()), "tags", List.of())),
                    io.pacod.cms.core.design.PageType.CATEGORY, Map.entry("list", Map.of("list", list(30))),
                    io.pacod.cms.core.design.PageType.PAGE, Map.entry("page", Map.of("page", new io.pacod.cms.site.services.PageService.Rendered(
                            about, lang, false, false, null, "About", "<p>About us.</p>", null))),
                    io.pacod.cms.core.design.PageType.SEARCH, Map.entry("search", Map.of("q", "x", "searched", true,
                            "results", ArticleListPageable.builder().page(org.springframework.data.domain.Page.empty()).build())));
            views.forEach((type, view) -> {
                io.pacod.cms.site.render.Regions regions = io.pacod.cms.core.tenant.TenantContext.callAs(tenant,
                        () -> composer.compose(type, lang));
                Map<String, Object> model = new java.util.HashMap<>(view.getValue());
                model.put("lang", lang);
                model.put("regions", regions);
                String html = render(view.getKey(), model);
                int expected = manifest.spec(type).regions().size();
                String as = view.getKey() + " on " + set + " in " + lang;
                assertThat(count(html, body)).as(as).isEqualTo(expected);
                assertThat(count(html, "--page\" lang=\"" + served + "\">")).as(as).isEqualTo(expected);
                assertThat(html).as(as).containsSubsequence("--page\" lang=\"" + served + "\">", "<div class=\"article-body\">" + body)
                        .contains("<div class=\"body-columns\"><div><h3>Gateway</h3></div></div>")
                        .doesNotContain("The platform", "Die Plattform", "A title alone", "??");
            });
        }

        // The home page's full width, below the columns, on the sets that have it.
        io.pacod.cms.site.render.Regions home = io.pacod.cms.core.tenant.TenantContext.callAs(tenant,
                () -> composer.compose(io.pacod.cms.core.design.PageType.HOME, "de"));
        String landing = render("landing", Map.of("lang", "de", "regions", home));
        if (set.equals("magazine")) {
            assertThat(landing).containsSubsequence("class=\"layout__aside\"", "class=\"mz-wide\"", "Eine Plattform.");
        } else if (set.equals("editorial")) {
            assertThat(landing).containsSubsequence("class=\"layout__aside\"", "region-wide", "Eine Plattform.");
        }
    }

    /**
     * Every public template renders in every language the site ships chrome for, with no
     * unresolved key -- Thymeleaf renders a missing one as {@code ??key_xx??} rather than failing,
     * which no other assertion here would notice.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "bg", "de", "fr", "es", "it", "ru", "pt"})
    @DisplayName("Every template renders its chrome in every bundled language, with no missing key")
    void everyTemplateRendersInEveryLanguage(String lang) {
        Map<String, String> chrome = Map.of(
                "article", render("article", Map.of("article", new ArticleModel(article(7, true, null), lang, List.of()), "lang", lang)),
                "index", render("index", Map.of("article", new ArticleModel(article(7, true, null), lang, List.of()), "lang", lang, "tags", List.of())),
                "list", render("list", Map.of("list", list(30), "lang", lang)),
                "search", render("search", Map.of("lang", lang, "q", "x", "searched", true,
                        "results", ArticleListPageable.builder().page(org.springframework.data.domain.Page.empty()).build())),
                "search-google", render("search-google", Map.of("lang", lang, "q", "")),
                "error", renderBare("error", Map.of("status", 404, "lang", lang)),
                "social", renderSocial(Map.of("social", socialView(lang, SocialSource.DIRECT, List.of()))));
        chrome.forEach((template, html) -> assertThat(html).as(template + " in " + lang).doesNotContain("??"));

        String expected = new io.pacod.cms.site.config.ThymeleafConfig().messageSource()
                .getMessage("nav.search", null, java.util.Locale.forLanguageTag(lang));
        assertThat(chrome.get("article")).contains("aria-label=\"" + expected + "\"");
    }

    /**
     * Every view, on every installed template set -- each set's engine built as production builds
     * it, with the set's chain of folders ({@code TemplateSetCatalog.chain}) -- with no key left
     * unresolved, each set's stylesheet linked, and each set's shape: editorial and magazine have an
     * aside, minimal does not.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"editorial", "minimal", "magazine"})
    @DisplayName("Every view renders on every template set")
    void everyViewRendersOnEverySet(String set) {
        engine = io.pacod.cms.site.render.TemplateSets.engine(new io.pacod.cms.core.design.TemplateSetCatalog().chain(set),
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
        io.pacod.cms.site.render.Regions home = new io.pacod.cms.site.render.Regions(Map.of("main", List.of(
                new io.pacod.cms.site.render.RenderedSection("h", "article-list", "articleList",
                        Map.of("style", "hero", "source", "latest", "count", 1, "offset", 0),
                        new io.pacod.cms.site.data.LandingSlot(null, "", List.of(new io.pacod.cms.site.data.ListItem(article(7, true, null), "en")))),
                new io.pacod.cms.site.render.RenderedSection("l", "article-list", "articleList",
                        Map.of("style", "cards", "source", "latest", "count", 2, "offset", 1),
                        new io.pacod.cms.site.data.LandingSlot(null, "Latest", List.of(new io.pacod.cms.site.data.ListItem(article(8, true, null), "en")))))));
        Map<String, String> views = Map.of(
                "landing", render("landing", Map.of("lang", "en", "regions", home)),
                "article", render("article", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()), "lang", "en")),
                "index", render("index", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()), "lang", "en", "tags", List.of())),
                "list", render("list", Map.of("list", list(30), "lang", "en")),
                "search", render("search", Map.of("lang", "en", "q", "x", "searched", true,
                        "results", ArticleListPageable.builder().page(org.springframework.data.domain.Page.empty()).build())),
                "search-google", render("search-google", Map.of("lang", "en", "q", "")),
                "error", renderBare("error", Map.of("status", 404, "lang", "en")),
                "issues", render("issues", issuesModel("en")),
                "issue", render("issue", issueModel("en")));
        views.forEach((view, html) -> {
            assertThat(html).as("%s on %s", view, set).doesNotContain("??").contains("/site-assets/site.css");
            if (!view.equals("error") && !view.equals("search-google")) {
                if (set.equals("minimal")) {
                    assertThat(html).as("%s on %s", view, set).contains("/site-assets/minimal.css").doesNotContain("layout__aside");
                } else if (set.equals("magazine")) {
                    assertThat(html).as("%s on %s", view, set).contains("/site-assets/magazine.css", "layout__aside", "mz-header")
                            .doesNotContain("minimal.css");
                } else {
                    assertThat(html).as("%s on %s", view, set).contains("layout__aside").doesNotContain("minimal.css")
                            .doesNotContain("magazine.css");
                }
            }
        });
        assertThat(views.get("landing")).contains("/article/7?lang=en", "/article/8?lang=en");
    }

    /**
     * The magazine set's own promises, each of which would fail silently: a class name a blocker's
     * generic rules hide (Fanboy's social list hid site.css's footer profiles until this set renamed
     * them), a creative frame that loses its booking id or gains a sandbox (its clicks stop
     * counting), the lead picture without its credit, the cover story, the reading time and the
     * author box.
     */
    @Test
    @DisplayName("the magazine set: no blocker-bait class, counted creative frames, the credit under the lead picture")
    void magazineSetGuards() {
        engine = io.pacod.cms.site.render.TemplateSets.engine(new io.pacod.cms.core.design.TemplateSetCatalog().chain("magazine"),
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
        Article featured = article(7, true, null);
        featured.getImages().get(0).setCaption("Tessaly backlit. Image: NASA/JPL-Caltech/Space Science Institute.");
        featured.getAuthors().get(0).setTitle("Destinations editor");
        featured.getAuthors().get(0).setDescription("Has crossed the Reach eleven times.");
        io.pacod.cms.site.data.LandingSlot cover = new io.pacod.cms.site.data.LandingSlot(null, "",
                List.of(new ListItem(featured, "en")));
        io.pacod.cms.site.render.Regions home = new io.pacod.cms.site.render.Regions(Map.of(
                "top", List.of(new io.pacod.cms.site.render.RenderedSection("c", "article-list", "articleList",
                        Map.of("style", "hero", "source", "latest", "count", 1, "offset", 0), cover)),
                "main", List.of(new io.pacod.cms.site.render.RenderedSection("l", "article-list", "articleList",
                        Map.of("style", "cards", "source", "latest", "count", 6, "offset", 1, "heading", "Latest"),
                        new io.pacod.cms.site.data.LandingSlot(null, "Latest", list(5).getPage().getContent())))));
        Map<String, String> views = Map.of(
                "landing", render("landing", Map.of("lang", "en", "regions", home)),
                "article", render("article", Map.of("article", new ArticleModel(featured, "en", List.of()), "lang", "en")),
                "list", render("list", Map.of("list", list(8), "lang", "en")));

        java.util.regex.Pattern classes = java.util.regex.Pattern.compile("class=\"([^\"]*)\"");
        views.forEach((view, html) -> {
            java.util.regex.Matcher found = classes.matcher(html);
            while (found.find()) {
                for (String name : found.group(1).split("\\s+")) {
                    assertThat(name).as(view).doesNotStartWith("ad-").doesNotStartWith("sponsor")
                            .doesNotStartWith("promo").doesNotStartWith("social-").doesNotStartWith("share-")
                            .doesNotStartWith("follow-").isNotEqualTo("site-footer__social");
                }
            }
            assertThat(html).as(view).contains("data-partner=\"90\"", "data-partner=\"91\"", "/site-assets/magazine.css")
                    .doesNotContain("sandbox", "??");
        });
        assertThat(views.get("landing")).contains("mz-cover", "Cover story", "/article/7?lang=en", "mz-grid--mosaic");
        assertThat(views.get("article"))
                .contains("<figcaption class=\"shell mz-hero__caption\">Tessaly backlit. Image: NASA/JPL-Caltech/Space Science Institute.</figcaption>",
                        "1 min read", "About the author", "Has crossed the Reach eleven times.", "Destinations editor");
        assertThat(views.get("list")).contains("mz-grid--lead", "Култура");
    }

    private static io.pacod.cms.site.services.IssueReader.IssueView issueView() {
        io.pacod.cms.core.data.JournalIssue issue = new io.pacod.cms.core.data.JournalIssue();
        issue.setId(3);
        issue.setVolume(2);
        issue.setNumber(5);
        issue.setYear(2026);
        issue.setMonth(10);
        return new io.pacod.cms.site.services.IssueReader.IssueView(issue, "Есенен брой", "<p>Уводни думи.</p>",
                "Вестник", "1234-567X", "Октомври", "/assets/img/articles/cover.jpg");
    }

    private static Map<String, Object> issuesModel(String lang) {
        return Map.of("lang", lang, "views", List.of(issueView()),
                "issues", new org.springframework.data.domain.PageImpl<>(List.of(issueView().issue())));
    }

    private static Map<String, Object> issueModel(String lang) {
        return Map.of("lang", lang, "view", issueView(),
                "entries", List.of(
                        new io.pacod.cms.site.services.IssueReader.Entry(12, "Първа <em>статия</em>", "Иван Петров", 0,
                                "Какво намерихме на брега.", "Пътувания", "first.jpg", "Брегът на залез"),
                        new io.pacod.cms.site.services.IssueReader.Entry(14, "Втора статия", "", 0)),
                "pdfs", Map.of("bg", "/resources/pdfs/issues/bg/issue-3.pdf"));
    }

    @Test
    @DisplayName("The issue pages show the archive, one issue's contents and its PDFs, in every language")
    void issuePages() {
        for (String lang : List.of("bg", "en", "de", "fr", "es", "it")) {
            assertThat(render("issues", issuesModel(lang))).as(lang).doesNotContain("??").contains("href=\"/issues/3?lang=" + lang + "\"");
            assertThat(render("issue", issueModel(lang))).as(lang).doesNotContain("??");
        }
        String issue = render("issue", issueModel("bg"));
        assertThat(issue).contains("Том 2, брой 5", "Есенен брой", "ISSN 1234-567X", "Уводни думи.",
                "href=\"/article/12?lang=bg\"", "Първа <em>статия</em>", "href=\"/resources/pdfs/issues/bg/issue-3.pdf\"");
        // The contents are a numbered list of their own: never .list-row, whose thumbnail column
        // squeezed an entry without one into 160 px. The picture, section and summary show where
        // an entry has them, and nothing stands in where it has not.
        assertThat(issue).contains("class=\"issue-contents\"", ">01<", ">02<", "Пътувания", "Какво намерихме на брега.",
                        "src=\"/assets/img/articles/first.jpg\"", "alt=\"Брегът на залез\"", "Втора статия")
                .doesNotContain("list-row");
        assertThat(issue.split("issue-contents__media", -1)).hasSize(2);
        assertThat(issue.split("issue-contents__byline", -1)).hasSize(2);
        String empty = render("issues", Map.of("lang", "en", "views", List.of(),
                "issues", org.springframework.data.domain.Page.empty()));
        assertThat(empty).contains("empty-note").doesNotContain("pager__item");
    }

    @Test
    @DisplayName("An issue's printed front: the cover, the editorial, and the contents with the pages it is given")
    void issuePrintFront() {
        String html = render("issue-print-front", Map.of("lang", "bg", "view", issueView(),
                "entries", List.of(new io.pacod.cms.site.services.IssueReader.Entry(12, "Първа", "Иван Петров", 3),
                        new io.pacod.cms.site.services.IssueReader.Entry(14, "Втора", "", 9))));
        assertThat(html).contains("class=\"issue-cover\"", "Вестник", "ISSN 1234-567X", "Том 2, брой 5", "Октомври 2026",
                        "class=\"issue-editorial\"", "Съдържание", "/site-assets/print.css")
                .doesNotContain("??").doesNotContain("pacod-theme").doesNotContain("site-nav__items")
                .doesNotContain("partner-card");
        assertThat(html).containsSubsequence("Първа", ">3<", "Втора", ">9<");
    }

    @Test
    @DisplayName("A published theme is linked after the set's stylesheet, by its version; none, no link")
    void themeStylesheetIsLinked() {
        String themed = render("article", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()),
                "lang", "en", "theme", new io.pacod.cms.site.render.ThemeReader.Stylesheet(":root{--accent:#123456;}", "abc123")));
        assertThat(themed).contains("href=\"/theme.css?v=abc123\"");
        assertThat(themed.indexOf("/site-assets/site.css")).isLessThan(themed.indexOf("/theme.css"));
        String plain = render("article", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()),
                "lang", "en", "theme", new io.pacod.cms.site.render.ThemeReader.Stylesheet("", "")));
        assertThat(plain).doesNotContain("/theme.css");
    }

    @Test
    @DisplayName("an image's alt text is the reader's language's, else the caption")
    void altTextFollowsTheLanguage() {
        Article withAlt = article(7, true, null);
        io.pacod.cms.core.data.ImageMedia first = withAlt.getImages().get(0);
        first.setCaption("Caption");
        first.setAlts(List.of(new io.pacod.cms.core.data.TextItem("Ein Garten", "de")));
        assertThat(render("article", Map.of("article", new ArticleModel(withAlt, "de", List.of()), "lang", "de")))
                .contains("alt=\"Ein Garten\"");
        assertThat(render("article", Map.of("article", new ArticleModel(withAlt, "fr", List.of()), "lang", "fr")))
                .contains("alt=\"Caption\"").doesNotContain("Ein Garten");
    }

    @Test
    @DisplayName("the PDF link appears only once the studio has made the file")
    void pdfLinkOnlyWhenItExists() {
        Map<String, Object> page = Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()), "lang", "en");
        assertThat(render("article", page)).doesNotContain("/resources/pdfs/").doesNotContain(">PDF</a>");
        Map<String, Object> withPdf = new java.util.HashMap<>(page);
        withPdf.put("pdf", "/resources/pdfs/articles/en/article-7.pdf");
        assertThat(render("article", withPdf)).contains("href=\"/resources/pdfs/articles/en/article-7.pdf\"");
    }

    /**
     * The studio's draft preview, on every set and on {@code /social}, which has a head of its
     * own: the token for preview.js and the draft theme by the token, never by version -- and,
     * with the chrome {@code withoutTracking}, no tag manager, Facebook SDK or verification meta,
     * and no HTML5 creative's frame, which may load its advertiser's tags.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"editorial", "minimal", "magazine"})
    @DisplayName("a preview carries its token and the draft theme, and tells no third party")
    void previewHead(String set) {
        engine = io.pacod.cms.site.render.TemplateSets.engine(new io.pacod.cms.core.design.TemplateSetCatalog().chain(set),
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
        Map<String, Object> preview = Map.of(
                "preview", new io.pacod.cms.site.render.SitePreview.Preview("p1.alpha.-.9.mac", null),
                "site", io.pacod.cms.site.testing.TestSites.chrome().withoutTracking(),
                "theme", new io.pacod.cms.site.render.ThemeReader.Stylesheet(":root{--accent:#123456;}", "abc123")
                        .forPreview("p1.alpha.-.9.mac"));
        Map<String, Object> page = new java.util.HashMap<>(preview);
        page.putAll(Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()), "lang", "en"));
        Map<String, Object> social = new java.util.HashMap<>(preview);
        social.put("social", socialView("en", SocialSource.DIRECT, List.of()));

        for (String html : List.of(render("article", page), renderSocial(social))) {
            assertThat(html)
                    .doesNotContain("src=\"/resources/banners/")
                    .contains("name=\"pacod-preview\" content=\"p1.alpha.-.9.mac\"", "/site-assets/preview.js",
                            "href=\"/theme.css?_preview=p1.alpha.-.9.mac\"", "Draft preview", "content=\"noindex, nofollow\"")
                    .doesNotContain("/theme.css?v=", "googletagmanager", "connect.facebook.net", "fb:app_id",
                            "google-site-verification", "msvalidate.01");
        }
        // An HTML5 booking is its box and name, not a frame: a creative may load its advertiser's tags.
        assertThat(render("article", page)).contains("partner-card__stand-in");
        // A reader's page links none of it, and frames its bookings.
        String reader = render("article", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()), "lang", "en"));
        assertThat(reader).doesNotContain("pacod-preview", "preview.js", "_preview", "partner-card__stand-in")
                .contains("src=\"/resources/banners/square/index.html\"");
    }

    /**
     * On every other page the box is empty, because {@code q} is not in those models.
     *
     * <p>Pinned because {@code th:value="${q}"} silently renders nothing for a null, which is the
     * behaviour wanted here -- and is also exactly what it would do if the attribute were reading
     * the wrong name, so the positive case above is what proves it works at all.
     */
    @Test
    @DisplayName("The navbar box renders empty on a page that carries no query")
    void navbarSearchIsEmptyElsewhere() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()), "lang", "bg"));

        assertThat(html).contains("id=\"site-search-input\"");
        assertThat(html).doesNotContain("value=\"фестивал\"");
        // The controls are still there and still wired; only the value is absent.
        assertThat(html).contains("formaction=\"/search-google\"");
    }

    /** Both screens are ordinary public pages: shared chrome, and the ad columns from the model. */
    @Test
    @DisplayName("Both search screens carry the shared chrome and the advertising")
    void searchScreensCarryChromeAndAdvertising() {
        for (String view : List.of("search", "search-google")) {
            String html = render(view, Map.of("lang", "bg", "q", "", "searched", false));

            assertThat(html).as("%s navbar", view).contains("site-nav__items");
            assertThat(html).as("%s footer", view).contains("site-footer");
            assertThat(html).as("%s advertising", view).contains("class=\"partner-card\"");
            assertThat(html).as("%s no blocker-bait class", view)
                    .doesNotContain("class=\"ad-", " ad-box", " ad-row");
        }
    }

    /* ---------------------------------------------------------------- article ---------- */

    @Test
    @DisplayName("The article page renders its body, chrome and share metadata")
    void rendersArticle() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg"));

        assertThat(html).contains("Заглавие 7");
        assertThat(html).contains("<p>Тяло на статията</p>");     // th:utext, not escaped
        assertThat(html).contains("image-7.jpg");
        assertThat(html).contains("site-nav__items").contains("site-footer");
        assertThat(html).contains("/site-assets/site.css");
    }

    /**
     * The whole reason fragments/head takes six parameters. A head fragment without them would
     * give every article page the same title, description and share card.
     */
    @Test
    @DisplayName("The article page keeps its own title and share image, not the site's")
    void articleHeadIsPerArticle() {
        ArticleModel article = new ArticleModel(article(7, true, null), "bg", List.of());
        String html = render("article", Map.of(
                "article", article,
                "lang", "bg",
                "head", io.pacod.cms.site.data.HeadMeta.article(article, null)));

        assertThat(html).contains("<title>Заглавие 7</title>");
        assertThat(html).contains("https://www.example.org/assets/img/articles/image-7.jpg");
        assertThat(html).contains("<meta property=\"og:url\" content=\"https://www.example.org/article/7\"/>");
        // The article's own image wins over the website's share card.
        assertThat(html).doesNotContain("/media/brand/share.png");
        assertThat(html).doesNotContain("content=\"https://www.example.org\"");
    }

    /**
     * ArticleModel.getImage() and getContent() return "" -- not null -- when an article has no
     * images. A null check in the head fragment would emit og:image pointing at the bare host
     * and a preload with an empty href.
     */
    @Test
    @DisplayName("An article with no images shares the website's card, and emits no empty preload")
    void articleWithoutImagesEmitsNoImageMetadata() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(8, false, null), "bg", List.of()),
                "lang", "bg"));

        assertThat(html).contains("property=\"og:image\" content=\"https://www.example.org/media/brand/share.png\"");
        // No type and no size: those describe the article's image, which there is none of.
        assertThat(html).doesNotContain("og:image:type", "og:image:width");
        assertThat(html).doesNotContain("href=\"\"");
        assertThat(html).doesNotContain("/assets/img/articles/\"");
        assertThat(html).doesNotContain("rel=\"preload\"");
    }

    /* ---------------------------------------------------------------- the head's address ---------- */

    /** A website written in Bulgarian that offers Bulgarian, English, German and Russian. */
    private static io.pacod.cms.site.data.SiteChrome multilingual() {
        List<Language> languages = new java.util.ArrayList<>();
        for (String code : List.of("bg", "en", "de", "ru")) {
            languages.add(new Language(code));
        }
        return io.pacod.cms.site.data.SiteChrome.of(io.pacod.cms.site.testing.TestSites.settings(),
                io.pacod.cms.site.testing.TestSites.menus(), "http://localhost", languages, "/de/article/7", null);
    }

    /** Article 7, translated into English and German, published in Sofia's zone. */
    private static Map<String, Object> translatedArticleModel(String lang) {
        Article article = article(7, true, null);
        article.setTexts(List.of(TextItem.builder().lang("en").text("<p>Body</p>").build(),
                TextItem.builder().lang("de").text("<p>Text</p>").build()));
        article.setTitles(List.of(TextItem.builder().lang("de").text("Titel 7").build()));
        article.setTags(List.of("космос", "наука"));
        // Last: every setter stamps lastModifiedOn with the clock.
        article.setLastModifiedOn(LocalDateTime.of(2026, 3, 5, 8, 30));
        ArticleModel model = new ArticleModel(article, lang, List.of());
        io.pacod.cms.site.data.SiteChrome site = multilingual();
        return Map.of("article", model, "lang", lang, "site", site,
                "head", io.pacod.cms.site.data.HeadMeta.article(model, site, java.time.ZoneId.of("Europe/Sofia"),
                        java.time.ZoneOffset.UTC));
    }

    /**
     * The address a translated article is filed under, the languages it names and what kind of page
     * it is, on every set: magazine and minimal take editorial's head, so one fragment answers for
     * all three, and this proves the inheritance as much as the tags.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"editorial", "minimal", "magazine"})
    @DisplayName("A translated article names its canonical address, its alternates, its dates and its feed")
    void articleHeadNamesItsAddress(String set) {
        engine = io.pacod.cms.site.render.TemplateSets.engine(new io.pacod.cms.core.design.TemplateSetCatalog().chain(set),
                new io.pacod.cms.site.config.ThymeleafConfig().messageSource(), false, List.of());
        String html = render("article", translatedArticleModel("de"));

        assertThat(html).as(set).contains(
                "<link rel=\"canonical\" href=\"https://www.example.org/de/article/7\"/>",
                "<meta property=\"og:url\" content=\"https://www.example.org/de/article/7\"/>",
                "<link rel=\"alternate\" hreflang=\"bg\" href=\"https://www.example.org/article/7\"/>",
                "<link rel=\"alternate\" hreflang=\"en\" href=\"https://www.example.org/en/article/7\"/>",
                "<link rel=\"alternate\" hreflang=\"de\" href=\"https://www.example.org/de/article/7\"/>",
                "<link rel=\"alternate\" hreflang=\"x-default\" href=\"https://www.example.org/article/7\"/>",
                "<meta property=\"og:type\" content=\"article\"/>",
                "<meta property=\"article:published_time\" content=\"2026-03-04T12:00:00+02:00\"/>",
                "<meta property=\"article:modified_time\" content=\"2026-03-05T10:30:00+02:00\"/>",
                "<meta property=\"article:section\" content=\"Култура\"/>",
                "<meta property=\"article:tag\" content=\"космос\"/>",
                "<meta property=\"article:tag\" content=\"наука\"/>",
                "<meta property=\"og:locale\" content=\"de_DE\"/>",
                "<meta property=\"og:locale:alternate\" content=\"bg_BG\"/>",
                "<meta property=\"og:locale:alternate\" content=\"en_US\"/>",
                "<meta name=\"twitter:card\" content=\"summary_large_image\"/>",
                "<meta name=\"twitter:title\" content=\"Titel 7\"/>",
                "<meta name=\"twitter:image\" content=\"https://www.example.org/assets/img/articles/image-7.jpg\"/>",
                "<link rel=\"alternate\" type=\"application/atom+xml\" title=\"Example Gazette\" href=\"https://www.example.org/feed.xml?lang=de\"/>");
        // Russian is offered and not written; the reader's own language is no alternate of itself.
        // (The switcher's own links carry hreflang too, Russian among them.)
        assertThat(html).as(set).doesNotContain("rel=\"alternate\" hreflang=\"ru\"", "og:locale:alternate\" content=\"de_DE\"");
        assertThat(count(html, "rel=\"canonical\"")).as(set).isEqualTo(1);

        tools.jackson.databind.JsonNode posting = jsonLd(html, "BlogPosting");
        assertThat(posting.get("headline").asString()).isEqualTo("Titel 7");
        assertThat(posting.get("datePublished").asString()).isEqualTo("2026-03-04T12:00:00+02:00");
        assertThat(posting.get("dateModified").asString()).isEqualTo("2026-03-05T10:30:00+02:00");
        assertThat(posting.get("image").asString()).isEqualTo("https://www.example.org/assets/img/articles/image-7.jpg");
        assertThat(posting.get("author").get(0).get("name").asString()).isEqualTo("Иван Петров");
        assertThat(posting.get("mainEntityOfPage").get("@id").asString()).isEqualTo("https://www.example.org/de/article/7");
        assertThat(posting.get("publisher").get("name").asString()).isEqualTo("Example Gazette");
        assertThat(posting.get("publisher").get("logo").get("url").asString())
                .isEqualTo("https://www.example.org/media/brand/logo.svg");
        // The website's own block is still there beside it.
        assertThat(jsonLd(html, "WebSite").get("url").asString()).isEqualTo("https://www.example.org/");
    }

    /** The original-language page is filed under the bare route; one with no picture gets the small card. */
    @Test
    @DisplayName("The original's page is the bare route, and a page with no picture at all the small card")
    void originalArticleHead() {
        String html = render("article", translatedArticleModel("bg"));
        assertThat(html).contains("<link rel=\"canonical\" href=\"https://www.example.org/article/7\"/>",
                "<meta property=\"og:url\" content=\"https://www.example.org/article/7\"/>",
                "href=\"https://www.example.org/feed.xml\"/>");

        io.pacod.cms.core.data.SiteSettings settings = io.pacod.cms.site.testing.TestSites.settings();
        settings.setOgImage(null);
        ArticleModel bare = new ArticleModel(article(8, false, null), "bg", List.of());
        String small = render("article", Map.of("article", bare, "lang", "bg",
                "site", io.pacod.cms.site.data.SiteChrome.of(settings, io.pacod.cms.site.testing.TestSites.menus(), "http://localhost"),
                "head", io.pacod.cms.site.data.HeadMeta.article(bare, null)));
        assertThat(small).contains("<meta name=\"twitter:card\" content=\"summary\"/>").doesNotContain("twitter:image");
    }

    /**
     * The structured data is JSON however hostile the title: the inlining escapes what would close
     * the script element or break the string, and the headline is the title without its markup.
     */
    @Test
    @DisplayName("The BlogPosting is valid JSON whatever the title holds, and closes no script early")
    void jsonLdSurvivesAHostileTitle() {
        Article article = article(7, true, null);
        article.setTitle("Lune's <em>\"night\"</em> </script><script>alert(1)</script> & co \\ é");
        ArticleModel model = new ArticleModel(article, "bg", List.of());
        String html = render("article", Map.of("article", model, "lang", "bg",
                "head", io.pacod.cms.site.data.HeadMeta.article(model, null)));

        // The body renders the title's markup as it always has; the head is where it must not escape.
        assertThat(html.substring(0, html.indexOf("</head>"))).doesNotContain("<script>alert(1)", "</script><script>");
        assertThat(jsonLd(html, "BlogPosting").get("headline").asString())
                .isEqualTo("Lune's \"night\" alert(1) & co \\ é");
    }

    /**
     * Pages rendered without a {@code head} keep what they had: the error page above all, which no
     * controller renders, and whose og:url names the home page -- a canonical there would tell a
     * crawler a missing page is the home page.
     */
    @Test
    @DisplayName("A page without a head -- the error page, an issue -- names no canonical, alternate or feed")
    void noHeadNoAddress() {
        for (String html : List.of(renderBare("error", Map.of("status", 404, "lang", "bg")),
                render("error", Map.of("status", 404, "lang", "bg")), render("issue", issueModel("bg")),
                render("index", Map.of("article", new ArticleModel(article(7, true, null), "bg", List.of()),
                        "lang", "bg", "tags", List.of())))) {
            assertThat(html).doesNotContain("rel=\"canonical\"", "rel=\"alternate\" hreflang", "application/atom+xml", "article:",
                            "BlogPosting", "og:locale:alternate")
                    .contains("<meta property=\"og:type\" content=\"website\"/>", "name=\"twitter:card\"");
        }
    }

    @Test
    @DisplayName("A category list names its page, its languages and both feeds; the latest list its own address")
    void listAndLatestHeads() {
        io.pacod.cms.site.data.SiteChrome site = multilingual();
        String html = render("list", Map.of("list", list(45), "lang", "de", "site", site,
                "head", io.pacod.cms.site.data.HeadMeta.list("9", 1, true, "de", site)));
        assertThat(html).contains("<link rel=\"canonical\" href=\"https://www.example.org/de/list/9?page=1\"/>",
                "<link rel=\"alternate\" hreflang=\"ru\" href=\"https://www.example.org/ru/list/9?page=1\"/>",
                "<link rel=\"alternate\" hreflang=\"x-default\" href=\"https://www.example.org/list/9?page=1\"/>",
                "type=\"application/atom+xml\" title=\"Example Gazette\" href=\"https://www.example.org/feed.xml?lang=de\"/>",
                "type=\"application/atom+xml\" title=\"Култура | Example Gazette\" href=\"https://www.example.org/list/9/feed.xml?lang=de\"/>",
                "<meta property=\"og:type\" content=\"website\"/>");

        String latest = render("index", Map.of("article", new ArticleModel(article(7, true, null), "en", List.of()),
                "lang", "en", "tags", List.of(), "site", site, "head", io.pacod.cms.site.data.HeadMeta.latest("en", site)));
        assertThat(latest).contains("<link rel=\"canonical\" href=\"https://www.example.org/latest?lang=en\"/>",
                "<meta property=\"og:url\" content=\"https://www.example.org/latest?lang=en\"/>",
                "<meta property=\"og:type\" content=\"website\"/>").doesNotContain("article:published_time", "BlogPosting");
    }

    @Test
    @DisplayName("A list counts its articles as a label and a number, which no language's plural can break")
    void listCount() {
        assertThat(render("list", Map.of("list", list(1), "lang", "en"))).contains("Articles · 1").doesNotContain("1 articles");
        assertThat(render("list", Map.of("list", list(45), "lang", "ru"))).contains("Материалы · 45");
        assertThat(render("list", Map.of("list", list(3), "lang", "bg"))).contains("Материали · 3");
    }

    @Test
    @DisplayName("Search results name no canonical and no alternates, only the feed")
    void searchHead() {
        io.pacod.cms.site.data.SiteChrome site = multilingual();
        String html = render("search", Map.of("lang", "de", "q", "x", "searched", true, "site", site,
                "results", ArticleListPageable.builder().page(org.springframework.data.domain.Page.empty()).build(),
                "head", io.pacod.cms.site.data.HeadMeta.minimal("de", site)));
        assertThat(html).doesNotContain("rel=\"canonical\"", "rel=\"alternate\" hreflang")
                .contains("<meta property=\"og:url\" content=\"https://www.example.org/search\"/>",
                        "href=\"https://www.example.org/feed.xml?lang=de\"/>");
    }

    /** The JSON-LD block of {@code type}, parsed: what a crawler reads. */
    private static tools.jackson.databind.JsonNode jsonLd(String html, String type) {
        java.util.regex.Matcher blocks = java.util.regex.Pattern
                .compile("<script type=\"application/ld\\+json\"[^>]*>(.*?)</script>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        while (blocks.find()) {
            tools.jackson.databind.JsonNode node = json.readTree(blocks.group(1));
            if (type.equals(node.get("@type").asString())) {
                return node;
            }
        }
        throw new AssertionError("No " + type + " JSON-LD in the page");
    }

    /**
     * The landing, listing, standing-page and search templates have no image of their own, and
     * pass none into the head fragment: a non-empty argument wins over the website's setting, so a
     * hard-coded masthead path there would leave the share image configured for the website
     * applying to article pages without images and nowhere else.
     */
    @Test
    @DisplayName("A page with no image of its own shares the website's configured card")
    void pagesWithoutAnImageShareTheWebsitesCard() {
        String html = render("search", Map.of("lang", "bg", "q", "", "searched", false));

        assertThat(html).contains("property=\"og:image\" content=\"https://www.example.org/media/brand/share.png\"");
    }

    @Test
    @DisplayName("With no share image at all, no og:image is emitted")
    void noShareImageAtAllEmitsNone() {
        io.pacod.cms.core.data.SiteSettings settings = io.pacod.cms.site.testing.TestSites.settings();
        settings.setOgImage(null);
        String html = render("article", Map.of(
                "article", new ArticleModel(article(8, false, null), "bg", List.of()),
                "lang", "bg",
                "site", io.pacod.cms.site.data.SiteChrome.of(settings, io.pacod.cms.site.testing.TestSites.menus(),
                        "http://localhost")));

        assertThat(html).doesNotContain("og:image");
    }

    @Test
    @DisplayName("An embedded video replaces the image gallery")
    void articleRendersEmbeddedVideo() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(9, false, "<iframe src=\"video\"></iframe>"), "bg", List.of()),
                "lang", "bg"));

        assertThat(html).contains("video-container");
        assertThat(html).contains("<iframe src=\"video\"></iframe>");
        assertThat(html).doesNotContain("class=\"gallery\"");
    }

    /**
     * A carousel that renders images 2..n as {@code display:none} and shows only
     * {@code images[0].caption} writes every further caption and never displays it. The
     * scroll-snap strip shows all of them.
     */
    @Test
    @DisplayName("Every image in a multi-image article is rendered, with its own caption")
    void articleRendersEveryImageAndCaption() {
        Article multi = article(11, false, null);
        List<ImageMedia> images = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ImageMedia image = new ImageMedia(i);
            image.setFilename("image-" + i + ".jpg");
            image.setCaption("Каптион " + i);
            images.add(image);
        }
        multi.setImages(images);

        String html = render("article", Map.of(
                "article", new ArticleModel(multi, "bg", List.of()), "lang", "bg"));

        assertThat(count(html, "class=\"gallery__item\"")).isEqualTo(3);
        assertThat(html).contains("image-1.jpg").contains("image-2.jpg").contains("image-3.jpg");
        assertThat(html).contains("Каптион 1").contains("Каптион 2").contains("Каптион 3");
    }

    /**
     * The studio's embedded-video textarea posts {@code ""} for an article with no video, so a
     * template that tests that field for {@code null} gets it wrong.
     *
     * <p>The result would be an empty {@code .video-container} -- which carries the
     * responsive-iframe padding rule and therefore draws as a black box -- while a gallery guarded
     * by {@code embedded == null} is suppressed entirely. One save of an article with no video
     * would be enough to lose its images. Same class of bug as {@code ArticleModel.getImage()}
     * returning {@code ""}, which {@code fragments/head.html} guards with
     * {@code #strings.isEmpty}.
     */
    @Test
    @DisplayName("An empty embedded video renders no video box and does not hide the gallery")
    void emptyEmbeddedDoesNotSuppressTheGallery() {
        Article article = article(11, false, null);
        article.setEmbedded("");
        ImageMedia image = new ImageMedia(1);
        image.setFilename("image-1.jpg");
        article.setImages(List.of(image));

        String html = render("article", Map.of(
                "article", new ArticleModel(article, "bg", List.of()), "lang", "bg"));

        assertThat(html).doesNotContain("video-container");
        assertThat(html).contains("class=\"gallery\"").contains("image-1.jpg");
    }

    @Test
    @DisplayName("A real embedded video still replaces the gallery")
    void realEmbeddedStillWins() {
        // The positive half. Without it the assertion above passes just as well against a template
        // that has stopped rendering embedded video at all.
        Article article = article(11, false, null);
        article.setEmbedded("<iframe src=\"https://www.youtube.com/embed/x\"></iframe>");
        ImageMedia image = new ImageMedia(1);
        image.setFilename("image-1.jpg");
        article.setImages(List.of(image));

        String html = render("article", Map.of(
                "article", new ArticleModel(article, "bg", List.of()), "lang", "bg"));

        assertThat(html).contains("video-container").contains("youtube.com/embed/x");
        assertThat(html).doesNotContain("class=\"gallery\"");
    }

    /**
     * An image the studio attached only so the operator could reference it from inside the body
     * stays out of the strip above the article.
     *
     * <p>Paired with a positive assertion on purpose: "image-1.jpg is absent" passes just as well
     * when the gallery is broken outright as when the mechanism works, which is the same trap the
     * ad-sidebar tests document.
     */
    @Test
    @DisplayName("An image hidden by the studio is left out of the gallery")
    void articleOmitsHiddenImagesFromTheGallery() {
        Article multi = article(11, false, null);
        List<ImageMedia> images = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ImageMedia image = new ImageMedia(i);
            image.setFilename("image-" + i + ".jpg");
            image.setCaption("Каптион " + i);
            images.add(image);
        }
        multi.setImages(images);
        multi.setHiddenImageIds(List.of(1));

        String html = render("article", Map.of(
                "article", new ArticleModel(multi, "bg", List.of()), "lang", "bg"));

        assertThat(count(html, "class=\"gallery__item\"")).isEqualTo(2);
        assertThat(html).doesNotContain("image-1.jpg");
        assertThat(html).contains("image-2.jpg").contains("image-3.jpg");
    }

    /**
     * The gallery is suppressed rather than rendered empty, exactly as it is for an article that
     * never had an image -- otherwise the figure frame draws around nothing.
     */
    @Test
    @DisplayName("An article whose images are all hidden renders no gallery at all")
    void articleWithEveryImageHiddenRendersNoGallery() {
        Article multi = article(11, false, null);
        ImageMedia only = new ImageMedia(1);
        only.setFilename("in-the-body.jpg");
        multi.setImages(List.of(only));
        multi.setHiddenImageIds(List.of(1));

        String html = render("article", Map.of(
                "article", new ArticleModel(multi, "bg", List.of()), "lang", "bg"));

        assertThat(html).doesNotContain("class=\"gallery\"");
        assertThat(html).doesNotContain("in-the-body.jpg");
        // The article itself still renders; only the strip is gone.
        assertThat(html).contains("class=\"article-body");
    }

    /**
     * The strip is upgraded to a carousel by {@code initGallery()} in site.js, which builds the
     * wrapper, the arrows and the dots itself. None of that may appear here.
     *
     * <p>Not a stylistic preference: prev/next markup shipped by the template outlives the script
     * that should drive it, and when that script does not run its arrows do nothing and images
     * 2..n stay {@code display:none} with no way to reach them. Controls that can outlive the
     * code driving them is the bug. Built by the script, they cannot -- and the page with
     * scripting off keeps a strip that still scrolls.
     */
    @Test
    @DisplayName("The template renders the strip only -- every carousel control is built by site.js")
    void articleGalleryShipsNoStaticControls() {
        Article multi = article(12, false, null);
        List<ImageMedia> images = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ImageMedia image = new ImageMedia(i);
            image.setFilename("image-" + i + ".jpg");
            images.add(image);
        }
        multi.setImages(images);

        String html = render("article", Map.of(
                "article", new ArticleModel(multi, "bg", List.of()), "lang", "bg"));

        assertThat(html).contains("class=\"gallery\"");
        assertThat(html).doesNotContain("carousel");

        /* Sliced, because the page carries one legitimate display:none of its own -- the Google
           Tag Manager noscript iframe. */
        String gallery = html.substring(html.indexOf("class=\"gallery\""),
                html.indexOf("class=\"article-body\""));
        assertThat(gallery).doesNotContain("display:none").doesNotContain("display: none");
        assertThat(gallery).doesNotContain("hidden");
    }

    @Test
    @DisplayName("The breadcrumb links back to the article's category")
    void articleBreadcrumbLinksToCategory() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg"));

        assertThat(html).contains("/list/9?lang=bg");
        assertThat(html).contains("Култура");
    }

    /* ---------------------------------------------------------------- masthead ---------- */

    /**
     * The masthead ships <em>both</em> inks of the logo and site.css hides one. Night mode is a
     * class on {@code <html>} rather than {@code prefers-color-scheme}, so {@code <picture>}
     * cannot see it and a src swap would need JavaScript running a frame late on every
     * navigation.
     *
     * <p>Deleting either {@code <img>} is silent in two directions: the CSS selectors that do
     * the hiding name the parent as well as the image, so they would simply match nothing, and
     * dropping the light copy would take the only {@code alt} naming the publication with it.
     * The dark copy is deliberately {@code alt=""} + aria-hidden -- {@code display:none} is
     * honoured by screen readers, but which of the pair is hidden depends on a stored
     * preference, so only one of them may ever carry the name.
     */
    @Test
    @DisplayName("The masthead carries both logo inks from the settings, and only the light one names the website")
    void mastheadShipsBothLogoInks() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg"));

        /* Sliced: the page carries other images, and og:image names the same path. */
        String logo = html.substring(html.indexOf("class=\"masthead__logo\""),
                html.indexOf("class=\"masthead__side\""));

        assertThat(logo).contains("src=\"/media/brand/logo.svg\"");
        assertThat(logo).contains("src=\"/media/brand/logo-dark.svg\"");
        assertThat(logo).containsOnlyOnce("alt=\"" + io.pacod.cms.site.testing.TestSites.NAME_BG + "\"");
        assertThat(logo).containsOnlyOnce("aria-hidden=\"true\"");
    }

    @Test
    @DisplayName("A website with no logo is named in text, never a broken image")
    void mastheadWithoutALogoSetsTheName() {
        io.pacod.cms.core.data.SiteSettings settings = io.pacod.cms.site.testing.TestSites.settings();
        settings.setLogoLight(null);
        settings.setLogoDark(null);
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "en",
                "site", io.pacod.cms.site.data.SiteChrome.of(settings, io.pacod.cms.site.testing.TestSites.menus(),
                        "http://localhost")));

        String logo = html.substring(html.indexOf("class=\"masthead__logo\""),
                html.indexOf("class=\"masthead__side\""));
        assertThat(logo).doesNotContain("<img");
        assertThat(logo).contains("class=\"masthead__wordmark\">" + io.pacod.cms.site.testing.TestSites.NAME_EN + "<");
    }

    /**
     * Every third-party identifier on a page is the website's own setting, and renders nothing at
     * all when unset -- a page carrying another website's tracking container is worse than one
     * carrying none, so no template writes one in.
     */
    @Test
    @DisplayName("Tracking, verification and the Facebook SDK render only when configured")
    void thirdPartyIdsRenderOnlyWhenConfigured() {
        Map<String, Object> article = Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "en");

        String configured = render("article", article);
        assertThat(configured)
                // Written through JavaScript inlining, so as an escaped string literal.
                .contains("'dataLayer',\"" + io.pacod.cms.site.testing.TestSites.GTM_ID + "\")")
                .contains("ns.html?id=" + io.pacod.cms.site.testing.TestSites.GTM_ID)
                .contains("name=\"google-site-verification\" content=\"google-token-0123\"")
                .contains("name=\"msvalidate.01\" content=\"bing-token-0123\"")
                .contains("property=\"fb:app_id\" content=\"" + io.pacod.cms.site.testing.TestSites.FB_APP_ID + "\"")
                .contains("connect.facebook.net/en_US/sdk.js")
                .contains("class=\"fb-share-button\"");

        Map<String, Object> bare = new java.util.HashMap<>(article);
        bare.put("site", io.pacod.cms.site.data.SiteChrome.empty("http://localhost"));
        String unconfigured = render("article", bare);
        assertThat(unconfigured).doesNotContain("googletagmanager", "google-site-verification",
                "msvalidate", "fb:app_id", "connect.facebook.net", "fb-share-button", "GTM-");
        // The page itself still renders in full.
        assertThat(unconfigured).contains("Заглавие 7", "class=\"site-footer\"");
    }

    @Test
    @DisplayName("The navbar renders the main menu from data: groups open panels, leaves link")
    void navbarRendersTheMainMenu() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "en"));
        String nav = html.substring(html.indexOf("id=\"site-nav-items\""), html.indexOf("</nav>"));

        assertThat(nav).contains("href=\"/?lang=en\">Home</a>");
        assertThat(nav).containsPattern("<button class=\"nav-item__label\" type=\"button\">Sections</button>");
        assertThat(nav).contains("href=\"/list/2?lang=en\">News</a>", "href=\"/list/3?lang=en\">In depth</a>");
        assertThat(nav).contains("href=\"/p/about?lang=en\">About us</a>");
        // No category link is written into the template.
        assertThat(nav).doesNotContain("data-i18n=\"nav_");
    }

    /* ------------------------------------------------------------------ index ---------- */

    /**
     * index.html and article.html render the same body; the only thing that should differ is the
     * canonical URL, which is this route rather than the article's own.
     */
    @Test
    @DisplayName("The latest-article page shares the article body but keeps its own canonical URL")
    void rendersIndex() {
        String html = render("index", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg",
                "tags", List.of()));

        assertThat(html).contains("Заглавие 7");
        assertThat(html).contains("<p>Тяло на статията</p>");
        assertThat(html).contains("https://www.example.org/latest");
        // Open Graph is property=, never the obsolete name= form.
        assertThat(html).doesNotContain("<meta name=\"og:");
    }

    /* ------------------------------------------------------------------- list ---------- */

    @Test
    @DisplayName("The list page renders one row per article and paginates")
    void rendersList() {
        String html = render("list", Map.of("list", list(45), "lang", "bg"));

        assertThat(html).contains("Култура");
        assertThat(html).contains("45");
        assertThat(count(html, "class=\"list-row\"")).isEqualTo(45);
        assertThat(html).contains("pager__item--current");
        assertThat(html).contains("/list/9?");
    }

    @Test
    @DisplayName("The footer is the website's own: contact, profiles, its menu and its note")
    void footerRendersTheSiteSettings() {
        String html = render("list", Map.of("list", list(3), "lang", "en"));
        String footer = html.substring(html.indexOf("<footer"), html.indexOf("</footer>"));

        assertThat(footer).contains("1 Example Street", "Example City", "editors@example.org",
                "href=\"tel:+00123456\"");
        assertThat(footer).contains("href=\"https://www.instagram.com/example/\"", ">INSTAGRAM<");
        assertThat(footer).contains("href=\"/p/terms?lang=en\"", ">Terms of use<");
        assertThat(footer).contains("© Example Gazette");
    }

    @Test
    @DisplayName("A footer with nothing configured renders no empty headings")
    void footerWithoutSettingsIsQuiet() {
        String html = render("list", Map.of("list", list(3), "lang", "en",
                "site", io.pacod.cms.site.data.SiteChrome.empty("http://localhost")));
        String footer = html.substring(html.indexOf("<footer"), html.indexOf("</footer>"));

        assertThat(footer).doesNotContain("site-footer__social", "mailto:", "tel:", "<a ");
    }

    /**
     * A booking can run on the list page and nowhere else. That is a page filter on the document,
     * and what reaches the template is simply a different list per page.
     *
     * <p>Driven by supplied data rather than by a template flag, and asserted in both directions
     * against the <em>same</em> fragment -- so the page that does not carry the booking is shown
     * to still render the ones it does.
     */
    @Test
    @DisplayName("A booking on one page only reaches that page, and does not blank the others")
    void perPageBookingsAreHonoured() {
        Banner everywhere = banner(91, BannerType.HTML5, "/resources/banners/square/index.html", null);
        Banner listOnly = banner(93, BannerType.IMAGE, "/assets/img/partners/list-only.webp",
                "https://list-only.example/");

        String listHtml = render("list", Map.of(
                "list", list(3), "lang", "bg",
                "sidebarBanners", List.of(everywhere, listOnly)));

        assertThat(listHtml).contains("/assets/img/partners/list-only.webp");
        assertThat(listHtml).contains("/resources/banners/square/index.html");

        String articleHtml = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg",
                "sidebarBanners", List.of(everywhere)));

        assertThat(articleHtml).doesNotContain("/assets/img/partners/list-only.webp");
        // The assertion above would pass on an empty column too, which is exactly the failure
        // mode this pins: the page still renders the booking it does carry.
        assertThat(articleHtml).contains("/resources/banners/square/index.html");
    }

    @Test
    @DisplayName("An image booking links through the click counter, never straight at the advertiser")
    void imageBannersLinkThroughTheClickCounter() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg"));

        assertThat(html).contains("/b/92");
        assertThat(html).doesNotContain("https://advertiser.example/");
    }

    /**
     * {@code site.js} measures viewable impressions and HTML5 clicks off {@code data-partner}, the
     * only place the browser can learn which booking a frame belongs to -- an HTML5 frame's
     * {@code src} names a folder, and several bookings may share one. Drop the attribute and
     * nothing fails: the page renders, the banners work, and the studio's viewable and HTML5
     * click columns simply stop moving.
     */
    @Test
    @DisplayName("Every creative frame carries its booking id for site.js, and no HTML5 frame is sandboxed")
    void creativeFramesCarryTheirBookingId() throws Exception {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg"));

        // Both types: the HTML5 wrapper and the image link are what the observer watches.
        assertThat(count(html, "data-partner=\"90\"")).isEqualTo(1);
        assertThat(count(html, "data-partner=\"91\"")).isEqualTo(1);
        assertThat(count(html, "data-partner=\"92\"")).isEqualTo(1);
        assertThat(html).contains("<a class=\"partner-card__frame");

        // A sandbox without allow-same-origin -- or credentialless -- makes the frame's document
        // unreadable from the page, and HTML5 clicks would stop being counted with no error.
        assertThat(html).doesNotContain("sandbox", "credentialless");

        // The script and the markup name the attribute and the endpoints identically. There is no
        // JS test toolchain here, so this is the one assertion spanning the two files.
        String script = new org.springframework.core.io.ClassPathResource("static/site-assets/site.js")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(script).contains("'[data-partner]'", "getAttribute('data-partner')",
                "'/b/v'", "'/c'", "initPartners();");
    }

    @Test
    @DisplayName("No bookings renders no ad boxes at all")
    void rendersNothingWithoutBanners() {
        String html = render("article", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()),
                "lang", "bg",
                "topBanners", List.of(),
                "sidebarBanners", List.of()));

        assertThat(html).doesNotContain("class=\"partner-card\"");
        // The .partner-row wrapper carries padding of its own, so an empty one is a visible gap.
        assertThat(html).doesNotContain("class=\"partner-row\"");
    }

    /**
     * The advertising must not emit a class beginning with {@code ad-}, on any page.
     *
     * <p>EasyList, the default list in uBlock Origin, AdBlock Plus, AdGuard and Brave Shields,
     * carries two <em>generic</em> cosmetic filters that match {@code .ad-box} and {@code .ad-row}
     * verbatim on every domain:
     *
     * <pre>
     *   ##.ad-box:not(#ad-banner):not(:empty)
     *   ##.ad-row
     * </pre>
     *
     * <p>So an ad box under either name is hidden with an injected
     * {@code display: none !important} while the server is completely healthy: the markup
     * renders, every booking resolves, every creative returns 200, and <em>every assertion in
     * this class passes</em>. That is the reason for this test rather than another one that
     * names the current classes: those already exist above and would go on passing through a
     * rename straight back into the filter list. The invariant is the shape of the name, not the
     * name.
     *
     * <p>Asserted on every template this class renders, because the leaderboard row and the
     * sidebar are separate fragments and a partial rename is the likely mistake.
     * {@code landing.html} carries the same pair and is pinned by the mirror of this test in
     * {@code LandingTemplateRenderTest}. {@code page.html} carries it too but is rendered by no
     * test in this repository -- {@code PageControllerTest} asserts the view name only -- so it
     * rides on the two fragments being shared rather than on an assertion of its own.
     * {@code error.html} is deliberately excluded: it carries no advertising at all.
     */
    @Test
    @DisplayName("No advertising class begins with 'ad-', which ad blockers hide generically")
    void advertisingCarriesNoBlockerBaitClassNames() {
        Map<String, Object> article = Map.of(
                "article", new ArticleModel(article(7, true, null), "bg", List.of()), "lang", "bg");

        for (Map.Entry<String, Map<String, Object>> page : Map.of(
                "article", article,
                "index", article,
                "list", Map.<String, Object>of("list", list(3), "lang", "bg")).entrySet()) {

            String html = render(page.getKey(), page.getValue());

            // The positive half: the fragments really did run on this page. Without it a template
            // that rendered no advertising at all would satisfy every assertion below.
            assertThat(html).as("%s renders advertising", page.getKey())
                    .contains("class=\"partner-card\"");

            assertThat(html).as("%s emits no ad- class", page.getKey())
                    .doesNotContain("class=\"ad-", "class=\"ad ", " ad-box", " ad-row");
        }
    }

    @Test
    @DisplayName("An empty listing says so instead of rendering an empty page")
    void emptyListRendersANote() {
        String html = render("list", Map.of("list", list(0), "lang", "bg"));

        assertThat(html).contains("empty-note");
        assertThat(count(html, "class=\"list-row\"")).isZero();
        // One page of nothing is not worth a pager.
        assertThat(html).doesNotContain("pager__item");
    }

    /* ----------------------------------------------------------- article-print --------- */

    /**
     * This one is printed, so it must come out light whatever the reader stored -- which means
     * it must not pull in the head fragment's theme snippet.
     */
    @Test
    @DisplayName("The print view is bare and never applies the night theme")
    void rendersArticlePrintWithoutTheThemeSnippet() {
        String html = render("article-print", Map.of(
                "article", new ArticleModel(article(7, true, null), "bg"),
                "lang", "bg"));

        assertThat(html).contains("Заглавие 7");
        assertThat(html).contains("/site-assets/site.css");
        assertThat(html).doesNotContain("pacod-theme");
        assertThat(html).doesNotContain("site-nav__items");
        assertThat(html).doesNotContain("site-footer");
    }

    /**
     * The strip in {@code fragments/article-view.html} is a scroll container, and this page loads
     * no site.js -- so nothing ever adds {@code .is-carousel}, which is where the scrollbar is
     * suppressed and the arrows and dots come from. Printed, that is a scrollbar and images past
     * the edge that no reader can reach.
     *
     * <p>Built with three images on purpose. The {@code article(...)} helper attaches exactly one,
     * so every assertion below would pass against the full scrolling strip too. The identity
     * assertion is there for the same reason the count alone is not enough: rendering
     * {@code images[1]} is also one image.
     */
    @Test
    @DisplayName("The print view shows the first image only, with no scrolling strip")
    void articlePrintRendersOnlyTheFirstImage() {
        Article article = article(7, false, null);
        article.setImages(List.of(image(11), image(12), image(13)));

        String html = render("article-print", Map.of(
                "article", new ArticleModel(article, "bg"),
                "lang", "bg"));

        assertThat(count(html, "/assets/img/articles/")).isEqualTo(1);
        assertThat(html).contains("/assets/img/articles/image-11.jpg");
        // .gallery is the scroll container; without the class there is nothing to scroll.
        assertThat(html).doesNotContain("class=\"gallery\"");
        assertThat(html).doesNotContain("gallery__item");
    }

    @Test
    @DisplayName("Both print views show the abstract and keywords they are given, and nothing when there are none")
    void printViewsShowTheFrontMatter() {
        io.pacod.cms.site.services.PrintExtras.PrintMeta meta =
                new io.pacod.cms.site.services.PrintExtras.PrintMeta("Кратко резюме.", List.of("наука", "град"));
        for (String view : List.of("article-print", "article-print-scientific")) {
            String html = render(view, Map.of(
                    "article", new ArticleModel(article(7, true, null), "bg"),
                    "lang", "bg", "printMeta", meta));
            assertThat(html).as(view).contains("print-abstract").contains("Кратко резюме.")
                    .contains("наука · град").contains("/site-assets/print.css")
                    .doesNotContain("??").doesNotContain("pacod-theme");

            String bare = render(view, Map.of(
                    "article", new ArticleModel(article(7, true, null), "bg"),
                    "lang", "bg", "printMeta", new io.pacod.cms.site.services.PrintExtras.PrintMeta(null, List.of())));
            assertThat(bare).as(view).doesNotContain("print-abstract").doesNotContain("print-keywords");
        }
    }

    @Test
    @DisplayName("The scientific print view lays the body in two columns and prints no video frame")
    void scientificPrintView() {
        Article article = article(7, false, null);
        article.setEmbedded("<iframe src=\"https://www.youtube.com/embed/x\"></iframe>");
        article.setImages(List.of(image(11), image(12)));

        String html = render("article-print-scientific", Map.of(
                "article", new ArticleModel(article, "bg"), "lang", "bg"));

        assertThat(html).contains("class=\"article-body sci-body\"").contains("Заглавие 7")
                .doesNotContain("youtube.com/embed");
        assertThat(count(html, "/assets/img/articles/")).isEqualTo(1);
    }

    /* ----------------------------------------------------------------- error ----------- */

    /**
     * Renders a template with exactly the variables given -- no default bookings, no {@code lang}.
     *
     * <p>{@link #render} exists to keep the ad assertions honest and is the wrong tool here: the
     * whole property being pinned is that {@code error.html} needs nothing in the model, because
     * the request that reaches it never got as far as a controller.
     */
    /**
     * {@code social.html} as SocialController renders it: the page view plus the website's chrome
     * that SiteChromeAdvice adds, and no ad columns -- /social carries none.
     */
    private String renderSocial(Map<String, Object> variables) {
        Map<String, Object> model = new java.util.HashMap<>(variables);
        model.putIfAbsent("site", io.pacod.cms.site.testing.TestSites.chrome());
        // What PageComposer adds to every page with content of its own, when no layout says more.
        model.putIfAbsent("regions", io.pacod.cms.site.render.Regions.primaryOnly());
        return renderBare("social", model);
    }

    private String renderBare(String template, Map<String, Object> variables) {
        MockServletContext servletContext = new MockServletContext();
        IWebExchange exchange = JakartaServletWebApplication
                .buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse());

        WebContext context = new WebContext(exchange, localeOf(variables));
        variables.forEach(context::setVariable);

        return engine.process(template, context);
    }

    /* ------------------------------------------------------------------ /social */

    private static SocialView socialView(String requested, SocialSource source, List<Article> latest) {
        return socialView(requested, source, latest, java.util.Map.of());
    }

    private static SocialView socialView(String requested, SocialSource source, List<Article> latest,
                                         Map<String, LinkTarget> linked) {
        SocialPage page = new SocialPage();
        page.setPublished(true);
        page.putName("bg", "Примерен вестник");
        page.putName("en", "Example Gazette");
        page.putBio("bg", "Новини, анализи и ръководства.");

        SocialLink instagram = new SocialLink("insta001");
        instagram.setPlacement(SocialLinkPlacement.ICON);
        instagram.setIcon(SocialIcon.INSTAGRAM);
        instagram.setUrl("https://www.instagram.com/example/");
        page.getLinks().add(instagram);

        SocialLink subscribe = new SocialLink("button01");
        subscribe.setIcon(SocialIcon.EMAIL);
        subscribe.putTitle("bg", "Абонамент");
        subscribe.putTitle("en", "Subscribe");
        subscribe.setUrl("/p/subscription");
        page.getLinks().add(subscribe);

        // Written in Bulgarian only, so on the English page it carries lang="bg".
        SocialLink event = new SocialLink("button02");
        event.putTitle("bg", "Семинар");
        event.setUrl("https://example.com/seminar");
        page.getLinks().add(event);

        Language bg = new Language();
        bg.setCode("bg");
        bg.setNativeName("Български");
        Language en = new Language();
        en.setCode("en");
        en.setNativeName("English");

        // An article link, added in Bulgarian only -- what the studio's add form writes.
        SocialLink read = new SocialLink("article1");
        read.putTitle("bg", "Есенният фестивал");
        read.setUrl("/article/3150");
        page.getLinks().add(read);

        return SocialView.of(page, null, linked, requested, LocalDateTime.now(), latest, List.of(bg, en), source,
                java.util.Set.of());
    }

    /**
     * {@code social.html} has a head of its own, and the one thing that head must never do is
     * load a script that reloads or redirects the page: it would count the visit twice and replace
     * the referrer with this page -- every Instagram visit recorded as one from the site. Nothing
     * on the server can see that happen; this is the only place it is caught.
     */
    @Test
    @DisplayName("/social is standalone: no reloader, no site scripts, no navbar, no ad columns")
    void socialPageIsStandalone() {
        String html = renderSocial(Map.of("social",
                socialView("bg", SocialSource.INSTAGRAM, List.of())));

        // No script is loaded by address at all; the theme snippet and GTM are inline.
        assertThat(html).doesNotContainPattern("<script[^>]*\\ssrc=");
        assertThat(html).doesNotContain("/site-assets/site.js", "masthead", "partner-card",
                "partner-row");
        // The theme snippet and GTM come from fragments/head by name, exactly once each.
        assertThat(count(html, "pacod-theme")).isEqualTo(1);
        assertThat(count(html, "GTM-TEST4321")).isEqualTo(2);  // the script and the noscript frame
        assertThat(html).contains("/site-assets/site.css", "<html lang=\"bg\"");
    }

    /**
     * The network is decided when the page renders; the click arrives with the page as its
     * referrer and cannot be classified on its own. So an href that lost {@code src=} costs the
     * attribution of every click on it, silently.
     */
    @Test
    @DisplayName("every tile, article and language link on /social carries src= and rel=nofollow")
    void socialLinksCarryTheSource() {
        Article article = article(3150, true, null);
        String html = renderSocial(Map.of("social",
                socialView("en", SocialSource.TIKTOK, List.of(article))));

        assertThat(html).contains("href=\"/social/go/insta001?src=tiktok\"",
                // /p/subscription is the site's own page, so its link also carries the language.
                "href=\"/social/go/button01?src=tiktok&amp;lang=en\"",
                "href=\"/social/go/button02?src=tiktok\"",
                "href=\"/social/read/3150?src=tiktok&amp;lang=en\"",
                "href=\"/social?src=tiktok&amp;lang=bg\"");

        java.util.regex.Matcher anchors = java.util.regex.Pattern
                .compile("<a [^>]*href=\"(/social[^\"]*)\"[^>]*>").matcher(html);
        int seen = 0;
        while (anchors.find()) {
            seen++;
            assertThat(anchors.group(1)).as(anchors.group()).contains("src=tiktok");
            assertThat(anchors.group()).as(anchors.group()).contains("rel=\"nofollow\"");
        }
        // Three buttons (one an article), one icon, one article tile, two languages.
        assertThat(seen).isEqualTo(7);
    }

    @Test
    @DisplayName("/social resolves each label on its own and marks the one in another language")
    void socialLabelsCarryTheirLanguage() {
        String html = renderSocial(Map.of("social",
                socialView("en", SocialSource.DIRECT, List.of())));

        assertThat(html).contains("<html lang=\"en\"", ">Example Gazette<", ">Subscribe<");
        assertThat(html).containsPattern("lang=\"bg\"[^>]*>Семинар<");
        // The bio exists in Bulgarian only.
        assertThat(html).containsPattern("lang=\"bg\"[^>]*>Новини, анализи и ръководства");
        assertThat(html).contains("aria-current=\"page\"");
    }

    /**
     * Fanboy's Social list hides generic names like {@code .bio-social} on every domain. On this
     * page that is not one hidden widget, it is the whole page -- so every class on it, not just
     * the advertising, is pinned to the neutral prefix.
     */
    @Test
    @DisplayName("every class on /social starts with linkpage")
    void socialClassesAreNeutral() {
        String html = renderSocial(Map.of("social",
                socialView("bg", SocialSource.DIRECT, List.of(article(3150, true, null)))));

        java.util.regex.Matcher classes = java.util.regex.Pattern.compile("class=\"([^\"]*)\"").matcher(html);
        int seen = 0;
        while (classes.find()) {
            for (String name : classes.group(1).trim().split("\\s+")) {
                seen++;
                assertThat(name).as("class %s", name).startsWith("linkpage");
            }
        }
        assertThat(seen).isGreaterThan(10);
    }

    /**
     * A button pointing at one of the website's articles carries the article's cover in place of
     * its icon. The covers come keyed by link id from SocialPageReader; this pins what the page
     * does with them -- including that the icon row never shows one.
     */
    @Test
    @DisplayName("an article link on /social shows the article's cover instead of its icon")
    void socialArticleLinkShowsItsCover() throws Exception {
        String html = renderSocial(Map.of("social", socialView("bg", SocialSource.DIRECT, List.of(),
                Map.of("button01", new LinkTarget("/assets/img/articles/cover-3150.jpg", Map.of()),
                        "insta001", new LinkTarget("/assets/img/articles/never-shown.jpg", Map.of())))));

        java.util.regex.Matcher tile = java.util.regex.Pattern
                .compile("<a class=\"linkpage__link linkpage__link--thumb\"[^>]*href=\"/social/go/button01[^\"]*\"[^>]*>(.*?)</a>",
                        java.util.regex.Pattern.DOTALL)
                .matcher(html);
        assertThat(tile.find()).as("button01 carries the thumbnail modifier").isTrue();
        assertThat(tile.group(1))
                .contains("<img class=\"linkpage__link-thumb\" src=\"/assets/img/articles/cover-3150.jpg\"")
                .contains("alt=\"\"")
                // The picture replaces the icon; it is not drawn beside it.
                .doesNotContain("linkpage__link-icon");

        // The icon row is brand marks in circles; a photograph there would read as a broken icon.
        assertThat(html).doesNotContain("never-shown.jpg");

        // The same size as a latest-articles cover -- in the markup, and in the one CSS rule both
        // classes share, so the two cannot drift apart.
        assertThat(tile.group(1)).contains("width=\"96\" height=\"64\"");
        String withLatest = renderSocial(Map.of("social",
                socialView("bg", SocialSource.DIRECT, List.of(article(3150, true, null)))));
        assertThat(withLatest).containsPattern("<img class=\"linkpage__thumb\"[^>]*width=\"96\" height=\"64\"");
        String css = new org.springframework.core.io.ClassPathResource("static/site-assets/site.css")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(css)
                .containsPattern("\\.linkpage__thumb,\\s*\\.linkpage__link-thumb \\{[^}]*width: 96px;[^}]*height: 64px;");
        // A button with no article behind it is unchanged.
        assertThat(html).containsPattern("<a class=\"linkpage__link\"[^>]*href=\"/social/go/button02");
    }

    /**
     * Switching the page's language switches its article links too: each language down the
     * reader's chain asks the link first and then the article, so text the operator wrote wins,
     * and where they wrote none the article's own title in that language stands in -- never the
     * Bulgarian stored on the link when it was added.
     */
    @Test
    @DisplayName("an article link on /social reads in the reader's language, and opens the article in it")
    void socialArticleLinkFollowsTheLanguage() {
        Map<String, LinkTarget> linked = Map.of("article1", new LinkTarget(null,
                Map.of("bg", "Есенният фестивал", "en", "The autumn festival",
                        "de", "Das Herbstfest")));

        String english = renderSocial(Map.of("social", socialView("en", SocialSource.DIRECT, List.of(), linked)));
        assertThat(english)
                .containsPattern("<span class=\"linkpage__link-text\">The autumn festival</span>")
                .doesNotContain("Есенният фестивал")
                // The redirect is told the language, so the article opens in it.
                .contains("href=\"/social/go/article1?src=direct&amp;lang=en\"");

        String german = renderSocial(Map.of("social", socialView("de", SocialSource.DIRECT, List.of(), linked)));
        assertThat(german).contains(">Das Herbstfest<", "href=\"/social/go/article1?src=direct&amp;lang=de\"");

        String bulgarian = renderSocial(Map.of("social", socialView("bg", SocialSource.DIRECT, List.of(), linked)));
        assertThat(bulgarian).contains(">Есенният фестивал<").doesNotContain("The autumn festival");

        // A language the article lacks falls down the chain like everything else. Here the page's
        // own name falls back to English too, so the page is lang="en" and the label needs none.
        String russian = renderSocial(Map.of("social", socialView("ru", SocialSource.DIRECT, List.of(),
                Map.of("article1", new LinkTarget(null, Map.of("bg", "Есенният фестивал", "en", "The autumn festival"))))));
        assertThat(russian).contains("<html lang=\"en\"", "<span class=\"linkpage__link-text\">The autumn festival</span>");

        // Only article links carry the language; another link's href is unchanged.
        assertThat(english).contains("href=\"/social/go/button02?src=direct\"");
    }

    /**
     * Not only articles: a link to a standing page reads in the page's own title wherever the
     * operator wrote nothing in that language, and a link to another site -- nothing to borrow --
     * falls down the chain to English, then Bulgarian.
     */
    @Test
    @DisplayName("a link to a standing page reads in the page's title; an external link falls back")
    void pageLinksFollowTheLanguageToo() {
        Map<String, LinkTarget> targets = Map.of("button01", new LinkTarget(null,
                Map.of("bg", "Абонамент", "en", "Subscription", "de", "Abonnement")));

        // socialView's button01 (/p/subscription) has operator text in bg and en only.
        String german = renderSocial(Map.of("social", socialView("de", SocialSource.DIRECT, List.of(), targets)));
        assertThat(german)
                .contains(">Abonnement<", "href=\"/social/go/button01?src=direct&amp;lang=de\"")
                // The seminar link points at another site: no German, no English, so Bulgarian,
                // marked as such, and its href carries no language.
                .containsPattern("lang=\"bg\"[^>]*>Семинар<")
                .contains("href=\"/social/go/button02?src=direct\"");

        // The operator's English text still wins over the page's English title.
        String english = renderSocial(Map.of("social", socialView("en", SocialSource.DIRECT, List.of(), targets)));
        assertThat(english).contains(">Subscribe<").doesNotContain(">Subscription<");
    }

    /**
     * The switcher offers every language the site does, not only the profile's: the links and the
     * latest articles are translated by their targets, so a German reader has German titles to
     * read even where nobody wrote the bio in German. The one marked current is the language
     * asked for.
     */
    @Test
    @DisplayName("the /social language switcher offers every site language, and marks the one asked for")
    void switcherOffersEverySiteLanguage() {
        SocialPage page = new SocialPage();
        page.setPublished(true);
        page.putName("bg", "Примерен вестник");
        page.putName("en", "Example Gazette");
        List<Language> site = new java.util.ArrayList<>();
        for (String[] l : new String[][]{{"bg", "Български"}, {"en", "English"}, {"de", "Deutsch"}, {"ru", "Русский"}}) {
            Language language = new Language();
            language.setCode(l[0]);
            language.setNativeName(l[1]);
            site.add(language);
        }

        SocialView view = SocialView.of(page, null, Map.of(), "de", LocalDateTime.now(), List.of(), site,
                SocialSource.INSTAGRAM, java.util.Set.of());

        assertThat(view.languages()).extracting(SocialView.LanguageChoice::code).containsExactly("bg", "en", "de", "ru");
        // German was asked for, though the page itself is English (its name has no German).
        assertThat(view.htmlLang()).isEqualTo("en");
        assertThat(view.languages()).filteredOn(SocialView.LanguageChoice::current)
                .extracting(SocialView.LanguageChoice::code).containsExactly("de");
        assertThat(view.languages().get(3).href()).isEqualTo("/social?src=instagram&lang=ru");
    }

    /**
     * The latest articles' titles switch with the page, by the rule the article links use: the
     * reader's language, then English, then Bulgarian -- and a Bulgarian reader always Bulgarian,
     * never the English the listings' ListItem rule tries before the Bulgarian source.
     */
    @Test
    @DisplayName("the latest articles on /social read in the chosen language, falling back like the links")
    void latestArticleTitlesFollowTheLanguage() {
        Article translated = article(3150, true, null);
        translated.setTitle("Есенният фестивал");
        translated.setTitles(List.of(
                TextItem.builder().lang("en").text("The autumn festival").build(),
                TextItem.builder().lang("de").text("Das Herbstfest").build()));
        Article untranslated = article(3151, true, null);
        untranslated.setTitle("Само на български");

        String german = renderSocial(Map.of("social",
                socialView("de", SocialSource.DIRECT, List.of(translated, untranslated))));
        assertThat(german).contains(">Das Herbstfest<")
                // No German and no English: Bulgarian, marked as such.
                .containsPattern("class=\"linkpage__article-title\" lang=\"bg\">Само на български<");

        String bulgarian = renderSocial(Map.of("social",
                socialView("bg", SocialSource.DIRECT, List.of(translated))));
        assertThat(bulgarian).contains(">Есенният фестивал<").doesNotContain("The autumn festival");

        String english = renderSocial(Map.of("social",
                socialView("en", SocialSource.DIRECT, List.of(translated))));
        assertThat(english)
                .contains("<span class=\"linkpage__article-title\">The autumn festival</span>");
    }

    @Test
    @DisplayName("text the operator wrote for a language beats the article's own title in it")
    void operatorTextBeatsTheArticleTitle() {
        SocialView view = socialView("en", SocialSource.DIRECT, List.of(),
                Map.of("article1", new LinkTarget(null, Map.of("en", "The autumn festival"))));
        // socialView's article link has a Bulgarian title only; here the operator adds English.
        assertThat(view.buttons()).filteredOn(t -> t.id().equals("article1")).singleElement()
                .satisfies(t -> assertThat(t.label()).isEqualTo("The autumn festival"));

        SocialLink own = new SocialLink("article9");
        own.putTitle("en", "Read the full story");
        own.setUrl("/article/3150");
        SocialPage page = new SocialPage();
        page.setPublished(true);
        page.getLinks().add(own);
        SocialView typed = SocialView.of(page, null,
                Map.of("article9", new LinkTarget(null, Map.of("en", "The autumn festival"))),
                "en", LocalDateTime.now(), List.of(), List.of(), SocialSource.DIRECT, java.util.Set.of());
        assertThat(typed.buttons()).singleElement()
                .satisfies(t -> assertThat(t.label()).isEqualTo("Read the full story"));
    }

    @Test
    @DisplayName("every SocialIcon has its symbol in the /social sprite")
    void everySocialIconHasASymbol() {
        String html = renderSocial(Map.of("social",
                socialView("bg", SocialSource.DIRECT, List.of())));

        for (SocialIcon icon : SocialIcon.values()) {
            assertThat(html).as(icon.name()).contains("<symbol id=\"" + icon.symbol() + "\"");
        }
        assertThat(html).contains("<use href=\"#si-instagram\"", "<use href=\"#si-email\"");
    }

    /**
     * The error page renders from an empty model.
     *
     * <p>This is the assertion that matters: a template that dereferences an absent attribute
     * throws <em>inside the error dispatch</em>, and what the reader gets then is worse than a bare
     * JSON body. There is no {@code lang}, no {@code topBanners} and no
     * {@code sidebarBanners} at that point, so every fragment parameter is a literal.
     */
    @Test
    @DisplayName("The error page renders with nothing in the model but a status")
    void errorPageNeedsNoModel() {
        String html = renderBare("error", Map.of("status", 404));

        // With no lang in the model the page is in the request's language -- English here.
        assertThat(html).contains("This page does not exist");
        assertThat(html).contains("Error 404");
        // The chrome came along, empty: with no model there is no website to name, and the
        // fragments render around that rather than failing inside the error dispatch.
        assertThat(html).contains("class=\"site-header\"", "class=\"site-footer\"");
        // Nobody bought a booking on an error page, and a render would count impressions.
        assertThat(html).doesNotContain("class=\"partner-card\"");
        assertThat(html).doesNotContain("class=\"partner-row\"");
    }

    /**
     * BasicErrorController resolves the view name {@code error} for every status, not only 404.
     */
    @Test
    @DisplayName("The error page's copy follows the status instead of always saying 'not found'")
    void errorPageCopyFollowsTheStatus() {
        String html = renderBare("error", Map.of("status", 500));

        assertThat(html).contains("Something went wrong");
        assertThat(html).doesNotContain("This page does not exist");

        // And in the reader's language, through the same bundles as every other page.
        String bulgarian = renderBare("error", Map.of("status", 404, "lang", "bg"));
        assertThat(bulgarian).contains("Страницата не съществува", "Грешка 404", "<html lang=\"bg\"");
    }

    /** Belt and braces: an error dispatch that has lost even the status still renders. */
    @Test
    @DisplayName("The error page survives a model with no status at all")
    void errorPageSurvivesAnAbsentStatus() {
        String html = renderBare("error", Map.of());

        assertThat(html).contains("Something went wrong");
    }

    private static ImageMedia image(int id) {
        ImageMedia image = new ImageMedia(id);
        image.setFilename("image-" + id + ".jpg");
        image.setCaption("Описание на снимката " + id);
        image.setContent("image/jpeg");
        return image;
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            total++;
        }
        return total;
    }
}
