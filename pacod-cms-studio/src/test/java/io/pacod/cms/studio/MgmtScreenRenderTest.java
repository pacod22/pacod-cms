package io.pacod.cms.studio;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.services.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders every console screen through the real view resolver.
 *
 * <p>Asserting a view <em>name</em> proves nothing about a Thymeleaf template: a mistyped fragment
 * signature, a property the model does not expose, or a {@code th:field} on a form object that is
 * not bound all blow up at render time and nowhere earlier. The existing
 * {@code LandingTemplateRenderTest} makes the same argument for the public landing page; the
 * edit screens additionally need a bound {@code BindingResult}, which only a real MVC dispatch
 * provides -- hence MockMvc rather than a bare {@code SpringTemplateEngine}.
 *
 * <p>The services are mocked so no MongoDB is required. What is under test here is the templates
 * and the controllers' model, not the persistence.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = "MGMT")
class MgmtScreenRenderTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private io.pacod.cms.studio.i18n.MgmtText mgmtText;

    @Autowired
    private MgmtPresence presence;

    private MockMvc mockMvc;

    // ------------------------------------------------------------------ English shadow render
    //
    // Every test here asserts Bulgarian, the console's default. Each HTML screen a test renders
    // with a GET is remembered and rendered once more, after the test's own assertions and with its
    // stubs still in place, with the mgmt-lang=en cookie -- so every screen this class knows how to
    // build is also checked in English, without a second copy of the stubbing above.
    //
    // What it catches: a key that does not resolve (Thymeleaf's ??key_en??), a screen that does not
    // say lang="en", and Bulgarian interface text left on an English screen -- every Bulgarian
    // bundle value of some length whose English differs must be absent. It cannot catch a Bulgarian
    // literal hardcoded in Java, which is indistinguishable from the Bulgarian test data on the
    // page; MgmtJavaTextTest reads the source for that.

    private record Screen(String path, Map<String, String[]> params) {
    }

    private final List<Screen> renderedScreens = new java.util.ArrayList<>();

    /** Test data above that happens to equal a Bulgarian interface string, word for word. */
    private static final java.util.Set<String> TEST_DATA_EQUAL_TO_UI = java.util.Set.of(
            "Кратко описание", "Acme, пролет 2026", "Примерен вестник");

    private static final List<String> BULGARIAN_UI = bulgarianInterfaceText();

    private void rememberScreen(org.springframework.test.web.servlet.MvcResult result) {
        org.springframework.mock.web.MockHttpServletRequest request = result.getRequest();
        String type = result.getResponse().getContentType();
        boolean english = request.getCookies() != null && java.util.Arrays.stream(request.getCookies())
                .anyMatch(c -> "mgmt-lang".equals(c.getName()));
        if ("GET".equals(request.getMethod()) && !english
                && result.getResponse().getStatus() == 200
                && type != null && type.startsWith("text/html")) {
            renderedScreens.add(new Screen(request.getRequestURI(), Map.copyOf(request.getParameterMap())));
        }
    }

    @org.junit.jupiter.api.AfterEach
    void everyScreenAlsoRendersInEnglish() throws Exception {
        for (Screen screen : List.copyOf(renderedScreens)) {
            var request = get(screen.path()).cookie(new jakarta.servlet.http.Cookie("mgmt-lang", "en"));
            screen.params().forEach(request::param);
            String url = screen.path() + (screen.params().isEmpty() ? "" : "?" + screen.params().keySet());
            String html = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
            soft.assertThat(html).as("%s in English", url).contains("<html lang=\"en\">").doesNotContain("??");
            for (String bulgarian : BULGARIAN_UI) {
                soft.assertThat(html).as("%s in English", url).doesNotContain(bulgarian);
            }
            soft.assertAll();
        }
    }

    /**
     * The Bulgarian interface strings worth looking for: no arguments (so the text is the whole
     * value), long enough not to occur inside test data by accident, and different in English.
     */
    private static List<String> bulgarianInterfaceText() {
        List<String> found = new java.util.ArrayList<>();
        java.util.regex.Pattern cyrillic = java.util.regex.Pattern.compile("\\p{IsCyrillic}");
        for (String basename : io.pacod.cms.studio.i18n.MgmtText.BASENAMES) {
            java.util.Properties bg = io.pacod.cms.studio.i18n.MgmtText.load(basename + "_bg.properties");
            java.util.Properties en = io.pacod.cms.studio.i18n.MgmtText.load(basename + ".properties");
            for (String key : bg.stringPropertyNames()) {
                String value = bg.getProperty(key).replace("''", "'");
                if (key.startsWith("js.") || value.contains("{") || value.contains("<")
                        || value.length() < 12 || !cyrillic.matcher(value).find()
                        || value.equals(en.getProperty(key)) || TEST_DATA_EQUAL_TO_UI.contains(value)) {
                    continue;
                }
                found.add(value);
            }
        }
        return found;
    }

    /** An enum constant's label as the Bulgarian screens these tests render show it. */
    private static String bulgarianLabel(Enum<?> value) {
        return io.pacod.cms.studio.i18n.MgmtText.newMessageSource().getMessage(
                io.pacod.cms.studio.i18n.MgmtText.enumKey(value, "label"), null, java.util.Locale.of("bg"));
    }

    @MockitoBean
    private MgmtArticleService articleService;

    @MockitoBean
    private MgmtAuthorService authorService;

    @MockitoBean
    private MgmtCategoryService categoryService;

    @MockitoBean
    private MgmtBannerService bannerService;

    @MockitoBean
    private MgmtImageService imageService;

    @MockitoBean
    private JobQueue jobQueue;

    /** On in the tests that queue a PDF; off (as the test profile leaves both) everywhere else. */
    @MockitoBean
    private io.pacod.cms.studio.pdf.GotenbergClient gotenberg;

    @MockitoBean
    private io.pacod.cms.core.design.PrintTokens printTokens;

    /** Off by default, as an installation without a gateway is; the translation tests switch it on. */
    @MockitoBean
    private io.pacod.cms.studio.ai.AiAccess aiAccess;

    @MockitoBean
    private MgmtMonitoringService monitoringService;

    @MockitoBean
    private MgmtPageService pageService;

    /**
     * The layout screens read both of these <em>and</em> the filesystem behind them. Mocking the
     * services rather than only the repositories is what keeps the «Файлове» card's rendering
     * dependent on a stub instead of on whatever {@code target/test-banners} happens to contain.
     */
    @MockitoBean
    private MgmtBannerLayoutService layoutService;

    @MockitoBean
    private MgmtBannerSkeletonService skeletonService;

    /**
     * The console's language list is a collection, not an enum, so the page screens reach a
     * repository for their tab strip. Unstubbed, these tests hit a real MongoDB -- which on a
     * developer machine is a container that answers "Unauthorized" rather than nothing at all.
     */
    @MockitoBean
    private MgmtLanguageService languageService;

    /** /mgmt/social. The statistics are answered by the REAL report logic over stubbed buckets --
     *  see stubServices -- so the template is rendered against the shape it really receives. */
    @MockitoBean
    private MgmtSocialService socialService;

    @MockitoBean
    private MgmtSocialStatsService socialStatsService;

    /** /mgmt/crawlers. Like the monitoring screen, the report is the real arithmetic -- see stubCrawlers. */
    @MockitoBean
    private MgmtCrawlerService crawlerService;

    /** The PDF cards' defaults: empty, so the defaults of a fresh website. */
    @MockitoBean
    private io.pacod.cms.core.repository.PdfSettingsRepository pdfSettingsRepository;

    /** /mgmt/issues. */
    @MockitoBean
    private MgmtIssueService issueService;

    private static io.pacod.cms.core.data.Language language(String code, String bulgarian,
                                                                  String nativeName) {
        io.pacod.cms.core.data.Language language = new io.pacod.cms.core.data.Language(code);
        language.setAdminName(bulgarian);
        language.setNativeName(nativeName);
        return language;
    }

    private static Author author() {
        Author author = new Author(7);
        author.setTitle("Проф.");
        author.setFirstname("Мария");
        author.setLastname("Влахова");
        author.setDescription("Дългo описание на автора, което списъкът съкращава.");
        return author;
    }

    private static Category category() {
        Category category = new Category(17);
        category.setName("Двор");
        return category;
    }

    private static Article article() {
        Article article = new Article(42);
        article.setTitle("В стария град през август");
        article.setDescription("Кратко описание");
        article.setText("<p>Текст</p>");
        article.setDate(LocalDateTime.of(2026, 8, 15, 9, 30));
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setCounter(1234);
        article.setPriority(5);
        article.setTags(List.of("градина", "август"));
        // The shape a stored embed takes: a YouTube /embed/ iframe carrying attributes the console
        // does not write itself. The visual view has to recognise it by its src alone -- an
        // attribute-exact comparison against what it emits would drop nearly every stored embed
        // into the HTML view.
        article.setEmbedded("<iframe width=\"560\" height=\"315\" "
                + "src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\" allowfullscreen></iframe>");
        article.setAuthors(List.of(author()));
        article.setCategories(List.of(category()));

        ImageMedia image = new ImageMedia(3);
        image.setFilename("garden.jpg");
        article.setImages(List.of(image));

        return article;
    }

    /** An image booking, so the click column, the upload card and the target URL all render. */
    private static Banner banner() {
        Banner banner = new Banner(5);
        banner.setName("Партньор");
        banner.setType(BannerType.IMAGE);
        banner.setSource("/assets/img/partners/partner.gif");
        banner.setTargetUrl("https://partner.example.net/");
        banner.setAltText("Партньор");
        banner.setPlacement(BannerPlacement.SIDEBAR);
        banner.setSize(BannerSize.AUTO);
        banner.setSortOrder(20);
        banner.setImpressions(4711);
        banner.setClicks(23);
        return banner;
    }

    @BeforeEach
    void stubServices() {
        // springSecurity() installs the real chain and makes @WithMockUser visible to it.
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .alwaysDo(this::rememberScreen)
                .build();
        renderedScreens.clear();

        when(articleService.list(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(article()), PageRequest.of(0, 20), 1));
        when(articleService.find(anyInt())).thenReturn(Optional.of(article()));
        when(articleService.allAuthors()).thenReturn(List.of(author()));
        when(articleService.authorsForPicker(any())).thenReturn(List.of(author()));
        when(articleService.allCategories()).thenReturn(List.of(category()));

        when(authorService.findAll(any(), any(), any())).thenReturn(List.of(author()));
        when(authorService.find(anyInt())).thenReturn(Optional.of(author()));

        when(categoryService.findAll(any(), any(), any(), any())).thenReturn(List.of(category()));
        when(categoryService.find(anyInt())).thenReturn(Optional.of(category()));
        when(categoryService.countArticles(anyInt())).thenReturn(3L);
        when(categoryService.countArticlesByCategory()).thenReturn(java.util.Map.of(17, 3L));


        when(bannerService.findAll(any())).thenReturn(List.of(banner()));
        when(bannerService.find(anyInt())).thenReturn(Optional.of(banner()));

        // Two overloads, one caller each: the editor's picker takes the term-and-ordering one,
        // the library screen the filters-and-ordering one. Every search carries an ordering, so
        // there is no third to stub.
        when(imageService.search(any(), any(MgmtImageService.ImageSort.class), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(imageRow()), PageRequest.of(0, 24), 1));
        when(imageService.search(any(), any(MgmtImageService.ArticleFilter.class), any(),
                any(MgmtImageService.ImageSort.class), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(imageRow()), PageRequest.of(0, 24), 1));
        when(imageService.resolveArticleFilter(any())).thenReturn(MgmtImageService.ArticleFilter.none());
        when(imageService.find(anyInt())).thenReturn(Optional.of(imageMedia()));
        when(imageService.usedBy(anyInt())).thenReturn(List.of(imageUsage()));
        when(imageService.fileOf(any())).thenReturn(
                new MgmtImageService.ImageFile(true, 96_000L, 800, 531, LocalDateTime.now()));

        when(jobQueue.forSite(any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(runningJob())));
        when(jobQueue.find(any(), any())).thenReturn(Optional.of(runningJob()));
        when(jobQueue.counts(any(), any())).thenReturn(Map.of(JobStatus.QUEUED, 3L, JobStatus.RUNNING, 1L, JobStatus.FAILED, 2L));
        when(jobQueue.enqueue(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(runningJob());
        when(aiAccess.refusalHere()).thenReturn(Optional.of(io.pacod.cms.studio.ai.AiRefusal.NOT_SET_UP));
        when(aiAccess.availableHere()).thenReturn(false);
        when(imageService.countCreatedSince(any())).thenReturn(4L);

        stubMonitoring(List.of(bucket(4, 120, 31), bucket(3, 90, 24), bucket(2, 0, 0), bucket(1, 45, 12)));
        stubCrawlers(List.of(crawlerBucket(2, 40, 12, 3), crawlerBucket(1, 25, 8, 1)),
                new MgmtCrawlerService.Watch(false, java.time.Instant.parse("2026-09-01T00:00:00Z"), List.of()));

        when(pageService.findAll(any())).thenReturn(List.of(standingPage()));
        when(pageService.find(any())).thenReturn(Optional.of(standingPage()));

        when(languageService.findAll()).thenReturn(List.of(
                language("bg", "Български", "Български"),
                language("en", "Английски", "English"),
                language("de", "Немски", "Deutsch")));
        when(languageService.find(any())).thenAnswer(call -> {
            String code = call.getArgument(0);
            return languageService.findAll().stream()
                    .filter(l -> l.getCode().equalsIgnoreCase(code))
                    .findFirst();
        });
        when(languageService.pageCounts()).thenReturn(java.util.Map.of("bg", 1, "en", 1));

        SocialPage social = socialPage();
        when(socialService.load()).thenReturn(social);
        when(socialService.exists()).thenReturn(true);
        when(socialService.avatar(any())).thenReturn(Optional.empty());
        when(socialService.publicUrl()).thenReturn("https://www.example.org/social");
        when(socialService.findLink(any())).thenAnswer(call -> social.link(call.getArgument(0)));
        when(socialService.articleThumbs(any())).thenReturn(Map.of("button01",
                new MgmtSocialService.ArticleThumb(3150, "Есенният фестивал", "/assets/img/articles/cover-3150.jpg")));
        MgmtSocialStatsService realStats = new MgmtSocialStatsService(
                org.mockito.Mockito.mock(io.pacod.cms.core.repository.SiteMetricHourRepository.class),
                org.mockito.Mockito.mock(io.pacod.cms.core.repository.SiteMetricDayRepository.class),
                org.mockito.Mockito.mock(io.pacod.cms.core.repository.ArticleRepository.class),
                org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class),
                org.mockito.Mockito.mock(io.pacod.cms.core.repository.SiteSettingsRepository.class));
        // Built by hand, so it has to be handed the resolver the context would have injected --
        // or its chart series and tooltips stay Bulgarian in the English shadow render below.
        realStats.setText(mgmtText);
        when(socialStatsService.series(any())).thenReturn(socialBuckets());
        when(socialStatsService.clicksByLink(any())).thenReturn(Map.of("button01", 7L));
        when(socialStatsService.allTimeClicks()).thenReturn(Map.of("button01", 42L));
        when(socialStatsService.report(any(), any(), any(), any(), any())).thenAnswer(call -> realStats.report(
                call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        when(languageService.usedBy(any())).thenReturn(List.of());

        when(layoutService.findAll(any())).thenReturn(List.of(
                new MgmtBannerLayoutService.LayoutRow(layout(), 1L, true)));
        when(layoutService.find(any())).thenReturn(Optional.of(layout()));
        when(layoutService.require(any())).thenReturn(layout());
        when(layoutService.files(any())).thenReturn(new BannerLayoutStorage.LayoutFiles(
                true, true, 4, 98_000L, LocalDateTime.now(),
                List.of("index.html", "css/banner.css", "js/banner.js", "acme-1.webp")));
        when(layoutService.bookingsUsing(any())).thenReturn(List.of(banner()));
        when(layoutService.suggestedSize(any()))
                .thenReturn(Optional.of(io.pacod.cms.core.data.BannerSize.SQUARE));
        when(layoutService.suggestedPlacement(any()))
                .thenReturn(io.pacod.cms.core.data.BannerPlacement.SIDEBAR);
        when(layoutService.layoutOf(any())).thenReturn(Optional.empty());
        when(layoutService.discover()).thenReturn(List.of(
                new BannerLayoutStorage.Orphan("acme2", "index.html", 5, true),
                new BannerLayoutStorage.Orphan("campaign", null, 2, false)));
        when(layoutService.listFiles(any())).thenReturn(List.of(
                new BannerLayoutStorage.LayoutFile("index.html", 1785L, true, false),
                new BannerLayoutStorage.LayoutFile("js/banner.js", 620L, true, false),
                new BannerLayoutStorage.LayoutFile("acme-1.webp", 28_000L, false, true),
                new BannerLayoutStorage.LayoutFile("fonts/x.woff2", 9_000L, false, false)));
        when(layoutService.fileText(any(), any())).thenReturn("var clickTag = \"https://old.example\";");

        when(skeletonService.findAll()).thenReturn(List.of(skeleton()));
        when(skeletonService.findAll(any())).thenReturn(List.of(skeleton()));
        when(skeletonService.find(any())).thenReturn(Optional.of(skeleton()));
        when(skeletonService.require(any())).thenReturn(skeleton());
        when(skeletonService.sourceOf(any())).thenReturn(
                "<html><head><link rel=stylesheet href=\"css/banner.css\"></head><body>"
                        + "<div class=\"banner-container-inline\"></div>"
                        + "<script src=\"js/banner.js\"></script><script>banner();</script></body></html>");
    }

    /** A two-slide sidebar layout, the common shape of a built layout's folder. */
    private static io.pacod.cms.core.data.BannerLayout layout() {
        io.pacod.cms.core.data.BannerLayout layout =
                new io.pacod.cms.core.data.BannerLayout("acme-2026");
        layout.setTitle("Acme, пролет 2026");
        layout.setSkeleton("sidebar");
        layout.setWidth(300);
        layout.setHeight(250);
        layout.setGtmId("GTM-TEST4321");

        io.pacod.cms.core.data.BannerSlide first =
                new io.pacod.cms.core.data.BannerSlide("acme-1.webp");
        first.setHref("https://www.example.com/acme/");
        first.setTitle("Acme Supplies");
        first.setAlt("Acme");

        io.pacod.cms.core.data.BannerSlide second =
                new io.pacod.cms.core.data.BannerSlide("acme-2.webp");
        second.setHref("https://www.example.com/acme/product-three");

        layout.getSlides().add(first);
        layout.getSlides().add(second);
        return layout;
    }

    private static io.pacod.cms.core.data.BannerSkeleton skeleton() {
        io.pacod.cms.core.data.BannerSkeleton skeleton =
                new io.pacod.cms.core.data.BannerSkeleton();
        skeleton.setId("sidebar");
        skeleton.setTitle("Странично 300x250");
        skeleton.setWidth(300);
        skeleton.setHeight(250);
        skeleton.setBuiltIn(true);
        skeleton.setGtmId("GTM-TEST4321");
        skeleton.setCss(".banner-container-inline { overflow: hidden; }");
        return skeleton;
    }

    /** A page translated into two languages, so the coverage column has something to show. */
    private static io.pacod.cms.core.data.Page standingPage() {
        io.pacod.cms.core.data.Page page = new io.pacod.cms.core.data.Page("terms");
        page.setOfficialInDefaultLanguageOnly(true);
        page.putTitle("bg", "Условия за ползване");
        page.putBody("bg", "<p>Условията</p>");
        page.putTitle("en", "Terms of use");
        page.putBody("en", "<p>The terms</p>");
        return page;
    }

    /**
     * The monitoring screen reads nine things, and a null from any of them is a render failure
     * rather than an empty section -- so they are stubbed together.
     *
     * <p>The bookings are three on purpose: an image banner, a same-origin HTML5 one whose clicks
     * site.js counts, and an off-origin HTML5 one that nothing can count -- the only row that
     * must still show a dash.
     */
    private void stubMonitoring(List<MgmtMonitoringService.Bucket> buckets) {
        when(monitoringService.series(any())).thenReturn(buckets);
        when(monitoringService.liveVisitors()).thenReturn(new MgmtMonitoringService.LiveCount(7, 5, true));
        when(monitoringService.totals(any())).thenReturn(new MgmtMonitoringService.Totals(255, 31));
        when(monitoringService.bookings()).thenReturn(List.of(
                monitoredBanner(3, "Акме", BannerType.IMAGE, "/assets/img/partners/spring.gif"),
                monitoredBanner(4, "Интерактивна", BannerType.HTML5, "/resources/banners/acme2/index.html"),
                monitoredBanner(5, "Чужд сървър", BannerType.HTML5, "https://ads.example.com/b/index.html")));
        // The report is the real arithmetic over the buckets below, for the reason the charts are.
        doCallRealMethod().when(monitoringService).bannerReport(any(), any(), any());
        // doCallRealMethod, not when(...).thenCallRealMethod(): the when() form evaluates the
        // call on the mock, which on a second stubbing would run the real method against a null
        // argument. The real geometry is what this test is for -- a mocked chart would render
        // whatever it was handed and prove nothing about MgmtChart.
        doCallRealMethod().when(monitoringService).trafficChart(any());
        doCallRealMethod().when(monitoringService).bannerChart(any(), any(), any());
        when(monitoringService.trafficByPage(any())).thenReturn(List.of(
                new MgmtMonitoringService.PageTraffic(io.pacod.cms.core.data.BannerPage.ARTICLE,
                        "Статия", 200L, 78.4d)));
        when(monitoringService.topArticles(anyInt())).thenReturn(List.of(
                new MgmtMonitoringService.TopArticle(42, "Новият мост над реката", 4321L)));
        when(monitoringService.contentTotals())
                .thenReturn(new MgmtMonitoringService.ContentTotals(3150, 31, 18, 1400, 8));
    }

    /**
     * One hour of traffic. Booking 3 is served five times per view and clicked once per twenty;
     * booking 4 three times and once per forty; booking 5 is served and seen but has no clicks to
     * report. Every booking is viewable on a third of what it was served.
     */
    private static MgmtMonitoringService.Bucket bucket(int hoursAgo, long views, long visitors) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 14, 0).minusHours(hoursAgo);
        return new MgmtMonitoringService.Bucket(start, "14:00", "09.08 14:00",
                views, visitors, views * 8, views / 20, java.util.Map.of("ARTICLE", views),
                java.util.Map.of("3", views * 6, "4", views * 3, "5", views * 3),
                java.util.Map.of("3", views * 2, "4", views, "5", views),
                java.util.Map.of("3", views * 3 / 20, "4", views * 3 / 40));
    }

    /**
     * The crawler screen reads six things. The report is the real arithmetic over the buckets, for
     * the reason the monitoring charts are: a mocked chart would render whatever it was handed.
     */
    private void stubCrawlers(List<MgmtCrawlerService.Bucket> buckets, MgmtCrawlerService.Watch watch) {
        java.time.Instant seen = java.time.Instant.now().minus(java.time.Duration.ofMinutes(12));
        when(crawlerService.series(any())).thenReturn(buckets);
        when(crawlerService.lastSeen()).thenReturn(java.util.Map.of("googlebot", seen));
        doCallRealMethod().when(crawlerService).report(any(), any());
        when(crawlerService.watch(any())).thenReturn(watch);
        when(crawlerService.topPaths(any())).thenReturn(List.of(
                new MgmtCrawlerService.PathRow("/robots.txt", 31, 0),
                new MgmtCrawlerService.PathRow("/article/999999", 4, 4)));
        when(crawlerService.log(any(), any(), anyInt())).thenAnswer(call -> new MgmtCrawlerService.LogPage(
                List.of(crawlerVisit("googlebot", io.pacod.cms.core.data.CrawlerCategory.SEARCH,
                                io.pacod.cms.core.data.CrawlerVerification.UNVERIFIED, "/article/5", 200, seen),
                        crawlerVisit("other", io.pacod.cms.core.data.CrawlerCategory.OTHER,
                                io.pacod.cms.core.data.CrawlerVerification.UNKNOWN, "/robots.txt", 404, seen)),
                call.getArgument(2), true));
    }

    /** One hour of crawling: Googlebot, GPTBot and an unnamed script, one forged Googlebot visit. */
    private static MgmtCrawlerService.Bucket crawlerBucket(int hoursAgo, long google, long gpt, long other) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 14, 0).minusHours(hoursAgo);
        return new MgmtCrawlerService.Bucket(start, "14:00", "09.08 14:00",
                java.util.Map.of("googlebot", google, "gptbot", gpt, "other", other),
                java.util.Map.of("googlebot", 2L),
                java.util.Map.of("googlebot", google - 1),
                java.util.Map.of("googlebot", 1L));
    }

    private static io.pacod.cms.core.data.CrawlerVisit crawlerVisit(
            String bot, io.pacod.cms.core.data.CrawlerCategory category,
            io.pacod.cms.core.data.CrawlerVerification verification, String path, int status,
            java.time.Instant at) {
        io.pacod.cms.core.data.CrawlerVisit visit = new io.pacod.cms.core.data.CrawlerVisit();
        visit.setBot(bot);
        visit.setCategory(category);
        visit.setVerification(verification);
        visit.setPath(path);
        visit.setStatus(status);
        visit.setMethod("GET");
        visit.setIp("203.0.113.7");
        visit.setUserAgent("Mozilla/5.0 (compatible; Googlebot/2.1)");
        visit.setOccurredAt(at);
        return visit;
    }

    /** Created long before the fixture buckets, so the id-reuse guard keeps every one of them. */
    private static Banner monitoredBanner(int id, String name, BannerType type, String source) {
        Banner banner = new Banner(id);
        banner.setName(name);
        banner.setType(type);
        banner.setSource(source);
        banner.setCreatedOn(LocalDateTime.of(2026, 1, 1, 0, 0));
        return banner;
    }

    /**
     * A job mid-run: the state that has a live log, a progress counter and a cancel button, and
     * the only one on which the poll is armed.
     */
    static final String JOB_ID = "0123456789abcdef01234567";

    private static Job runningJob() {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setSiteId("test");
        job.setType(JobType.PDF_ARTICLES);
        job.setParams(new java.util.LinkedHashMap<>(Map.of("ids", "12,14")));
        job.setStatus(JobStatus.RUNNING);
        job.setQueuedOn(java.time.Instant.parse("2026-10-16T10:00:00Z"));
        job.setStartedOn(java.time.Instant.parse("2026-10-16T10:00:05Z"));
        job.setQueuedBy("admin@example.com");
        job.setAttempt(1);
        job.setDone(1);
        job.setTotal(4);
        job.setLog("10:00:05  article 12 (bg): <b>48 KB</b>\n");
        job.setLogChars(job.getLog().length());
        return job;
    }

    private static Job queuedJob() {
        Job job = runningJob();
        job.setStatus(JobStatus.QUEUED);
        job.setStartedOn(null);
        job.setDone(0);
        job.setTotal(0);
        job.setLog(null);
        job.setLogChars(0);
        return job;
    }

    private static Job finishedJob() {
        Job job = runningJob();
        job.setStatus(JobStatus.FAILED);
        job.setFinishedOn(java.time.Instant.parse("2026-10-16T10:01:00Z"));
        job.setError("1 of 4 failed; the log says which");
        return job;
    }

    /**
     * An article with three images: the first hidden, so the cover badge has somewhere wrong to
     * land if it is written as an index test rather than a comparison.
     */
    private static Article articleWithHiddenLeadImage() {
        Article article = article();

        ImageMedia hidden = new ImageMedia(3);
        hidden.setFilename("garden.jpg");
        ImageMedia cover = new ImageMedia(4);
        cover.setFilename("oldtown.jpg");
        ImageMedia third = new ImageMedia(5);
        third.setFilename("apples.jpg");

        article.setImages(List.of(hidden, cover, third));
        article.setHiddenImageIds(List.of(3));
        return article;
    }

    private static MgmtImageService.ImageRow imageRow() {
        return new MgmtImageService.ImageRow(
                9, "shared.jpg", "MultipartFile resource [file]", "/assets/img/articles/shared.jpg", 2);
    }

    private static ImageMedia imageMedia() {
        ImageMedia image = new ImageMedia(9);
        image.setFilename("shared.jpg");
        image.setContent("image/jpeg");
        image.setCaption("MultipartFile resource [file]");
        return image;
    }

    private static MgmtImageService.ImageUsage imageUsage() {
        return new MgmtImageService.ImageUsage(
                42, "В стария град през август", ArticleStatus.PUBLISHED,
                LocalDateTime.of(2024, 8, 1, 9, 0));
    }

    @Test
    @DisplayName("the article list renders its rows, filters and chrome")
    void rendersArticleList() throws Exception {
        mockMvc.perform(get("/mgmt/articles"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("В стария град през август")))
                .andExpect(content().string(containsString("/assets/img/articles/garden.jpg")))
                // The sidebar and topbar fragments resolved.
                .andExpect(content().string(containsString("/mgmt/logout")))
                .andExpect(content().string(containsString("mgmt-assets/mgmt.css")))
                // The tab icon. Declared rather than left to convention: with no <link rel="icon">
                // the browser asks for /favicon.ico at the root of the host, which the console does
                // not serve, so the console tab would carry no mark of its own. That failure is
                // invisible from inside the page, which is why it is pinned here.
                .andExpect(content().string(containsString("mgmt-assets/favicon.svg")))
                // The sidebar collapse button, and the label span the collapsed icon rail clips.
                // CSS cannot address a bare text node, so without the span the 68px rail would
                // either keep the labels or lose the links' accessible names.
                .andExpect(content().string(containsString("id=\"nav-collapse\"")))
                .andExpect(content().string(containsString("<span class=\"nav-text\">Статии</span>")))
                // The jobs link, offered to whoever may read content (here a platform admin).
                .andExpect(content().string(containsString("<span class=\"nav-text\">Задачи</span>")));
    }

    @Test
    @DisplayName("the article editor binds every field it claims to edit")
    void rendersArticleEditor() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // th:field on a form object that is not bound fails at render time, so reaching these
        // strings at all is most of the assertion.
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"title\"")
                .contains("id=\"text\"")
                .contains("id=\"authorIds\"")
                .contains("id=\"categoryIds\"")
                .contains("id=\"status\"")
                .contains("id=\"tags\"")
                .contains("2026-08-15T09:30")
                // Tags round-trip as a comma-separated string.
                .contains("градина, август")
                // The image card and its own delete form, outside the main form.
                .contains("/mgmt/articles/42/images/3/delete")
                // The upload form's limits come from ImageService, not from a hardcoded string.
                // The whole clause, not just the numbers: each one promises a refusal, and with
                // its flag off there is no refusal to promise. The two switches are independent,
                // so this is the both-on corner; DimensionCheckOff and FileSizeCheckOff below are
                // the other two, and their mixed forms are ImageServiceValidationTest's.
                .contains("поне 600×400 px, 3:2, до 128 KB, най-много 10 на статия")
                // The editor is vendored in this jar, never loaded off a storage volume where its
                // version, its CVEs and whether it still resolves its own files go unseen.
                .contains("/mgmt-assets/tinymce/tinymce.min.js")
                // The slot mgmt.js fills when opening the article rewrote its HTML.
                .contains("id=\"fidelity-warning\"")
                // The two writing-mode controls. Both are `hidden` until mgmt.js enables them, and
                // both must be type="button": they sit inside the article form, where a bare
                // <button> saves the article.
                .contains("id=\"editor-fullscreen\"")
                .contains("id=\"editor-wide\"")
                // The video field is two views of ONE control: #embedded keeps its id, its name and
                // its th:field in both, and the visual view is layered over it exactly as the
                // pickers are layered over their <select multiple>.
                .contains("id=\"embedded\"")
                .containsOnlyOnce("name=\"embedded\"")
                // The stored markup round-trips into the textarea untouched. Opening an article
                // must never rewrite this field.
                .contains("&lt;iframe")
                .contains("id=\"embed-modes\"")
                .contains("id=\"embed-mode-visual\"")
                .contains("id=\"embed-mode-html\"")
                .contains("id=\"embed-url\"")
                .contains("id=\"embed-preview\"")
                // The address box must never acquire a name. ArticleForm has one field for this and
                // MgmtArticleService.save writes the fields the form carries, so a second parameter
                // would be silently ignored today and silently authoritative tomorrow.
                .doesNotContain("name=\"embed-url\"");
    }

    @Test
    @DisplayName("the author ordering card ships no controls of its own")
    void authorOrderCardShipsNoStaticControls() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"author-order\"")
                .contains("id=\"author-order-list\"")
                .contains("id=\"author-order-empty\"");

        // Every row and every button is built by mgmt.js. Arrows in the markup that need a script
        // to work are dead controls with scripting off -- and this card sits OUTSIDE
        // <form id="article-form">, so a button here that reached the form would save the article.
        // Asserting the whole page has no buttons is impossible; slice the card and check that.
        int start = html.indexOf("<div class=\"card\" id=\"author-order\"");
        org.assertj.core.api.Assertions.assertThat(start).isGreaterThan(-1);
        String card = html.substring(start, html.indexOf("</div>", html.indexOf("author-order-empty")));

        org.assertj.core.api.Assertions.assertThat(card)
                // Shipped `hidden`, and mgmt.js is what takes the attribute off. Without it a
                // scripting-off operator gets a permanent empty card with a heading and a note,
                // and the native <select multiple> in the rail is what they actually use.
                .startsWith("<div class=\"card\" id=\"author-order\" hidden")
                .doesNotContain("<button")
                .doesNotContain("data-move")
                // No name anywhere in it, ever: #authorIds carries the binding and the order is the
                // order of its <option> elements. A second parameter would be silently ignored by
                // MgmtArticleService.save today and silently authoritative tomorrow.
                .doesNotContain("name=");
    }

    @Test
    @DisplayName("the author options render in the order the service returns, not alphabetically")
    void authorOptionsFollowTheServiceOrder() throws Exception {
        Author second = new Author(8);
        second.setFirstname("Иван");
        second.setLastname("Иванов");

        Article article = article();
        article.setAuthors(List.of(author(), second));

        when(articleService.find(anyInt())).thenReturn(Optional.of(article));
        // Alphabetically this is [8, 7]; the article carries [7, 8] and that is what the picker
        // must render, because a <select multiple> submits its options in TREE order -- so the
        // option order is the only place the byline's order can live.
        when(articleService.authorsForPicker(any())).thenReturn(List.of(author(), second));

        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int select = html.indexOf("id=\"authorIds\"");
        int first = html.indexOf("value=\"7\"", select);
        int next = html.indexOf("value=\"8\"", select);

        org.assertj.core.api.Assertions.assertThat(first).isGreaterThan(-1);
        org.assertj.core.api.Assertions.assertThat(next).isGreaterThan(first);
    }

    /**
     * An article with translations in {@code texts}: two real ones, a blank one, and an entry for
     * the original ({@code bg}), which stored data can carry beside the scalar body.
     */
    private static Article translatedArticle() {
        Article article = article();
        article.setTexts(List.of(
                new TextItem("<p>english</p>", "en"),
                new TextItem("<p>deutsch</p>", "de"),
                // The source. Stored data can carry it, but it is never a translation -- see
                // Article.translatedIn().
                new TextItem("<p>\u0431\u044a\u043b\u0433\u0430\u0440\u0441\u043a\u0438</p>", "bg"),
                // Written but empty: a language a translation job has not got to yet.
                new TextItem("   ", "fr")));
        return article;
    }

    @Test
    @DisplayName("the article list counts and names the languages an article is translated into")
    void articleListShowsTranslationLanguages() throws Exception {
        when(articleService.list(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(translatedArticle()), PageRequest.of(0, 20), 1));

        String html = mockMvc.perform(get("/mgmt/articles"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // A band under the row, not a column: a capped column wraps the chips into a
                // tall stack and stretches every translated row.
                .doesNotContain("<th>\u0415\u0437\u0438\u0446\u0438</th>")
                .contains("class=\"lang-row\"")
                .contains("has-langs")
                // The number as well as the chips: a dozen two-letter chips cannot be counted by
                // eye, and the count is what a scan down the list is actually reading.
                .contains("<span class=\"lang-count\">2 \u0435\u0437\u0438\u043a\u0430</span>")
                .contains(">DE<")
                .contains(">EN<")
                // Each chip opens that translation.
                .contains("href=\"/mgmt/articles/42/translations/de\"")
                // Neither the source language nor a language whose text is blank. Both render
                // exactly like a real translation, answer 200 and pass every other assertion here.
                // Scoped to the chips: the console's own language switch says BG on every screen.
                .doesNotContain("class=\"chip\" href=\"/mgmt/articles/42/translations/bg\"")
                .doesNotContain(">FR<");
    }

    @Test
    @DisplayName("an article with no translations gets no language band")
    void articleListShowsNoTranslations() throws Exception {
        // The default fixture carries no texts at all.
        String html = mockMvc.perform(get("/mgmt/articles"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // The article row itself still renders; only the band is absent.
                .contains("data-href=\"/mgmt/articles/")
                .doesNotContain("class=\"lang-row\"")
                .doesNotContain("has-langs")
                .doesNotContain("class=\"lang-count\"");
    }

    @Test
    @DisplayName("the editor names the article's translations and never binds them")
    void articleEditorShowsTranslations() throws Exception {
        when(articleService.find(anyInt())).thenReturn(Optional.of(translatedArticle()));

        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The whole card: from its heading to the start of the one that follows it in the rail.
        String card = html.substring(html.indexOf("\u041f\u0440\u0435\u0432\u043e\u0434\u0438"),
                html.indexOf("id=\"image-library\""));

        org.assertj.core.api.Assertions.assertThat(card)
                // Bulgarian plural: "1 \u0435\u0437\u0438\u043a", "2 \u0435\u0437\u0438\u043a\u0430".
                .contains("2 \u0435\u0437\u0438\u043a\u0430")
                .contains(">DE<")
                .contains(">EN<")
                .doesNotContain(">BG<")
                // Read-only, and it has to stay that way: MgmtArticleService.save applies the form
                // onto the stored document, so a named control here would let one console save
                // wipe every translation, with no undo and nothing on screen to show for it.
                .doesNotContain("name=");
    }

    @Test
    @DisplayName("the editor says so plainly when there is nothing translated yet")
    void articleEditorShowsNoTranslations() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("0 \u0435\u0437\u0438\u043a\u0430")
                .contains("\u043d\u0435 \u0435 \u043f\u0440\u0435\u0432\u0435\u0434\u0435\u043d\u0430 \u043d\u0430 \u043d\u0438\u0442\u043e \u0435\u0434\u0438\u043d \u0435\u0437\u0438\u043a");
    }

    @Test
    @DisplayName("the editor links each translation to its own screen")
    void articleEditorLinksToTranslations() throws Exception {
        when(articleService.find(anyInt())).thenReturn(Optional.of(translatedArticle()));

        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("href=\"/mgmt/articles/42/translations/de\"")
                .contains("href=\"/mgmt/articles/42/translations/en\"")
                .doesNotContain("/translations/bg")
                .doesNotContain("/translations/fr")
                // The strip names them from the language collection.
                .contains(">\u041d\u0435\u043c\u0441\u043a\u0438<")
                // One wrapping row at the foot of the text card, not a card of its own.
                .contains("class=\"card-foot lang-strip\"");

        // Inside the text card: after the body, before the rail begins.
        int strip = html.indexOf("lang-strip");
        org.assertj.core.api.Assertions.assertThat(strip)
                .isGreaterThan(html.indexOf("id=\"embedded\""))
                .isLessThan(html.indexOf("class=\"editor-rail\""));
    }

    @Test
    @DisplayName("an article with no translations shows no language strip")
    void articleEditorWithoutTranslationsHasNoStrip() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .doesNotContain("/translations/")
                .doesNotContain("\u0411\u044a\u043b\u0433\u0430\u0440\u0441\u043a\u0438 (\u043e\u0440\u0438\u0433\u0438\u043d\u0430\u043b)");
    }

    @Test
    @DisplayName("the translation screen loads that language's raw text into the editor's ids")
    void translationScreenRenders() throws Exception {
        Article article = translatedArticle();
        article.setTitles(List.of(new TextItem("English title", "en")));
        when(articleService.find(anyInt())).thenReturn(Optional.of(article));

        String html = mockMvc.perform(get("/mgmt/articles/42/translations/en"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // The ids mgmt.js binds TinyMCE, the save bar and the dirty guard to.
                .contains("id=\"article-form\"")
                .contains("id=\"text\"")
                .contains("tinymce.min.js")
                .contains("action=\"/mgmt/articles/42/translations/en\"")
                .contains("English title")
                .contains("&lt;p&gt;english&lt;/p&gt;")
                // Back to the original's editor.
                .contains("href=\"/mgmt/articles/42\"")
                .contains("action=\"/mgmt/articles/42/translations/en/delete\"");

        // Only the three texts are posted: the rail is not on this screen, so a translation save
        // cannot write the article's classification.
        int start = html.indexOf("id=\"article-form\"");
        String form = html.substring(start, html.indexOf("</form>", start));
        org.assertj.core.api.Assertions.assertThat(form)
                .contains("name=\"title\"")
                .contains("name=\"description\"")
                .contains("name=\"text\"")
                .doesNotContain("name=\"authorIds\"")
                .doesNotContain("name=\"categoryIds\"")
                .doesNotContain("name=\"status\"");
    }

    @Test
    @DisplayName("Bulgarian and untranslated languages have no translation screen")
    void translationScreenRefusesBulgarianAndMissingLanguages() throws Exception {
        when(articleService.find(anyInt())).thenReturn(Optional.of(translatedArticle()));

        mockMvc.perform(get("/mgmt/articles/42/translations/bg")).andExpect(status().isNotFound());
        // Written, but blank -- not a translation.
        mockMvc.perform(get("/mgmt/articles/42/translations/fr")).andExpect(status().isNotFound());
        mockMvc.perform(get("/mgmt/articles/42/translations/it")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("saving a translation requires CSRF, and a blank body re-renders instead of saving")
    void translationSaveRequiresCsrf() throws Exception {
        when(articleService.find(anyInt())).thenReturn(Optional.of(translatedArticle()));

        mockMvc.perform(post("/mgmt/articles/42/translations/en")
                        .param("title", "t").param("text", "<p>x</p>"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/articles/42/translations/en")
                        .param("title", "t").param("text", "<p>x</p>").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/articles/42/translations/en"));
        verify(articleService).saveTranslation(eq(42), eq("en"), any());

        mockMvc.perform(post("/mgmt/articles/42/translations/en")
                        .param("title", "t").param("text", " ").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"text\"")));
        verify(articleService, times(1)).saveTranslation(any(), any(), any());
    }

    @Test
    @DisplayName("deleting a translation returns to the Bulgarian editor")
    void translationDeleteRedirects() throws Exception {
        when(articleService.removeTranslation(42, "de")).thenReturn("de");

        mockMvc.perform(post("/mgmt/articles/42/translations/de/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/articles/42"))
                .andExpect(flash().attributeExists("message"));
    }

    @Test
    @DisplayName("the editor is one grid, so the images sit directly under the text")
    void articleEditorIsASingleGrid() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // TWO grids is the bug. With the form as the first one and the image, restore and delete
        // cards in a second below it -- the rail being the taller column and the grid
        // align-items: start -- the row holding the body is as tall as the rail and the images
        // card starts a few hundred pixels below the text.
        //
        // Deliberately not an index comparison of article-images against text: that passes against
        // the two-grid markup too. Grid placement is what differs, never DOM order, so only the
        // count can see it.
        org.assertj.core.api.Assertions.assertThat(html)
                .containsOnlyOnce("class=\"editor-grid\"")
                // The form generates no box (display: contents), which is how it spans both columns
                // while the cards that are their own POSTs stay a sibling outside it.
                .contains("class=\"editor-form\"")
                .contains("class=\"editor-tail\"");
    }

    @Test
    @DisplayName("the upload form still posts on its own, and carries what mgmt.js needs to intercept it")
    void uploadFormKeepsItsFallbackAndItsAsyncGate() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int start = html.indexOf("<form method=\"post\" enctype=\"multipart/form-data\" id=\"image-form\"");
        org.assertj.core.api.Assertions.assertThat(start).isGreaterThan(-1);
        String form = html.substring(start, html.indexOf("</form>", start));

        org.assertj.core.api.Assertions.assertThat(form)
                // The enhancement is a wrapper over this form, never a replacement: with scripting
                // off «Качи» posts here and gets the redirect. Dropping the action because "the
                // fetch handles it" would leave the no-script path posting to this very page and
                // answering 200 with nothing uploaded.
                .contains("action=\"/mgmt/articles/42/images\"")
                // What scopes the interception to this screen. banner-edit.html and
                // banner-layout-edit.html reuse the same #image-form / #dropzone / #image-file
                // trio and both of them want the redirect, so the gate is load-bearing -- and it
                // is an attribute rather than a guess about which page this is precisely so that
                // it can be pinned here.
                .contains("data-async")
                // Without `multiple` the drop handler's FileList still reaches the input and only
                // the first file is ever posted, silently.
                .contains("multiple")
                // The status line mgmt.js writes progress, refusals and the "wait for the upload"
                // message into. Rendered server-side and empty, like #arrange-status.
                .contains("id=\"upload-status\"")
                .contains("id=\"upload-submit\"");
    }

    @Test
    @DisplayName("the cover badge follows the first VISIBLE image, not images[0]")
    void coverBadgeSkipsHiddenImages() throws Exception {
        when(articleService.find(anyInt())).thenReturn(Optional.of(articleWithHiddenLeadImage()));

        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Asserting only that a badge exists somewhere would pass against an index test, which is
        // the way to get this wrong: the loop walks stored order, hidden images included.
        String cover = "<span class=\"image-badge cover\">главна</span>";
        org.assertj.core.api.Assertions.assertThat(html).containsOnlyOnce(cover);

        int hiddenCard = html.indexOf("data-image=\"3\"");
        int coverCard = html.indexOf("data-image=\"4\"");
        int thirdCard = html.indexOf("data-image=\"5\"");
        int badge = html.indexOf(cover);

        // Between the second card and the third: that is image 4, the first one not hidden.
        org.assertj.core.api.Assertions.assertThat(hiddenCard).isLessThan(coverCard);
        org.assertj.core.api.Assertions.assertThat(badge).isBetween(coverCard, thirdCard);

        // And the hidden one says so.
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("class=\"image-card is-hidden\"");
    }

    @Test
    @DisplayName("every attached image offers its path, its order and its visibility")
    void imageCardsCarryTheirControls() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // The copyable path. A readonly input rather than text plus a copy button:
                // navigator.clipboard is undefined on a plaintext origin, and the local stack is
                // reached over plaintext, so selecting the field has to work on its own.
                .contains("<input class=\"path\" type=\"text\" readonly")
                .contains("value=\"/assets/img/articles/garden.jpg\"")
                // Buttons, not only a drag handle: dragging is unreachable from a keyboard and
                // unusable on a touch screen.
                .contains("data-move=\"up\"")
                .contains("data-move=\"down\"")
                .contains("data-toggle-hidden")
                .contains("data-insert")
                // The card the fetch endpoints act on has to name the article and the image.
                .contains("data-image=\"3\"")
                .contains("data-path=\"/assets/img/articles/garden.jpg\"");
    }

    @Test
    @DisplayName("the editor's image library is present and cannot disturb the article form")
    void rendersImageLibrary() throws Exception {
        String html = mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"image-library\"")
                .contains("id=\"library-list\"")
                // NOT id="search-input": mgmt.js debounces that id and auto-submits its enclosing
                // form, which here is the article.
                .contains("id=\"library-search\"")
                // The card sits inside <form id="article-form">, where the unsaved-changes tracker
                // listens for bubbling input/change. Without this, typing a search term would arm
                // the beforeunload prompt on an article nobody edited -- which is how operators
                // learn to click through the one prompt that matters.
                .contains("data-no-dirty");

        // No nested form: nesting one is illegal HTML and the browser would drop it silently.
        String library = html.substring(html.indexOf("id=\"image-library\""));
        library = library.substring(0, library.indexOf("</form>"));
        org.assertj.core.api.Assertions.assertThat(library)
                .doesNotContain("<form")
                /*
                 * The ordering buttons and «Уголеми» are built by mgmt.js and are not in the
                 * markup. Controls in the markup that need a script to work outlive their code.
                 * Here they would also outlive their *server* -- an ordering is a reload of a
                 * paged list, so a button rendered here with scripting off would reorder nothing
                 * at all.
                 *
                 * Equally, nothing in this card may carry a `name`: it is inside
                 * <form id="article-form"> while collapsed and lifted out of it while expanded, so
                 * a named control would post in one state and vanish in the other.
                 */
                .doesNotContain("segmented")
                .doesNotContain("data-sort")
                // The slice runs to the article form's close and the library is the last card in
                // it, so this covers the card plus its closing tags -- deliberately a little wider
                // than the card, because the rule is about the form and not about the card.
                .doesNotContain("name=");
    }

    @Test
    @DisplayName("the library screen pages over an ordered collection, not over natural order")
    void imageLibraryScreenIsOrdered() throws Exception {
        mockMvc.perform(get("/mgmt/images?page=2")).andExpect(status().isOk());

        // Unsorted paging has no defined order between pages: an image can be listed twice and
        // another never at all, and «Към списъка» on the detail screen carries `page` back to a
        // page that would then hold different rows. Same key and direction as the editor's picker.
        verify(imageService).search(any(), any(MgmtImageService.ArticleFilter.class), any(),
                eq(MgmtImageService.ImageSort.ID), eq(false), any());
    }

    @Test
    @DisplayName("the library screen's columns sort, and the headers are links rather than a script")
    void imageLibraryScreenColumnsSort() throws Exception {
        String html = mockMvc.perform(get("/mgmt/images?q=gard&article=42&usage=USED&size=48&sort=name"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(imageService).search(any(), any(MgmtImageService.ArticleFilter.class), any(),
                eq(MgmtImageService.ImageSort.NAME), eq(false), any());

        org.assertj.core.api.Assertions.assertThat(html)
                // Real links, so a click reorders on the server and the result is a URL. A
                // client-side table sorter would order the 48 rows on screen and call it an
                // ordering of a paged library.
                //
                // The active column reverses; an inactive one starts ascending.
                .contains("sort=name&amp;dir=desc")
                .contains("aria-sort=\"ascending\"")
                // Every header carries all three filters and the size: dropping one would clear
                // the operator's filter on the first click and answer 200 with the whole library.
                .contains("q=gard&amp;article=42&amp;usage=USED&amp;size=48&amp;sort=id");

        // `page` deliberately does not ride along -- a reordered list has different rows on page 4,
        // so the first page is the only honest landing place. Asserted over the <thead> alone: the
        // rows below it link to each image's detail screen and DO carry the page, so a
        // doesNotContain over the whole document would either pass for the wrong reason or fail
        // against correct markup, depending only on which column the test happened to request.
        String head = html.substring(html.indexOf("<thead>"), html.indexOf("</thead>"));
        org.assertj.core.api.Assertions.assertThat(head)
                .contains("sort=id").contains("sort=name").contains("sort=articles")
                .doesNotContain("page=");

        // The search form, «Изчисти», the pager and the size form all carry it, or the first
        // keystroke throws the ordering away: a GET form replaces the whole query string.
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<input type=\"hidden\" name=\"sort\" value=\"name\">")
                .contains("<input type=\"hidden\" name=\"dir\" value=\"asc\">");
    }

    @Test
    @DisplayName("an unknown column is the default, not a property path echoed back into the header")
    void imageLibraryScreenForgivesAnUnknownSort() throws Exception {
        String html = mockMvc.perform(get("/mgmt/images?sort=filename&dir=sideways"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(imageService).search(any(), any(MgmtImageService.ArticleFilter.class), any(),
                eq(MgmtImageService.ImageSort.ID), eq(false), any());

        // Echo it raw and the screen sorts by a column that says it is not sorting, and the next
        // click produces a descending sort out of nowhere.
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<input type=\"hidden\" name=\"sort\" value=\"id\">")
                .doesNotContain("sort=filename")
                .doesNotContain("dir=sideways");
    }

    @Test
    @DisplayName("the detail screen carries the ordering back to the list, not just the page")
    void imageDetailCarriesTheOrderingBack() throws Exception {
        // The same page number of a differently ordered list is a different set of rows, so
        // «Към списъка» has to restore the ordering along with the filters and the page.
        mockMvc.perform(get("/mgmt/images/9?q=gard&usage=USED&size=48&sort=articles&dir=desc&page=2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sort=articles&amp;dir=desc&amp;page=2")));
    }

    @Test
    @DisplayName("the image library screen lists files, paths and how often each is used")
    void rendersImageLibraryScreen() throws Exception {
        mockMvc.perform(get("/mgmt/images"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("shared.jpg")))
                .andExpect(content().string(containsString("/assets/img/articles/shared.jpg")))
                // The usage count is what makes the delete refusal predictable rather than a
                // surprise, so it is on the row.
                .andExpect(content().string(containsString("в 2 статии")))
                // ...and the count is a link to the screen that can afford to name the articles.
                // The fragment is what this pins: it is written as `@{...} + '#usage'`, because
                // appending one outside the link expression is a parse error and putting it inside
                // makes it part of the path. Deliberately not the whole query string -- the
                // parameters and their order are incidental to that.
                .andExpect(content().string(containsString("/mgmt/images/9?")))
                .andExpect(content().string(containsString("#usage")))
                .andExpect(content().string(containsString("/mgmt/images/9/delete")))
                // The sidebar entry that got the operator here.
                .andExpect(content().string(containsString("Изображения")));
    }

    @Test
    @DisplayName("the library screen carries both new filters, and every link keeps them")
    void imageLibraryFiltersRideAlong() throws Exception {
        String html = mockMvc.perform(get("/mgmt/images?article=42&usage=UNUSED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("name=\"article\"")
                .contains("id=\"usage\"")
                // The select must show the filter that was applied, not the default.
                .contains("value=\"UNUSED\" selected=\"selected\"")
                // A row link that dropped the filters would silently return an unfiltered list on
                // the way back from the detail screen.
                .contains("article=42")
                .contains("usage=UNUSED");
    }

    @Test
    @DisplayName("an unrecognised usage parameter renders as 'all' instead of failing the screen")
    void imageLibraryForgivesAnUnknownUsage() throws Exception {
        mockMvc.perform(get("/mgmt/images?usage=nonsense"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"ANY\" selected=\"selected\"")));
    }

    @Test
    @DisplayName("a title search that hit the article cap says how many it left out")
    void imageLibraryReportsACappedArticleFilter() throws Exception {
        when(imageService.resolveArticleFilter(any()))
                .thenReturn(new MgmtImageService.ArticleFilter("градина", List.of(9), 200, true));

        mockMvc.perform(get("/mgmt/images?article=градина"))
                .andExpect(status().isOk())
                // Naming both numbers is the point: "the first 25 of 200" is a different screen
                // from "25 matched", and only one of them is missing rows.
                .andExpect(content().string(containsString("първите 25 от 200 статии")));
    }

    @Test
    @DisplayName("an empty result explains which filter emptied it")
    void imageLibraryEmptyStatesAreSpecific() throws Exception {
        when(imageService.search(any(), any(MgmtImageService.ArticleFilter.class), any(),
                any(MgmtImageService.ImageSort.class), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 24), 0));

        // No such article: retype.
        when(imageService.resolveArticleFilter(any()))
                .thenReturn(new MgmtImageService.ArticleFilter("999", List.of(), 0, false));
        mockMvc.perform(get("/mgmt/images?article=999"))
                .andExpect(content().string(containsString("Няма статия")));

        // The article exists and simply has none.
        when(imageService.resolveArticleFilter(any()))
                .thenReturn(new MgmtImageService.ArticleFilter("42", List.of(), 1, false));
        mockMvc.perform(get("/mgmt/images?article=42"))
                .andExpect(content().string(containsString("няма прикачени изображения")));

        // The question "what can I delete" answered with "nothing".
        when(imageService.resolveArticleFilter(any())).thenReturn(MgmtImageService.ArticleFilter.none());
        mockMvc.perform(get("/mgmt/images?usage=UNUSED"))
                .andExpect(content().string(containsString("се използва поне от една статия")));
    }

    @Test
    @DisplayName("the page-size control survives a result that fits on one page")
    void imageLibraryKeepsItsSizeControlWithoutAPager() throws Exception {
        // One row, one page -- exactly the state the articles screen never reaches and the image
        // library reaches on any search. Gate the size form on the pager and the operator who
        // chose 96 has no way back to 24 except by editing the URL.
        String html = mockMvc.perform(get("/mgmt/images?size=96"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"page-size\"")
                .contains("data-autosubmit")
                // The pager itself is absent: there is one page.
                .doesNotContain("Следваща страница")
                // And the search form carries the size, or searching would silently reset it.
                .contains("name=\"size\" value=\"96\"");
    }

    @Test
    @DisplayName("the image detail screen shows the file's facts and names the articles using it")
    void rendersImageDetail() throws Exception {
        String html = mockMvc.perform(get("/mgmt/images/9"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("shared.jpg")
                .contains("/assets/img/articles/shared.jpg")
                // The usage list is the point of the screen: a count the operator cannot act on
                // becomes a link to the article they have to detach the picture from.
                .contains("В стария град през август")
                .contains("/mgmt/articles/42")
                // What is on disk, which no other screen reads.
                .contains("800 × 531")
                // The caption is editable; nothing else on the document is.
                .contains("name=\"caption\"")
                .doesNotContain("name=\"filename\"")
                // A refused delete has to come back here, not to the list.
                .contains("name=\"from\" value=\"detail\"");
    }

    @Test
    @DisplayName("a record whose file is gone says so instead of showing a broken image")
    void rendersImageDetailForAMissingFile() throws Exception {
        when(imageService.fileOf(any()))
                .thenReturn(new MgmtImageService.ImageFile(false, 0L, null, null, null));

        mockMvc.perform(get("/mgmt/images/9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Файлът липсва на диска")));
    }

    @Test
    @DisplayName("an image nothing uses says it can be deleted")
    void rendersImageDetailWithoutUsages() throws Exception {
        when(imageService.usedBy(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/mgmt/images/9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("може да бъде изтрито")));
    }

    @Test
    @DisplayName("the caption is saved and the operator lands back on the image")
    void savesTheCaption() throws Exception {
        mockMvc.perform(post("/mgmt/images/9").param("caption", "Стар град").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/images/9"));

        // The caption and the alt text are one form: no alt field posted is no alt changed.
        verify(imageService).saveDescription(9, "Стар град", Map.of());
    }

    @Test
    @DisplayName("a refused delete from the detail screen comes back to it, with the reason")
    void refusedDeleteReturnsToTheDetailScreen() throws Exception {
        org.mockito.Mockito.doThrow(new MgmtOperationException("Изображението се използва от 2 статия(и)"))
                .when(imageService).delete(9);

        mockMvc.perform(post("/mgmt/images/9/delete").param("from", "detail").with(csrf()))
                .andExpect(redirectedUrl("/mgmt/images/9"))
                .andExpect(flash().attributeExists("error"));

        // From the list it stays on the list -- there is nothing on the detail screen it needs.
        mockMvc.perform(post("/mgmt/images/9/delete").with(csrf()))
                .andExpect(redirectedUrl("/mgmt/images"));
    }

    @Test
    @DisplayName("a successful delete always lands on the list -- the image's screen is gone")
    void deleteReturnsToTheList() throws Exception {
        mockMvc.perform(post("/mgmt/images/9/delete").param("from", "detail").with(csrf()))
                .andExpect(redirectedUrl("/mgmt/images"))
                .andExpect(flash().attributeExists("message"));
    }

    @Test
    @DisplayName("the library search answers JSON, not a page")
    void imageLibrarySearchIsJson() throws Exception {
        mockMvc.perform(get("/mgmt/images/search?q=shared"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("/assets/img/articles/shared.jpg")))
                .andExpect(content().string(containsString("\"more\"")));
    }

    @Test
    @DisplayName("the library search echoes the ordering it applied, never the one it was asked for")
    void imageLibrarySearchNormalisesItsSort() throws Exception {
        mockMvc.perform(get("/mgmt/images/search?sort=articles&dir=desc"))
                .andExpect(content().string(containsString("\"sort\":\"articles\"")))
                .andExpect(content().string(containsString("\"dir\":\"desc\"")));

        verify(imageService).search(any(), eq(MgmtImageService.ImageSort.ARTICLES), eq(true), any());

        // `sort` arrives off a query string and ends up in Sort.by, so it is an allow-list. mgmt.js
        // paints the active button from what comes back: echoing this raw would mark a column
        // active that the list is not ordered by, and the next click would reverse a direction
        // nobody chose. The /mgmt/authors rule, in JSON.
        mockMvc.perform(get("/mgmt/images/search?sort=filename&dir=sideways"))
                .andExpect(content().string(containsString("\"sort\":\"id\"")))
                .andExpect(content().string(containsString("\"dir\":\"asc\"")));

        verify(imageService).search(any(), eq(MgmtImageService.ImageSort.ID), eq(false), any());
    }

    @Test
    @DisplayName("arranging and attaching are JSON endpoints that do not redirect")
    void arrangeAndAttachAnswerWithoutARedirect() throws Exception {
        ImageMedia attached = new ImageMedia(9);
        attached.setFilename("shared.jpg");
        when(articleService.attachImage(42, 9)).thenReturn(attached);
        when(imageService.row(attached)).thenReturn(imageRow());

        // A redirect is what the rest of this console does and exactly what these must not do:
        // the operator is mid-article and a reload would take the unsaved body with it.
        mockMvc.perform(post("/mgmt/articles/42/images/arrange")
                        .param("ids", "9", "3").param("hidden", "3").with(csrf()))
                .andExpect(status().isNoContent());

        verify(articleService).arrangeImages(42, List.of(9, 3), List.of(3));

        mockMvc.perform(post("/mgmt/articles/42/images/9/attach").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("/assets/img/articles/shared.jpg")));
    }

    @Test
    @DisplayName("a refused attach comes back as JSON carrying the reason")
    void refusedAttachIsJson() throws Exception {
        // Not an HTML error page: mgmt.js shows this string to the operator, and a page here would
        // leave the picker silently unresponsive.
        when(articleService.attachImage(42, 9))
                .thenThrow(new MgmtOperationException("Изображението вече е прикачено към тази статия."));

        mockMvc.perform(post("/mgmt/articles/42/images/9/attach").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("вече е прикачено")));
    }

    @Test
    @DisplayName("the restore action appears only when there is a previous body to go back to")
    void restoreActionIsConditional() throws Exception {
        Article rewritten = article();
        rewritten.setText("<p>rewritten by the editor</p>");
        rewritten.setPreviousText("<p>the decade-old original</p>");
        when(articleService.find(anyInt())).thenReturn(Optional.of(rewritten));

        mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/mgmt/articles/42/restore-text")));

        // The default fixture has no previousText, so the card must not be rendered at all.
        when(articleService.find(anyInt())).thenReturn(Optional.of(article()));

        mockMvc.perform(get("/mgmt/articles/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(containsString("restore-text"))));
    }

    @Test
    @DisplayName("the author screens render")
    void rendersAuthorScreens() throws Exception {
        mockMvc.perform(get("/mgmt/authors"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Мария Влахова")))
                // The search box. id="search-input" is what mgmt.js debounces and auto-submits.
                .andExpect(content().string(containsString("name=\"q\"")))
                .andExpect(content().string(containsString("id=\"search-input\"")));

        mockMvc.perform(get("/mgmt/authors/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"firstname\"")));

        mockMvc.perform(get("/mgmt/authors/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Нов автор")));
    }

    @Test
    @DisplayName("the author list's column headers are server-side sort links")
    void authorListHeadersSort() throws Exception {
        // Rendered with no parameters, the screen must already declare an active column: the
        // list *is* sorted, and a header showing nothing would invite a first click that
        // silently produced a descending sort.
        String html = mockMvc.perform(get("/mgmt/authors"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Real links, not a script that reorders the DOM. One per column, each naming the key the
        // service allow-lists.
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=id");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=name");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=description");

        // The default column is marked, and clicking it asks for the other direction.
        org.assertj.core.api.Assertions.assertThat(html).contains("aria-sort=\"ascending\"");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=name&amp;dir=desc");
        // An inactive column starts ascending rather than inheriting the active one's direction.
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=id&amp;dir=asc");

        // A GET form replaces the whole query string, so the ordering has to ride along in the
        // search form or the first keystroke would throw it away -- invisible from a status code.
        org.assertj.core.api.Assertions.assertThat(html).contains("name=\"sort\"");
        org.assertj.core.api.Assertions.assertThat(html).contains("name=\"dir\"");

        // And the mirror image: a header link that dropped q would clear the operator's filter
        // and answer 200 with the whole list.
        when(authorService.findAll(eq("влахова"), any(), any())).thenReturn(List.of(author()));

        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/authors").param("q", "влахова"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("q=%D0%B2%D0%BB%D0%B0%D1%85%D0%BE%D0%B2%D0%B0&amp;sort=id");
    }

    @Test
    @DisplayName("the author sort parameters reach the service normalised, never raw")
    void authorSortIsNormalised() throws Exception {
        mockMvc.perform(get("/mgmt/authors").param("sort", "DESCRIPTION").param("dir", "DESC"))
                .andExpect(status().isOk())
                // Marked as the active column, which is what makes the next click flip it.
                .andExpect(content().string(containsString("aria-sort=\"descending\"")));

        // A column no header shows must not reach the service, and must not 500 at an operator
        // who edited the URL: it degrades to the default silently.
        mockMvc.perform(get("/mgmt/authors").param("sort", "password").param("dir", "sideways"))
                .andExpect(status().isOk());

        verify(authorService).findAll(null, "description", "desc");
        verify(authorService).findAll(null, "name", "asc");
    }

    @Test
    @DisplayName("the category screens render, including the usage count")
    void rendersCategoryScreens() throws Exception {
        mockMvc.perform(get("/mgmt/categories"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Двор")))
                // The count column comes from one aggregation, not a query per row.
                .andExpect(content().string(containsString(">3<")))
                .andExpect(content().string(containsString("name=\"q\"")))
                .andExpect(content().string(containsString("id=\"search-input\"")));

        mockMvc.perform(get("/mgmt/categories/17"))
                .andExpect(status().isOk())
                // The delete card shows how many articles would be left with a broken reference.
                .andExpect(content().string(containsString("3")));

        mockMvc.perform(get("/mgmt/categories/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Нова категория")));
    }

    @Test
    @DisplayName("the author and category search term reaches the service and comes back in the box")
    void listSearchIsWiredThrough() throws Exception {
        // Nothing matches, so the empty state is what renders -- and it must not read as
        // "there are no authors", which is what an unconditional message would say.
        when(authorService.findAll("влахова", "name", "asc")).thenReturn(List.of());
        when(categoryService.findAll(eq("17"), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/mgmt/authors").param("q", "влахова"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Няма автори, отговарящи на търсенето.")))
                // Echoed back, or the box would clear itself on every submit.
                .andExpect(content().string(containsString("влахова")));

        mockMvc.perform(get("/mgmt/categories").param("q", "17"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Няма категории, отговарящи на търсенето.")));

        verify(authorService).findAll("влахова", "name", "asc");
        verify(categoryService).findAll("17", "id", "asc", Map.of(17, 3L));
    }

    @Test
    @DisplayName("the category list's column headers are server-side sort links, count included")
    void categoryListHeadersSort() throws Exception {
        String html = mockMvc.perform(get("/mgmt/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Four real links, not a script that reorders the DOM. «Статии» is the interesting one:
        // its values come from the aggregation, not from the document.
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=id");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=name");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=lang");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=articles");

        // The default column is the NUMBER here, not the name as on the author list, and it is
        // marked -- an unmarked header would invite a first click that silently sorted descending.
        org.assertj.core.api.Assertions.assertThat(html).contains("aria-sort=\"ascending\"");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=id&amp;dir=desc");
        org.assertj.core.api.Assertions.assertThat(html).contains("sort=articles&amp;dir=asc");

        // A GET form replaces the whole query string, so the ordering rides along in the search
        // form or the first keystroke throws it away -- invisible from a status code.
        org.assertj.core.api.Assertions.assertThat(html).contains("name=\"sort\"");
        org.assertj.core.api.Assertions.assertThat(html).contains("name=\"dir\"");

        // And the mirror image: a header link that dropped q would clear the filter and answer
        // 200 with the whole collection.
        when(categoryService.findAll(eq("двор"), any(), any(), any())).thenReturn(List.of(category()));

        org.assertj.core.api.Assertions.assertThat(
                mockMvc.perform(get("/mgmt/categories").param("q", "двор"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .contains("q=%D0%B4%D0%B2%D0%BE%D1%80&amp;sort=id");
    }

    @Test
    @DisplayName("the category sort parameters are normalised, and the counts are read once")
    void categorySortIsNormalisedAndTheAggregationRunsOnce() throws Exception {
        mockMvc.perform(get("/mgmt/categories").param("sort", "ARTICLES").param("dir", "DESC"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-sort=\"descending\"")));

        // A column no header shows must not reach the service and must not 500 at an operator
        // who edited the URL.
        mockMvc.perform(get("/mgmt/categories").param("sort", "password").param("dir", "sideways"))
                .andExpect(status().isOk());

        verify(categoryService).findAll(null, "articles", "desc", Map.of(17, 3L));
        // The map the controller read is the map findAll got -- that is the whole reason the
        // aggregation is hoisted above the list call rather than run twice.
        verify(categoryService).findAll(null, "id", "asc", Map.of(17, 3L));

        // The «Статии» values are sortable AND rendered, and both come from the same map: the
        // controller reads it once and hands it to findAll rather than aggregating twice.
        verify(categoryService, times(2)).countArticlesByCategory();
    }

    @Test
    @DisplayName("the banner screens render, including the counters and the upload card")
    void rendersBannerScreens() throws Exception {
        mockMvc.perform(get("/mgmt/banners"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Партньор")))
                .andExpect(content().string(containsString("4711")))
                .andExpect(content().string(containsString("name=\"q\"")))
                .andExpect(content().string(containsString("id=\"search-input\"")));

        String html = mockMvc.perform(get("/mgmt/banners/5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // th:field on an unbound form object fails at render time, so reaching these ids is most
        // of the assertion.
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"name\"")
                .contains("id=\"type\"")
                .contains("id=\"placement\"")
                .contains("id=\"size\"")
                .contains("id=\"sortOrder\"")
                .contains("id=\"targetUrl\"")
                .contains("id=\"caption\"")
                .contains("id=\"activeFrom\"")
                .contains("id=\"active\"")
                // Every page is offered as a checkbox; none ticked means everywhere.
                .contains("id=\"page-LIST\"")
                // The upload posts to its own endpoint, not to the main form.
                .contains("/mgmt/banners/5/creative")
                // The size limit comes from BannerCreativeService through MgmtControllerAdvice,
                // not from a hardcoded string in the template.
                .contains(String.valueOf(BannerCreativeService.MAX_BANNER_BYTES / 1024))
                .contains("/mgmt/banners/5/delete");

        mockMvc.perform(get("/mgmt/banners/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Нова реклама")));
    }

    /**
     * A click inside an iframe never reaches the framing page as an event -- but a same-origin,
     * unsandboxed frame lets the page's site.js listen inside it, so an HTML5 booking has a click
     * number. The card shows it and divides nothing by it: the all-time counts carry no dates and
     * need not have started together.
     */
    @Test
    @DisplayName("a same-origin HTML5 booking shows its clicks, and no all-time rate")
    void html5BookingsReportClicks() throws Exception {
        Banner html5 = new Banner(6);
        html5.setName("Acme");
        html5.setType(BannerType.HTML5);
        html5.setSource("/resources/banners/acme2/index.html");
        html5.setPlacement(BannerPlacement.SIDEBAR);
        html5.setSize(BannerSize.SQUARE);
        html5.setSortOrder(10);
        html5.setImpressions(99);
        html5.setClicks(4);

        when(bannerService.find(anyInt())).thenReturn(Optional.of(html5));

        String html = mockMvc.perform(get("/mgmt/banners/6"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("99")
                .contains("iframe")
                // Same-origin, so site.js counts its clicks -- shown, but never divided by an
                // all-time impression count that predates them.
                .contains("<strong>4</strong>")
                .contains("не се изчислява CTR")
                .doesNotContain("не могат да бъдат преброени")
                // No upload card: there is no single file to replace.
                .doesNotContain("/mgmt/banners/6/creative");
    }

    @Test
    @DisplayName("an off-origin HTML5 booking reports no clicks, as a dash and a reason")
    void offOriginHtml5BookingsReportNoClicks() throws Exception {
        Banner html5 = new Banner(6);
        html5.setName("Чужд сървър");
        html5.setType(BannerType.HTML5);
        html5.setSource("https://ads.example.com/b/index.html");
        html5.setPlacement(BannerPlacement.SIDEBAR);
        html5.setSize(BannerSize.SQUARE);
        html5.setSortOrder(10);
        html5.setImpressions(99);

        when(bannerService.find(anyInt())).thenReturn(Optional.of(html5));

        String html = mockMvc.perform(get("/mgmt/banners/6"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<strong>\u2014</strong>")
                .contains("не могат да бъдат преброени")
                .doesNotContain("не се изчислява CTR");
    }

    @Test
    @DisplayName("the banner search term reaches the service and comes back in the box")
    void bannerSearchIsWiredThrough() throws Exception {
        when(bannerService.findAll("партньор")).thenReturn(List.of());

        mockMvc.perform(get("/mgmt/banners").param("q", "партньор"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Няма реклами, отговарящи на търсенето.")))
                // Echoed back, or the box would clear itself on every submit.
                .andExpect(content().string(containsString("партньор")));

        verify(bannerService).findAll("партньор");
    }

    @Test
    @DisplayName("a banner creative upload reaches the service under the parameter name the form posts")
    void bannerUploadIsWiredToTheService() throws Exception {
        MockMultipartFile upload =
                new MockMultipartFile("file", "partner.gif", "image/gif", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/mgmt/banners/5/creative").file(upload).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/5"));

        verify(bannerService).uploadCreative(eq(5), any());
    }

    /**
     * An oversize upload must land back on the record's own screen with a message, not on Tomcat's
     * bare "HTTP Status 413 - Payload Too Large" page -- which is what it does without
     * {@code UploadSizeAdvice}, observed against the running stack.
     *
     * <p>What this pins is the redirect target and the message. It does <em>not</em> reproduce the
     * production trigger: MockMvc builds the multipart request itself and never runs the parser,
     * whereas in production the parse fails in {@code DispatcherServlet} before any handler is
     * resolved. That difference is the whole reason the handler is an unscoped advice rather than
     * part of {@code MgmtControllerAdvice}, and only a real request over HTTP exercises it.
     *
     * <p>Asserted for both upload endpoints, because the handler derives where to send the
     * operator back to by stripping the last URL segment, and that rule has to hold for both.
     */
    @Test
    @DisplayName("an upload the container refuses comes back as a message, not an error page")
    void oversizeUploadIsReportedToTheOperator() throws Exception {
        MockMultipartFile upload =
                new MockMultipartFile("file", "huge.gif", "image/gif", new byte[]{1, 2, 3});

        when(bannerService.uploadCreative(anyInt(), any()))
                .thenThrow(new MaxUploadSizeExceededException(BannerCreativeService.MAX_BANNER_BYTES));

        mockMvc.perform(multipart("/mgmt/banners/5/creative").file(upload).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/5"))
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString(
                                String.valueOf(BannerCreativeService.MAX_BANNER_BYTES / 1024))));

        when(articleService.addImage(anyInt(), any()))
                // The argument is only what the exception reports; the flash message names the
                // limit the service is configured with, not this number.
                .thenThrow(new MaxUploadSizeExceededException(128L * 1024L));

        mockMvc.perform(multipart("/mgmt/articles/42/images").file(upload).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/articles/42"));
    }

    @Test
    @DisplayName("a refusal renders the studio's own error page, worded for the status")
    void errorPageNamesTheStatus() throws Exception {
        for (int status : new int[]{403, 404, 503, 500}) {
            String html = mockMvc.perform(get("/error")
                            .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, status)
                            .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/mgmt/x")
                            .accept(org.springframework.http.MediaType.TEXT_HTML))
                    .andExpect(status().is(status))
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(html)
                    .contains("/mgmt/sites")
                    .doesNotContain("Whitelabel", "??");
        }
    }

    @Test
    @DisplayName("/mgmt/ lands on the dashboard")
    void rootRedirectsToDashboard() throws Exception {
        mockMvc.perform(get("/mgmt/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/dashboard"));
    }

    @Test
    @DisplayName("the dashboard shows readers, operators, the 24 h tiles and all three charts")
    void dashboardRenders() throws Exception {
        presence.touch("maria");

        mockMvc.perform(get("/mgmt/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"dash-readers\">7<")))
                // The operator rendering the page is a request too, so is always among them.
                .andExpect(content().string(containsString("admin")))
                .andExpect(content().string(containsString("maria")))
                // Its own ids, never live-count: both polls on one page would fetch twice.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"live-count\""))))
                .andExpect(content().string(containsString("Използване на сайта")))
                .andExpect(content().string(containsString("Реклами")))
                .andExpect(content().string(containsString("Обхождане по категория")))
                .andExpect(content().string(containsString("изпълнява се: 1 · неуспешни: 2")))
                .andExpect(content().string(containsString("Нови изображения, 24 ч")))
                // The fixture's social page is published, so its card is there.
                .andExpect(content().string(containsString("Социална статистика")))
                // Four fixture buckets cannot hold a previous 24 h, so no percentage is invented.
                .andExpect(content().string(containsString("няма предишни данни за сравнение")))
                .andExpect(content().string(containsString("<span class=\"nav-text\">Табло</span>")));

        verify(monitoringService).series(MonitoringRange.H24);
        verify(monitoringService).series(MonitoringRange.D7);
        verify(crawlerService).series(MonitoringRange.H24);
    }

    @Test
    @DisplayName("the change against the previous 24 h renders signed, up and down")
    void dashboardRendersTheChange() throws Exception {
        // A real week: 120 empty hours, yesterday at 4 views and 3 readers an hour, today at 5 and 2.
        // Views rise 25 %, the readers' peak falls a third -- both branches of the template.
        List<MgmtMonitoringService.Bucket> week = new java.util.ArrayList<>();
        for (int i = 167; i >= 0; i--) {
            week.add(i >= 48 ? bucket(i, 0, 0) : i >= 24 ? bucket(i, 4, 3) : bucket(i, 5, 2));
        }
        List<MgmtMonitoringService.Bucket> today = week.subList(144, 168);
        when(monitoringService.series(MonitoringRange.D7)).thenReturn(week);
        when(monitoringService.series(MonitoringRange.H24)).thenReturn(today);
        doCallRealMethod().when(monitoringService).totals(any());

        mockMvc.perform(get("/mgmt/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"stat-note up\"")))
                .andExpect(content().string(containsString("class=\"stat-note down\"")))
                .andExpect(content().string(containsString("+25,0 % (24ч)")))
                .andExpect(content().string(containsString("-33,3 % (24ч)")));
    }

    @Test
    @DisplayName("one failing section empties itself; the dashboard still renders")
    void dashboardSurvivesAFailingSection() throws Exception {
        when(crawlerService.series(any())).thenThrow(new IllegalStateException("boom"));
        when(jobQueue.counts(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(monitoringService.liveVisitors()).thenReturn(MgmtMonitoringService.LiveCount.unavailable());

        mockMvc.perform(get("/mgmt/dashboard"))
                .andExpect(status().isOk())
                // «—», never 0, for readers the database could not count.
                .andExpect(content().string(containsString("id=\"dash-readers\">\u2014<")))
                .andExpect(content().string(containsString("няма връзка с базата")))
                .andExpect(content().string(containsString("Използване на сайта")));
    }

    @Test
    @DisplayName("the dashboard poll answers JSON with readers and operators")
    void dashboardLiveIsJson() throws Exception {
        presence.touch("maria");

        mockMvc.perform(get("/mgmt/dashboard/live"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("\"active\":7")))
                .andExpect(content().string(containsString("\"maria\"")));
    }

    @Test
    @DisplayName("the CSRF token, not the role, is what separates an accepted POST from a refused one")
    void csrfIsTheDiscriminatorOnAWrite() throws Exception {
        when(articleService.create()).thenReturn(article());

        // Same authenticated user, same URL; only the token differs. Without the accepted case
        // the 403 below would also be produced by CSRF being off and the role being wrong, so
        // this pair is what would catch CSRF being disabled again.
        mockMvc.perform(post("/mgmt/articles").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/articles/42"));

        mockMvc.perform(post("/mgmt/articles"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a multipart upload reaches the service under the parameter name the form posts")
    void uploadIsWiredToTheService() throws Exception {
        // The endpoint reads "file", and nothing but a real multipart dispatch proves the form
        // and the endpoint agree -- nor that CSRF works on a multipart POST, which is the
        // combination most likely to break silently.
        MockMultipartFile upload =
                new MockMultipartFile("file", "garden.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/mgmt/articles/42/images").file(upload).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/articles/42"));

        verify(articleService).addImage(eq(42), any());
    }

    @Test
    @DisplayName("the upload has a JSON twin that answers with the new row instead of redirecting")
    void uploadAnswersJsonForTheBrowser() throws Exception {
        // The whole reason it exists: a «Качи» that submitted the form would get a redirect back
        // to the editor and throw away every unsaved word in the body. This is the endpoint
        // mgmt.js posts to instead, and it must not redirect.
        ImageMedia stored = new ImageMedia(9);
        stored.setFilename("shared.jpg");
        when(articleService.addImage(eq(42), any())).thenReturn(stored);
        when(imageService.row(stored)).thenReturn(imageRow());

        MockMultipartFile upload =
                new MockMultipartFile("file", "garden.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/mgmt/articles/42/images/upload").file(upload).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                // The same row shape the attach endpoint answers with, because mgmt.js builds the
                // card from it with one function.
                .andExpect(content().string(containsString("/assets/img/articles/shared.jpg")));

        // One service method behind both paths, which is what keeps the cap, the 600x400 rule and
        // the article document's save from drifting between them.
        verify(articleService).addImage(eq(42), any());
    }

    @Test
    @DisplayName("a refused upload comes back as JSON carrying the reason, not an error page")
    void refusedUploadIsJson() throws Exception {
        when(articleService.addImage(anyInt(), any()))
                .thenThrow(new MediaException("garden.png is 1600x900; the site layout requires the proportions of 600x400."));

        MockMultipartFile upload =
                new MockMultipartFile("file", "garden.png", "image/png", new byte[]{1, 2, 3});

        // An HTML page here would leave the operator with a generic "the request failed": readJson
        // refuses a non-JSON body before it can read a reason out of it.
        mockMvc.perform(multipart("/mgmt/articles/42/images/upload").file(upload).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("1600x900")));
    }

    @Test
    @DisplayName("the no-script upload posts every selected file and reports each outcome")
    void formUploadTakesEveryFileAndReportsPartialRefusals() throws Exception {
        // The file input is `multiple`, so the form can carry several parts. Bound as a single
        // MultipartFile Spring would hand over the first and drop the rest with nothing logged and
        // a 302 that reads as success.
        when(articleService.addImage(anyInt(), any()))
                .thenReturn(new ImageMedia(9))
                .thenThrow(new MediaException("wide.png is 1600x900; the site layout requires the proportions of 600x400."));

        mockMvc.perform(multipart("/mgmt/articles/42/images")
                        .file(new MockMultipartFile("file", "garden.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("file", "wide.png", "image/png", new byte[]{2}))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/articles/42"))
                // Both halves of the outcome, named: one stored, one refused with its reason. A
                // flash carrying only the last would be a lie about either file.
                .andExpect(flash().attributeExists("message"))
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("wide.png")));

        verify(articleService, org.mockito.Mockito.times(2)).addImage(eq(42), any());
    }

    @Test
    @DisplayName("a refused upload comes back as a message rather than an error page")
    void refusedUploadIsReportedToTheOperator() throws Exception {
        when(articleService.addImage(anyInt(), any()))
                .thenThrow(new MediaException("garden.png is 1600x900; the site layout requires the proportions of 600x400."));

        MockMultipartFile upload =
                new MockMultipartFile("file", "garden.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/mgmt/articles/42/images").file(upload).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("1600x900")));
    }

    // ---- the jobs screens ------------------------------------------------------------------

    @Test
    @DisplayName("the jobs list renders the queue, the filter and, with PDFs off, says why")
    void rendersJobsScreen() throws Exception {
        String html = mockMvc.perform(get("/mgmt/jobs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("/mgmt/jobs/" + JOB_ID, "PDF файлове на статии", "12,14", "изпълнява се", "1 от 4",
                        "/mgmt/jobs?status=failed", "PACOD_PDF_GOTENBERG_URL")
                .doesNotContain("action=\"/mgmt/jobs/pdf\"");
    }

    @Test
    @DisplayName("with PDF export on, the list offers the form")
    void jobsScreenOffersThePdfForm() throws Exception {
        when(gotenberg.enabled()).thenReturn(true);
        when(printTokens.enabled()).thenReturn(true);
        mockMvc.perform(get("/mgmt/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/mgmt/jobs/pdf\"")))
                .andExpect(content().string(containsString("name=\"ids\"")));
    }

    @Test
    @DisplayName("a running job shows its log as text, a cancel and a refresh; a queued one a promotion")
    void rendersJobScreen() throws Exception {
        String html = mockMvc.perform(get("/mgmt/jobs/" + JOB_ID)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"job-log\"", "data-job=\"" + JOB_ID + "\"", "data-terminal=\"false\"",
                        "&lt;b&gt;48 KB&lt;/b&gt;", "/mgmt/jobs/" + JOB_ID + "/cancel", "admin@example.com")
                .doesNotContain("<b>48 KB</b>", "/promote");

        when(jobQueue.find(any(), any())).thenReturn(Optional.of(queuedJob()));
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/jobs/" + JOB_ID)).andReturn().getResponse().getContentAsString())
                .contains("/mgmt/jobs/" + JOB_ID + "/promote");

        when(jobQueue.find(any(), any())).thenReturn(Optional.of(finishedJob()));
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/jobs/" + JOB_ID)).andReturn().getResponse().getContentAsString())
                .contains("1 of 4 failed", "/mgmt/jobs/" + JOB_ID + "/delete")
                .doesNotContain("/cancel", "data-terminal=\"false\"");
    }

    @Test
    @DisplayName("an id that is not a job's shape, or another website's job, is a 404")
    void unknownJob() throws Exception {
        mockMvc.perform(get("/mgmt/jobs/nope")).andExpect(status().isNotFound());
        when(jobQueue.find(any(), any())).thenReturn(Optional.empty());
        mockMvc.perform(get("/mgmt/jobs/" + JOB_ID)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the poll answers JSON: the log after the offset, or the whole window when it fell behind")
    void jobStatusIsJson() throws Exception {
        mockMvc.perform(get("/mgmt/jobs/" + JOB_ID + "/status").param("since", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"terminal\":false")))
                .andExpect(content().string(containsString("\"total\":4")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("\"replace\""))));
        Job behind = runningJob();
        behind.setLogChars(behind.getLog().length() + 5000);
        when(jobQueue.find(any(), any())).thenReturn(Optional.of(behind));
        mockMvc.perform(get("/mgmt/jobs/" + JOB_ID + "/status").param("since", "10"))
                .andExpect(content().string(containsString("\"replace\":true")));
    }

    @Test
    @DisplayName("queuing PDFs: refused while export is off, queued for this website when on, bad input refused")
    void queuePdf() throws Exception {
        mockMvc.perform(post("/mgmt/jobs/pdf").with(csrf()).param("ids", "12"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/jobs"));
        verify(jobQueue, never()).enqueue(any(), any(), any(), any(), any(), anyBoolean());

        when(gotenberg.enabled()).thenReturn(true);
        when(printTokens.enabled()).thenReturn(true);
        mockMvc.perform(post("/mgmt/jobs/pdf").with(csrf()).param("ids", "12, 14").param("languages", "BG,de").param("next", "true"))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID));
        verify(jobQueue).enqueue(eq("test"), any(), eq(JobType.PDF_ARTICLES),
                eq(Map.of("ids", "12,14", "languages", "bg,de", "format", "standard", "mode", "code")), any(), eq(true));

        mockMvc.perform(post("/mgmt/jobs/pdf").with(csrf()).param("ids", "12; drop").param("back", "//evil.example"))
                .andExpect(redirectedUrl("/mgmt/jobs"));
        mockMvc.perform(post("/mgmt/jobs/pdf").with(csrf()).param("from", "1").param("to", "900")
                        .param("back", "/mgmt/articles/12"))
                .andExpect(redirectedUrl("/mgmt/articles/12"));
        verify(jobQueue, times(1)).enqueue(any(), any(), any(), any(), any(), anyBoolean());

        // The AI mode needs AI here: refused with the reason while it is off, queued once it is on.
        mockMvc.perform(post("/mgmt/jobs/pdf").with(csrf()).param("ids", "12").param("mode", "llm"))
                .andExpect(redirectedUrl("/mgmt/jobs"))
                .andExpect(flash().attributeExists("error"));
        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        mockMvc.perform(post("/mgmt/jobs/pdf").with(csrf()).param("ids", "12").param("format", "scientific")
                        .param("mode", "llm").param("refresh", "true"))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID));
        verify(jobQueue).enqueue(eq("test"), any(), eq(JobType.PDF_ARTICLES),
                eq(Map.of("ids", "12", "format", "scientific", "mode", "llm", "refresh", "true")), any(), eq(false));
    }

    @Test
    @DisplayName("the PDF settings screen offers every default, a journal name per language, and saves")
    void pdfSettingsScreen() throws Exception {
        String html = mockMvc.perform(get("/mgmt/pdf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("action=\"/mgmt/pdf\"", "name=\"defaultFormat\"", "name=\"defaultMode\"",
                        "name=\"paperSize\"", "name=\"marginMm\"", "name=\"journalName_bg\"", "name=\"issn\"",
                        "name=\"maxKeywords\"");

        when(pdfSettingsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        mockMvc.perform(post("/mgmt/pdf").with(csrf()).param("defaultFormat", "SCIENTIFIC").param("defaultMode", "CODE")
                        .param("paperSize", "A5").param("marginMm", "20").param("issn", "1234-567x")
                        .param("pageNumbers", "true").param("maxKeywords", "5").param("journalName_bg", "Вестник"))
                .andExpect(redirectedUrl("/mgmt/pdf"))
                .andExpect(flash().attributeExists("message"));
        verify(pdfSettingsRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.format() == io.pacod.cms.core.data.PdfFormat.SCIENTIFIC
                        && saved.paper() == io.pacod.cms.core.data.PaperSize.A5
                        && "1234-567X".equals(saved.getIssn()) && saved.isPageNumbers() && !saved.isRunningHeader()
                        && "Вестник".equals(saved.rawJournalName("bg"))));

        mockMvc.perform(post("/mgmt/pdf").with(csrf()).param("marginMm", "3"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @DisplayName("the issue screens: the list, a new one, and the editor with its articles, generation and publication")
    void issueScreens() throws Exception {
        io.pacod.cms.core.data.JournalIssue issue = new io.pacod.cms.core.data.JournalIssue();
        issue.setId(3);
        issue.setVolume(2);
        issue.setNumber(5);
        issue.setYear(2026);
        issue.setMonth(10);
        issue.setArticleIds(List.of(12, 13));
        issue.setTitles(List.of(new io.pacod.cms.core.data.TextItem("Есен", "bg")));
        issue.setRenditions(new java.util.LinkedHashMap<>(Map.of("bg", new io.pacod.cms.core.data.IssueRendition(
                LocalDateTime.now(), 24, io.pacod.cms.core.data.PdfFormat.STANDARD, io.pacod.cms.core.data.PdfMode.CODE, JOB_ID, 2048))));
        when(issueService.list(0)).thenReturn(new PageImpl<>(List.of(issue)));
        when(issueService.suggestion()).thenReturn(issue);
        when(issueService.find(3)).thenReturn(issue);
        when(issueService.rows(any(), any())).thenReturn(List.of(
                new MgmtIssueService.ArticleRow(12, "Заглавие", LocalDateTime.now(), io.pacod.cms.core.data.ArticleStatus.PUBLISHED, false),
                new MgmtIssueService.ArticleRow(14, "Чернова", null, io.pacod.cms.core.data.ArticleStatus.DRAFT, false),
                new MgmtIssueService.ArticleRow(13, null, null, null, true)));
        when(gotenberg.enabled()).thenReturn(true);
        when(printTokens.enabled()).thenReturn(true);

        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/issues")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("href=\"/mgmt/issues/3\"", "Есен", "2026-10");
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/issues/new")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("action=\"/mgmt/issues\"", "name=\"volume\"");

        String editor = mockMvc.perform(get("/mgmt/issues/3")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(editor)
                .contains("action=\"/mgmt/issues/3\"", "name=\"title_bg\"", "name=\"editorial_bg\"",
                        "value=\"12, 13\"", "action=\"/mgmt/issues/3/fill-month\"", "action=\"/mgmt/issues/3/articles/move\"",
                        "action=\"/mgmt/issues/3/generate\"", "action=\"/mgmt/issues/3/publish\"",
                        "action=\"/mgmt/issues/3/delete\"", "href=\"/mgmt/issues/3/pdf/bg\"");
        // A draft is flagged, a missing article named; the article list is its own form.
        org.assertj.core.api.Assertions.assertThat(editor.split("class=\"badge draft\"", -1).length - 1).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(editor).contains("Чернова");

        mockMvc.perform(post("/mgmt/issues/3/generate").with(csrf()).param("format", "scientific"))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID));
        verify(jobQueue).enqueue(eq("test"), any(), eq(JobType.PDF_ISSUE),
                eq(Map.of("issue", "3", "format", "scientific", "mode", "code")), any(), eq(false));

        mockMvc.perform(post("/mgmt/issues/3").with(csrf()).param("volume", "2").param("number", "5")
                        .param("year", "2026").param("month", "10").param("articleIds", "12; 13"))
                .andExpect(redirectedUrl("/mgmt/issues/3"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(get("/mgmt/issues/x1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the PDF cards offer format and mode, the website's defaults first, the AI mode off without AI")
    void pdfChoices() throws Exception {
        when(gotenberg.enabled()).thenReturn(true);
        when(printTokens.enabled()).thenReturn(true);
        String jobs = mockMvc.perform(get("/mgmt/jobs")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(jobs)
                .contains("name=\"format\"", "value=\"scientific\"", "name=\"mode\"", "value=\"llm\" disabled")
                .doesNotContain("name=\"refresh\"");
        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        jobs = mockMvc.perform(get("/mgmt/jobs")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(jobs).contains("name=\"refresh\"").doesNotContain("value=\"llm\" disabled");
    }

    @Test
    @DisplayName("without AI the jobs screen says why; with it, it offers the website's languages but the original")
    void translateCard() throws Exception {
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/jobs")).andReturn().getResponse().getContentAsString())
                .contains("PACOD_GW_URL").doesNotContain("action=\"/mgmt/jobs/translate\"");

        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        String html = mockMvc.perform(get("/mgmt/jobs")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("action=\"/mgmt/jobs/translate\"", "id=\"trl-en\"")
                .doesNotContain("id=\"trl-bg\"");
    }

    @Test
    @DisplayName("the article editor offers translation only where AI is available, in a form of its own")
    void editorTranslateCard() throws Exception {
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/articles/42")).andReturn().getResponse().getContentAsString())
                .doesNotContain("/mgmt/jobs/translate");
        when(aiAccess.availableHere()).thenReturn(true);
        String html = mockMvc.perform(get("/mgmt/articles/42")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("action=\"/mgmt/jobs/translate\"", "value=\"/mgmt/articles/42\"");
        // Its checkboxes sit in that form, never inside #article-form, whose save would bind them.
        int form = html.indexOf("id=\"article-form\"");
        int formEnd = html.indexOf("</form>", form);
        org.assertj.core.api.Assertions.assertThat(html.substring(form, formEnd)).doesNotContain("name=\"languages\"");
    }

    @Test
    @DisplayName("queuing a translation: refused without AI; with it, the ticked languages the website offers, never the original")
    void queueTranslation() throws Exception {
        mockMvc.perform(post("/mgmt/jobs/translate").with(csrf()).param("ids", "12").param("languages", "en"))
                .andExpect(redirectedUrl("/mgmt/jobs"))
                .andExpect(flash().attributeExists("error"));
        verify(jobQueue, never()).enqueue(any(), any(), any(), any(), any(), anyBoolean());

        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        mockMvc.perform(post("/mgmt/jobs/translate").with(csrf()).param("ids", "12")
                        .param("languages", "en", "bg", "xx").param("mode", "all").param("back", "/mgmt/articles/12"))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID));
        verify(jobQueue).enqueue(eq("test"), any(), eq(JobType.TRANSLATE_ARTICLES),
                eq(Map.of("ids", "12", "languages", "en", "mode", "all")), any(), eq(false));

        mockMvc.perform(post("/mgmt/jobs/translate").with(csrf()).param("ids", "12").param("languages", "bg")
                        .param("back", "/mgmt/articles/12"))
                .andExpect(redirectedUrl("/mgmt/articles/12"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @DisplayName("Preview and View on the site go through the studio's preview route, never the studio's own /article/ path")
    void previewLinks() throws Exception {
        String editor = mockMvc.perform(get("/mgmt/articles/42")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(editor).contains("href=\"/mgmt/articles/42/preview\"")
                .doesNotContain("href=\"/article/42\"");
        // No website address here: back to the editor, saying why.
        mockMvc.perform(get("/mgmt/articles/42/preview"))
                .andExpect(redirectedUrl("/mgmt/articles/42"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @DisplayName("the rest of the website: a card of its own, tags unticked; the parts and languages posted are allow-listed")
    void queueSiteTranslation() throws Exception {
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/jobs")).andReturn().getResponse().getContentAsString())
                .doesNotContain("action=\"/mgmt/jobs/translate-site\"");
        mockMvc.perform(post("/mgmt/jobs/translate-site").with(csrf()).param("parts", "pages").param("languages", "en"))
                .andExpect(redirectedUrl("/mgmt/jobs"))
                .andExpect(flash().attributeExists("error"));
        verify(jobQueue, never()).enqueue(any(), any(), any(), any(), any(), anyBoolean());

        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        String html = mockMvc.perform(get("/mgmt/jobs")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("action=\"/mgmt/jobs/translate-site\"", "id=\"tsl-en\"",
                        "id=\"ts-pages\" value=\"pages\" checked", "2000")
                .doesNotContain("id=\"tsl-bg\"", "id=\"ts-tags\" value=\"tags\" checked");

        mockMvc.perform(post("/mgmt/jobs/translate-site").with(csrf()).param("parts", "social", "pages", "users")
                        .param("languages", "en", "bg").param("next", "true"))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID));
        verify(jobQueue).enqueue(eq("test"), any(), eq(JobType.TRANSLATE_SITE),
                eq(Map.of("parts", "pages,social", "languages", "en", "mode", "missing")), any(), eq(true));

        mockMvc.perform(post("/mgmt/jobs/translate-site").with(csrf()).param("parts", "users").param("languages", "en"))
                .andExpect(redirectedUrl("/mgmt/jobs"))
                .andExpect(flash().attribute("error", containsString("Отметнете поне едно нещо")));
        verify(jobQueue, times(1)).enqueue(any(), any(), any(), any(), any(), anyBoolean());
    }

    @MockitoBean
    private io.pacod.cms.studio.ai.WritingAssist writingAssist;

    @Test
    @DisplayName("the writing help answers JSON: a 409 saying why without AI, the cleaned answer with it")
    void writingHelp() throws Exception {
        mockMvc.perform(post("/mgmt/ai/assist").with(csrf()).contentType("application/json")
                        .content("{\"action\":\"shorten\",\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("PACOD_GW_URL")));

        when(aiAccess.refusalHere()).thenReturn(Optional.empty());
        when(writingAssist.rewrite(eq(io.pacod.cms.studio.ai.WritingAssist.Action.SHORTEN), eq("<p>x</p>"))).thenReturn(new io.pacod.cms.studio.ai.WritingAssist.Written("<p>y</p>", List.of()));
        mockMvc.perform(post("/mgmt/ai/assist").with(csrf()).contentType("application/json")
                        .content("{\"action\":\"shorten\",\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<p>y</p>")));
        mockMvc.perform(post("/mgmt/ai/assist").with(csrf()).contentType("application/json")
                        .content("{\"action\":\"delete-everything\",\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isBadRequest());
        when(writingAssist.tags(any())).thenReturn(List.of("wine"));
        mockMvc.perform(post("/mgmt/ai/tags").with(csrf()).contentType("application/json").content("{\"articleId\":42}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"wine\"")));

        when(writingAssist.titles(eq("Old"), eq("<p>x</p>"))).thenReturn(List.of("New title"));
        mockMvc.perform(post("/mgmt/ai/titles").with(csrf()).contentType("application/json")
                        .content("{\"title\":\"Old\",\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"New title\"")));
        when(writingAssist.draft(eq("A fair"), eq(io.pacod.cms.studio.ai.WritingAssist.Length.SHORT)))
                .thenReturn(new io.pacod.cms.studio.ai.WritingAssist.Draft("T", "D", "<p class=\"body-lead\">B</p>", List.of("1998")));
        mockMvc.perform(post("/mgmt/ai/draft").with(csrf()).contentType("application/json")
                        .content("{\"brief\":\"A fair\",\"length\":\"short\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"title\":\"T\"")))
                .andExpect(content().string(containsString("body-lead")))
                .andExpect(content().string(containsString("\"unverified\":[\"1998\"]")));
        mockMvc.perform(post("/mgmt/ai/draft").with(csrf()).contentType("application/json")
                        .content("{\"brief\":\"A fair\",\"length\":\"endless\"}"))
                .andExpect(status().isBadRequest());
        when(writingAssist.write(eq("more"), eq("<p>x</p>"))).thenReturn(new io.pacod.cms.studio.ai.WritingAssist.Written("<p>more</p>", List.of()));
        mockMvc.perform(post("/mgmt/ai/write").with(csrf()).contentType("application/json")
                        .content("{\"brief\":\"more\",\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<p>more</p>")));
        when(writingAssist.designBody(eq("<p>x</p>"))).thenThrow(new io.pacod.cms.studio.ai.AiGateway.AiException("The answer changed the wording"));
        mockMvc.perform(post("/mgmt/ai/design-body").with(csrf()).contentType("application/json")
                        .content("{\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(containsString("changed the wording")));
    }

    @Test
    @DisplayName("the editor's AI controls appear only where AI is available")
    void editorAiControls() throws Exception {
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/articles/42")).andReturn().getResponse().getContentAsString())
                .contains("data-ai-assist=\"false\"").doesNotContain("data-ai-describe", "data-ai-tags");
        when(aiAccess.availableHere()).thenReturn(true);
        String html = mockMvc.perform(get("/mgmt/articles/42")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("data-ai-assist=\"true\"", "data-ai-describe", "data-ai-tags", "data-ai-titles", "data-ai-draft");
        // The draft card is outside #article-form (its first </form> is its end: it nests none), and
        // neither it nor the titles control carries a name save() would bind.
        int formEnd = html.indexOf("</form>", html.indexOf("id=\"article-form\""));
        org.assertj.core.api.Assertions.assertThat(html.indexOf("data-ai-draft")).isGreaterThan(formEnd);
        org.assertj.core.api.Assertions.assertThat(html.substring(html.indexOf("data-ai-draft"), html.indexOf("data-ai-draft-make")))
                .doesNotContain("name=");
        org.assertj.core.api.Assertions.assertThat(html.substring(html.indexOf("data-ai-titles"), html.indexOf("data-ai-title-list")))
                .doesNotContain("name=");
    }

    @Test
    @DisplayName("the body's styled blocks are linked for the editor and the AI preview")
    void bodyBlocksStylesheet() throws Exception {
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/articles/42")).andReturn().getResponse().getContentAsString())
                .contains("data-body-blocks", "/mgmt-assets/body-blocks.css");
    }

    @Test
    @DisplayName("the image screen edits an alt text per language, the original first; a suggestion only with AI")
    void imageAltText() throws Exception {
        String html = mockMvc.perform(get("/mgmt/images/9")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("name=\"alt_bg\"", "name=\"alt_en\"").doesNotContain("data-ai-alt");
        org.assertj.core.api.Assertions.assertThat(html.indexOf("name=\"alt_bg\"")).isLessThan(html.indexOf("name=\"alt_en\""));
        when(aiAccess.availableHere()).thenReturn(true);
        org.assertj.core.api.Assertions.assertThat(mockMvc.perform(get("/mgmt/images/9")).andReturn().getResponse().getContentAsString())
                .contains("data-ai-alt", "data-lang=\"en\"");

        mockMvc.perform(post("/mgmt/images/9").with(csrf()).param("caption", "C").param("alt_en", "A garden").param("alt_bg", ""))
                .andExpect(redirectedUrl("/mgmt/images/9"));
        verify(imageService).saveDescription(eq(9), eq("C"), eq(Map.of("en", "A garden", "bg", "")));
    }

    @Test
    @DisplayName("cancel, promote and delete come back where the request is visible")
    void jobActions() throws Exception {
        when(jobQueue.cancel(any(), eq(JOB_ID))).thenReturn(true);
        mockMvc.perform(post("/mgmt/jobs/" + JOB_ID + "/cancel").with(csrf()))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID))
                .andExpect(flash().attributeExists("message"));
        mockMvc.perform(post("/mgmt/jobs/" + JOB_ID + "/promote").with(csrf()))
                .andExpect(redirectedUrl("/mgmt/jobs/" + JOB_ID))
                .andExpect(flash().attributeExists("error"));
        when(jobQueue.delete(any(), eq(JOB_ID))).thenReturn(true);
        mockMvc.perform(post("/mgmt/jobs/" + JOB_ID + "/delete").with(csrf()))
                .andExpect(redirectedUrl("/mgmt/jobs"));
    }

    @Test
    @DisplayName("the monitoring screen renders, chart and all")
    void rendersMonitoringScreen() throws Exception {
        mockMvc.perform(get("/mgmt/monitoring"))
                .andExpect(status().isOk())
                // The chart is server-rendered SVG. If this string is absent the page still looks
                // fine and shows nothing, which is exactly the failure mode the whole
                // draw-it-in-Java decision exists to rule out.
                .andExpect(content().string(containsString("<polyline")))
                .andExpect(content().string(containsString("class=\"chart-svg\"")))
                // Hover tooltips are <title> elements, not JavaScript. Losing them is silent.
                .andExpect(content().string(containsString("09.08 14:00")))
                // The live figure is rendered by the server as well as polled, so the screen is
                // not blank with scripting off.
                .andExpect(content().string(containsString("id=\"live-count\"")))
                .andExpect(content().string(containsString(">7<")))
                .andExpect(content().string(containsString("Новият мост над реката")))
                .andExpect(content().string(containsString("Акме")))
                // An off-origin HTML5 booking cannot have its clicks observed and must show a
                // dash, not a 0. The literal character: th:text escapes, it does not emit an entity.
                .andExpect(content().string(containsString("\u2014")))
                // The banner chart's third series. A key with no CSS draws nothing, so the legend
                // entry is what proves the series reached the screen at all.
                .andExpect(content().string(containsString("chart-key viewable")))
                // The nav entry, which is shared chrome and therefore easy to break from elsewhere.
                .andExpect(content().string(containsString("<span class=\"nav-text\">Статистика</span>")));
    }

    @Test
    @DisplayName("the selected window reaches the service and comes back selected in the picker")
    void monitoringRangeIsWiredThrough() throws Exception {
        mockMvc.perform(get("/mgmt/monitoring").param("range", "30d"))
                .andExpect(status().isOk())
                // Echoed back, or the picker would reset itself on every submit.
                .andExpect(content().string(containsString("Последните 30 дни")));

        verify(monitoringService).series(MonitoringRange.D30);

        // An unknown window is the default, not an error page.
        mockMvc.perform(get("/mgmt/monitoring").param("range", "nonsense"))
                .andExpect(status().isOk());

        verify(monitoringService).series(MonitoringRange.H24);
    }

    /**
     * The tiles and the table come from one report, so the arithmetic on screen has to close:
     * the clicks tile over the impressions tile is the CTR printed beneath them. Over the four
     * fixture buckets (120, 90, 0 and 45 views): booking 3 is served 1530 and clicked 37
     * (18 + 13 + 0 + 6, integer division per bucket), booking 4 is served 765 and clicked 18
     * (9 + 6 + 0 + 3), and booking 5 is served 765 but, being off-origin, contributes no clicks
     * and is left out of the rates: 55 / 2295 = 2,40 % -- a decimal comma, because the console's
     * default language is Bulgarian and #numbers follows it.
     */
    @Test
    @DisplayName("the advertising tiles and table are per period, and an HTML5 booking reports clicks")
    void monitoringBannerReportIsPerPeriod() throws Exception {
        String html = mockMvc.perform(get("/mgmt/monitoring"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // The table follows the range. «Най-четени статии» is all-time and says so; the
                // advertising table carries no all-time hint.
                .doesNotContain("общо, за цялото време")
                // Served 1530 + 765 + 765; clicks 37 + 18.
                .contains(">3060<", ">55<")
                // 55 / (1530 + 765), the off-origin booking excluded -- and said so.
                .contains("CTR 2,40 %")
                .contains("по проследяваните реклами")
                // The same-origin HTML5 booking reports its clicks, not a dash.
                .contains(">18<")
                // Viewable: a third of what was served.
                .contains("видими 1020")
                // 55 / 765 viewable on the two click-tracked bookings.
                .contains("vCTR 7,19 %");
    }

    @Test
    @DisplayName("?banner= narrows the chart, survives a change of range, and is cleared by «Всички»")
    void monitoringBannerFilterIsWiredThrough() throws Exception {
        String html = mockMvc.perform(get("/mgmt/monitoring").param("range", "7d").param("banner", "4"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // The chart names what it is drawing.
                .contains("Реклама № 4 · Интерактивна")
                // A GET form replaces the whole query string: without this, picking another range
                // would silently widen the chart back to every booking.
                .contains("name=\"banner\" value=\"4\"")
                // The row is marked, and every row link keeps the range.
                .contains("row-selected")
                .contains("/mgmt/monitoring?range=7d&amp;banner=3#banner-chart")
                // «Всички» clears the filter and keeps the range.
                .contains("href=\"/mgmt/monitoring?range=7d#banner-chart\"");
    }

    @Test
    @DisplayName("a ?banner= that names nothing is no filter, never a 400 or a heading for nothing")
    void unknownBannerFilterIsNormalised() throws Exception {
        for (String junk : List.of("abc", "999", " ")) {
            String html = mockMvc.perform(get("/mgmt/monitoring").param("banner", junk))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(html)
                    .as("?banner=%s", junk)
                    .doesNotContain("Реклама № ", "name=\"banner\"", "row-selected");
        }
    }

    @Test
    @DisplayName("with nothing recorded the chart says so instead of drawing a flat zero line")
    void monitoringWithoutDataRendersTheEmptyState() throws Exception {
        // The state every fresh deployment is in: nothing is back-filled, so the series is
        // genuinely empty. A line along the axis would read as a site nobody visits.
        stubMonitoring(List.of(bucket(2, 0, 0), bucket(1, 0, 0)));

        mockMvc.perform(get("/mgmt/monitoring"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Още няма записани данни")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<polyline"))));
    }

    @Test
    @DisplayName("a failed live count shows as unavailable, never as zero readers")
    void unavailableLiveCountIsNotZero() throws Exception {
        when(monitoringService.liveVisitors()).thenReturn(MgmtMonitoringService.LiveCount.unavailable());

        mockMvc.perform(get("/mgmt/monitoring"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("няма връзка с базата")));
    }

    @Test
    @DisplayName("the live poll answers JSON, past the /mgmt/** catch-all")
    void liveCountIsJson() throws Exception {
        // Two segments under a screen, so this is also pinning that MgmtHomeController's
        // @GetMapping("/mgmt/**") loses on pattern specificity -- the same thing
        // jobStatusIsJson pins for /mgmt/jobs/{id}/status.
        mockMvc.perform(get("/mgmt/monitoring/live"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("\"active\":7")))
                .andExpect(content().string(containsString("\"available\":true")));
    }

    @Test
    @DisplayName("the crawler screen renders: tiles, chart, per-bot table, top paths and the log")
    void rendersCrawlerScreen() throws Exception {
        String html = mockMvc.perform(get("/mgmt/crawlers"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // 40+12+3 and 25+8+1 hits; 2+2 errors; 1+1 forged; three bots.
                .contains(">89<", ">4<", ">2<", ">3<")
                // One line per category with hits, each with its CSS key -- a key without a rule
                // draws nothing, so the polyline's class is what proves the series is drawable.
                .contains("class=\"crawl-search\"/>", "class=\"crawl-ai\"/>", "class=\"crawl-other\"/>")
                .doesNotContain("class=\"crawl-seo\"/>")
                .contains("googlebot", "gptbot", "Търсачки", "Изкуствен интелект", "Други")
                // «Последно» comes from the log over its whole retention, not from the buckets.
                .contains("преди 12 мин")
                .contains("/robots.txt", "/article/999999")
                // The log: the address Caddy saw, and a forged Googlebot labelled as one.
                .contains("203.0.113.7", ">фалшив<", ">непроверен<")
                // The path box is the one mgmt.js debounces and binds "/" to.
                .contains("id=\"search-input\"")
                .contains("<span class=\"nav-text\">Роботи</span>", "href=\"#i-bot\"")
                // What the screen cannot see is said on it.
                .contains("/assets/", "/resources/")
                // History older than 48 h and every watched bot seen: no warning.
                .doesNotContain("flash warn", "индексирането може да е спряло");
    }

    @Test
    @DisplayName("the log's filters are normalised, reach the service and ride on every pager link")
    void crawlerFiltersAreWiredThrough() throws Exception {
        String html = mockMvc.perform(get("/mgmt/crawlers")
                        .param("range", "7d").param("bot", "googlebot").param("category", "search")
                        .param("verification", "unverified").param("errors", "1").param("q", "/article")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(crawlerService).series(MonitoringRange.D7);
        verify(crawlerService).log(eq(new MgmtCrawlerService.LogFilter("googlebot",
                        io.pacod.cms.core.data.CrawlerCategory.SEARCH,
                        io.pacod.cms.core.data.CrawlerVerification.UNVERIFIED, true, "/article")),
                any(), eq(2));
        org.assertj.core.api.Assertions.assertThat(html)
                // Both pager links carry every filter, normalised -- SEARCH, not the "search" typed.
                .contains("bot=googlebot&amp;category=SEARCH&amp;verification=UNVERIFIED&amp;errors=1&amp;q=/article&amp;page=1#log")
                .contains("bot=googlebot&amp;category=SEARCH&amp;verification=UNVERIFIED&amp;errors=1&amp;q=/article&amp;page=3#log")
                // The range form carries the log's filters back, or picking a range would drop them.
                .contains("name=\"bot\" value=\"googlebot\"", "name=\"category\" value=\"SEARCH\"")
                .contains("Показани 101–102")
                .contains("row-selected");
    }

    @Test
    @DisplayName("nonsense filters are no filter and page one -- never a 400")
    void junkCrawlerFiltersAreNormalised() throws Exception {
        mockMvc.perform(get("/mgmt/crawlers")
                        .param("bot", "Evil.Bot{$ne:1}").param("category", "nonsense")
                        .param("verification", "x").param("errors", "maybe").param("q", "   ")
                        .param("page", "abc"))
                .andExpect(status().isOk());

        verify(crawlerService).log(eq(new MgmtCrawlerService.LogFilter(null, null, null, false, null)),
                any(), eq(0));
    }

    @Test
    @DisplayName("a watched crawler gone quiet is announced, with how long it has been")
    void staleCrawlerWarning() throws Exception {
        stubCrawlers(List.of(crawlerBucket(1, 5, 1, 1)), new MgmtCrawlerService.Watch(false,
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                List.of(new MgmtCrawlerService.Stale("bingbot", null))));

        mockMvc.perform(get("/mgmt/crawlers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("flash warn")))
                .andExpect(content().string(containsString("не е идвал през последните 31 дни")));
    }

    @Test
    @DisplayName("while the log is younger than 48 hours the screen says it is collecting, not that Googlebot left")
    void crawlerLogStillCollecting() throws Exception {
        stubCrawlers(List.of(crawlerBucket(1, 5, 1, 1)), new MgmtCrawlerService.Watch(true, null, List.of()));

        mockMvc.perform(get("/mgmt/crawlers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Още няма записани посещения от роботи")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("flash warn"))));
    }

    @Test
    @DisplayName("with no crawler traffic the chart says so instead of drawing a flat zero line")
    void crawlerScreenEmptyState() throws Exception {
        stubCrawlers(List.of(new MgmtCrawlerService.Bucket(LocalDateTime.of(2026, 8, 9, 14, 0), "14:00",
                        "09.08 14:00", java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of())),
                new MgmtCrawlerService.Watch(true, null, List.of()));
        when(crawlerService.log(any(), any(), anyInt()))
                .thenReturn(new MgmtCrawlerService.LogPage(List.of(), 0, false));
        when(crawlerService.topPaths(any())).thenReturn(List.of());

        mockMvc.perform(get("/mgmt/crawlers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Още няма записани данни")))
                .andExpect(content().string(containsString("Няма записани посещения от роботи за периода.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<polyline"))));
    }

    @Test
    @DisplayName("on the twelve-month range the log-backed cards say where the log stops")
    void crawlerLogCardsSayWhereTheyStop() throws Exception {
        mockMvc.perform(get("/mgmt/crawlers").param("range", "12m"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("последните 31 дни")));
    }

    @Test
    @DisplayName("the monitoring screen says crawlers are not in its numbers, and where they are counted")
    void monitoringPointsAtTheCrawlerScreen() throws Exception {
        mockMvc.perform(get("/mgmt/monitoring"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Роботите не се броят тук")))
                .andExpect(content().string(containsString("/mgmt/crawlers?range=24h")));
    }

    @Test
    @DisplayName("the pages screens render, with the language coverage and the editor")
    void rendersPageScreens() throws Exception {
        mockMvc.perform(get("/mgmt/pages"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Условия за ползване")))
                .andExpect(content().string(containsString("/p/terms")))
                // The coverage column reads the stored bodies, so it is the operator's to-do list.
                // Scoped to the chips: the console's own language switch says BG and EN on every
                // screen, so a bare ">BG<" would pass with the column empty.
                .andExpect(content().string(containsString("<span class=\"chip\">BG</span>")))
                .andExpect(content().string(containsString("<span class=\"chip\">EN</span>")))
                .andExpect(content().string(containsString("id=\"search-input\"")))
                .andExpect(content().string(containsString("<span class=\"nav-text\">Страници</span>")));

        mockMvc.perform(get("/mgmt/pages/terms"))
                .andExpect(status().isOk())
                // id="text" is what mgmt.js binds TinyMCE to, and id="article-form" is what the
                // save bar, Ctrl+S and the unsaved-changes guard look for. Rename either and the
                // screen degrades silently to a plain textarea with no editor.
                .andExpect(content().string(containsString("id=\"text\"")))
                .andExpect(content().string(containsString("id=\"article-form\"")))
                .andExpect(content().string(containsString("tinymce/tinymce.min.js")));

        mockMvc.perform(get("/mgmt/pages/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Нова страница")));
    }

    @Test
    @DisplayName("the language tab reaches the form, and an empty translation opens empty")
    void pageLanguageTabIsWiredThrough() throws Exception {
        // German has no stored body, so the fields must be BLANK -- loading page.title(lang) here
        // would show the Bulgarian text and saving it would store the original as a "translation".
        mockMvc.perform(get("/mgmt/pages/terms").param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Немски")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("value=\"Условия за ползване\""))));

        mockMvc.perform(get("/mgmt/pages/terms").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Terms of use\"")));
    }

    @Test
    @DisplayName("saving a page requires CSRF, like every other console write")
    void pageSaveRequiresCsrf() throws Exception {
        mockMvc.perform(post("/mgmt/pages/terms").param("title", "x").param("lang", "bg"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/pages/terms")
                        .param("title", "Условия").param("lang", "bg").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/pages/terms?lang=bg"));
    }

    @Test
    @DisplayName("the layout screens render, with the folder report, the slides and the preview")
    void rendersLayoutScreens() throws Exception {

        mockMvc.perform(get("/mgmt/banners/layouts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("acme-2026")))
                .andExpect(content().string(containsString("id=\"search-input\"")))
                .andExpect(content().string(containsString("<span class=\"nav-text\">Макети</span>")))
                // The import card lives on the list because UploadSizeAdvice redirects an oversize
                // upload to this URL's parent, which is this page.
                .andExpect(content().string(containsString("/mgmt/banners/layouts/import")));

        mockMvc.perform(get("/mgmt/banners/layouts/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Странично 300x250")));

        mockMvc.perform(get("/mgmt/banners/layouts/acme-2026"))
                .andExpect(status().isOk())
                // A bound th:field on an indexed list is the thing that blows up at render time and
                // nowhere earlier, so both slides have to actually appear.
                .andExpect(content().string(containsString("name=\"slides[0].href\"")))
                .andExpect(content().string(containsString("name=\"slides[1].href\"")))
                .andExpect(content().string(containsString("acme-1.webp")))
                // The upload trio mgmt.js binds, and the form id the save bar and Ctrl+S look for.
                .andExpect(content().string(containsString("id=\"dropzone\"")))
                .andExpect(content().string(containsString("id=\"article-form\"")))
                // The «Файлове» card reads the stub, not target/test-banners.
                .andExpect(content().string(containsString("css/banner.css")))
                // The hand-off, and the preview that must stay click-to-load.
                .andExpect(content().string(containsString("/mgmt/banners/new?layout=acme-2026")))
                .andExpect(content().string(containsString("id=\"layout-preview-load\"")))
                // TinyMCE has no business on this screen -- see the template's header comment.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("tinymce/tinymce.min.js"))));
    }

    @Test
    @DisplayName("the skeleton screens render as plain textareas, never as a rich-text editor")
    void rendersSkeletonScreens() throws Exception {

        mockMvc.perform(get("/mgmt/banners/layouts/skeletons"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Странично 300x250")))
                .andExpect(content().string(containsString("id=\"search-input\"")));

        mockMvc.perform(get("/mgmt/banners/layouts/skeletons/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/mgmt/banners/layouts/skeletons/read")));

        mockMvc.perform(get("/mgmt/banners/layouts/skeletons/sidebar"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("banner-container-inline")))
                .andExpect(content().string(containsString("id=\"html\"")))
                .andExpect(content().string(containsString("id=\"css\"")))
                // id="text" is what mgmt.js binds TinyMCE to, and an editor configured to preserve
                // stored article bodies would still reformat this markup on every open.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("id=\"text\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("tinymce/tinymce.min.js"))))
                // A built-in is editable but not deletable: the seed only runs on an empty
                // collection, so deleting one would be permanent with nothing saying so.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("Изтрий шаблона"))));
    }

    @Test
    @DisplayName("the booking form pre-fills from a layout, and never with the AUTO frame size")
    void bannerFormPrefillsFromALayout() throws Exception {
        mockMvc.perform(get("/mgmt/banners/new").param("layout", "acme-2026"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "value=\"/resources/banners/acme-2026/index.html\"")))
                .andExpect(content().string(containsString("Попълнено от макета")))
                // The derived size, selected. AUTO would render a frame of no height at all.
                .andExpect(content().string(containsString("value=\"SQUARE\" selected")));
    }

    @Test
    @DisplayName("saving a layout requires CSRF, like every other console write")
    void layoutSaveRequiresCsrf() throws Exception {
        mockMvc.perform(post("/mgmt/banners/layouts/acme-2026")
                        .param("title", "x").param("rotationMs", "10000"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mgmt/banners/layouts/acme-2026")
                        .param("title", "Acme").param("rotationMs", "10000")
                        .param("slides[0].href", "https://www.example.com/acme/")
                        .param("slides[1].href", "https://www.example.com/acme/product-three")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/layouts/acme-2026"));
    }

    @Test
    @DisplayName("a refusal over a stale slide list re-renders against the document, not blank rows")
    void staleSlideListRerendersAgainstTheDocument() throws Exception {
        // The document has two slides; a tab opened before an upload posts one. The template
        // iterates the document's slides and binds against the form, so without squaring the two
        // up the binder auto-grows the short list into a blank row -- and the operator is told to
        // reload while looking at a field that was never theirs.
        when(layoutService.save(any(), any()))
                .thenThrow(new MgmtOperationException("Кадрите на макета са променени"));

        mockMvc.perform(post("/mgmt/banners/layouts/acme-2026")
                        .param("title", "Acme").param("rotationMs", "10000")
                        .param("slides[0].href", "https://example.com/typed")
                        .with(csrf()))
                .andExpect(status().isOk())
                // What the operator typed at a position that still exists survives...
                .andExpect(content().string(containsString("value=\"https://example.com/typed\"")))
                // ...and the position they never posted comes back from the document.
                .andExpect(content().string(containsString("value=\"https://www.example.com/acme/product-three\"")));
    }

    @Test
    @DisplayName("the two filesystem endpoints accept a real multipart POST with CSRF")
    void layoutUploadsReachTheService() throws Exception {

        // MgmtSecurityTest proves these are refused without a token. This proves the multipart
        // plumbing behind them is wired at all: a @RequestParam MultipartFile that never resolves
        // fails the same way a missing route does.
        mockMvc.perform(multipart("/mgmt/banners/layouts/acme-2026/slides")
                        .file(new MockMultipartFile("file", "acme-3.webp", "image/webp",
                                new byte[]{1, 2, 3}))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/layouts/acme-2026"));
        verify(layoutService).addSlide(eq("acme-2026"), any());

        when(layoutService.importArchive(any(), any(), any())).thenReturn(layout());
        mockMvc.perform(multipart("/mgmt/banners/layouts/import")
                        .file(new MockMultipartFile("file", "globex.zip", "application/zip",
                                new byte[]{1, 2, 3}))
                        .param("id", "globex").param("title", "Globex")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/layouts/acme-2026"));
        verify(layoutService).importArchive(eq("globex"), eq("Globex"), any());
    }

    @Test
    @DisplayName("the list offers the folders already on the volume, and says which cannot be adopted")
    void rendersDiscoveredFolders() throws Exception {
        // Without this panel the screens describe only folders the console itself created, which on
        // the live volume is none of the bookings the site is actually running.
        mockMvc.perform(get("/mgmt/banners/layouts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Папки на диска без макет")))
                .andExpect(content().string(containsString("/mgmt/banners/layouts/adopt")))
                .andExpect(content().string(containsString("acme2")))
                // A folder with no unambiguous entry file is listed but its button is disabled --
                // the console says what it found rather than guessing a file.
                .andExpect(content().string(containsString("няма еднозначен HTML файл")));
    }

    @Test
    @DisplayName("the builder offers a per-frame image swap that needs no JavaScript")
    void rendersTheSlideSwapControl() throws Exception {
        String html = mockMvc.perform(get("/mgmt/banners/layouts/acme-2026"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("slides/0/image")))
                .andExpect(content().string(containsString("slides/1/image")))
                // A real file input and a real submit button, both reaching the out-of-line form
                // through form= -- the save bar's mechanism, and the reason it works with
                // scripting off. An inline onchange handler would not.
                .andExpect(content().string(containsString("class=\"slide-swap\" data-no-dirty")))
                .andExpect(content().string(containsString("form=\"slide-img-0\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("onchange="))))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // The rows bind by explicit name because they are a fragment the upload endpoint
                // also renders -- the names applySlides counts must be there.
                .contains("name=\"slides[0].href\"", "name=\"slides[1].alt\"")
                .contains("value=\"https://www.example.com/acme/product-three\"")
                // The thumbnail is the scripted replace target, and picking a file there is a
                // durable upload, not an unsaved edit.
                .contains("class=\"slide-thumb\" data-no-dirty data-slide-index=\"0\"")
                // Scripted add: the new attribute, never data-async -- that one is the article's.
                .contains("id=\"image-form\" data-slide-upload")
                .doesNotContain("data-async")
                .contains("id=\"upload-status\"")
                // The fragment definitions are never rendered on the page itself.
                .doesNotContain("upload-refusal")
                // Each row's four action forms, exactly once each.
                .containsOnlyOnce("id=\"slide-img-1\"")
                .containsOnlyOnce("id=\"slide-forms\"");
    }

    @Test
    @DisplayName("adding a frame through fetch answers with the bound row and its action forms")
    void slideUploadAnswersWithTheRow() throws Exception {
        io.pacod.cms.core.data.BannerLayout grown = layout();
        grown.getSlides().add(new io.pacod.cms.core.data.BannerSlide("acme-3.webp"));
        when(layoutService.addSlide(eq("acme-2026"), any())).thenReturn(grown);

        String html = mockMvc.perform(multipart("/mgmt/banners/layouts/acme-2026/slides/upload")
                        .file(new MockMultipartFile("file", "acme-3.webp", "image/webp", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                // Bound at the new index, so the next save posts the count the document holds.
                .contains("name=\"slides[2].href\"", "name=\"slides[2].title\"", "name=\"slides[2].alt\"")
                .contains("/resources/banners/acme-2026/acme-3.webp")
                .contains("id=\"slide-up-2\"", "id=\"slide-down-2\"", "id=\"slide-del-2\"", "id=\"slide-img-2\"")
                .contains("slides/2/image")
                // Only the new row, not the page around it.
                .doesNotContain("slides[0].href")
                .doesNotContain("<html");
    }

    @Test
    @DisplayName("a refused frame upload answers 409 with the reason, not a redirect")
    void slideUploadRefusalIsA409() throws Exception {
        when(layoutService.addSlide(any(), any())).thenThrow(new MediaException("big.webp is too large"));

        mockMvc.perform(multipart("/mgmt/banners/layouts/acme-2026/slides/upload")
                        .file(new MockMultipartFile("file", "big.webp", "image/webp", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("class=\"upload-refusal\"")))
                .andExpect(content().string(containsString("Файлът не беше приет: big.webp is too large")));
    }

    @Test
    @DisplayName("swapping a frame's picture through fetch answers with the new name")
    void slideImageUploadAnswersWithTheNewName() throws Exception {
        io.pacod.cms.core.data.BannerLayout swapped = layout();
        swapped.getSlides().getFirst().setImage("acme-1-b.webp");
        when(layoutService.replaceSlideImage(eq("acme-2026"), eq(0), any())).thenReturn(swapped);

        mockMvc.perform(multipart("/mgmt/banners/layouts/acme-2026/slides/0/image/upload")
                        .file(new MockMultipartFile("file", "new.webp", "image/webp", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"image\":\"acme-1-b.webp\"")))
                .andExpect(content().string(containsString(
                        "\"src\":\"/resources/banners/acme-2026/acme-1-b.webp\"")));

        when(layoutService.replaceSlideImage(eq("acme-2026"), eq(1), any()))
                .thenThrow(new MediaException("new.webp is not an image"));
        mockMvc.perform(multipart("/mgmt/banners/layouts/acme-2026/slides/1/image/upload")
                        .file(new MockMultipartFile("file", "new.webp", "image/webp", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Файлът не беше приет: new.webp is not an image")));
    }

    @Test
    @DisplayName("the files screen lists what can be edited, what can be swapped, and what neither")
    void rendersTheFileScreen() throws Exception {
        mockMvc.perform(get("/mgmt/banners/layouts/acme-2026/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("js/banner.js")))
                .andExpect(content().string(containsString("Редактирай")))
                .andExpect(content().string(containsString("acme-1.webp")))
                .andExpect(content().string(containsString("files/replace")))
                .andExpect(content().string(containsString("fonts/x.woff2")))
                // No textarea until a file is chosen.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"content\""))));

        mockMvc.perform(get("/mgmt/banners/layouts/acme-2026/files").param("file", "js/banner.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"content\"")))
                .andExpect(content().string(containsString("clickTag")))
                // TinyMCE binds to id="text"; an editor configured to preserve stored article
                // bodies would still reformat a banner's script on every open.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"text\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("tinymce.min.js"))));
    }

    @Test
    @DisplayName("adoption and the file writes require CSRF and land back where they came from")
    void adoptionAndFileWritesRequireCsrf() throws Exception {
        mockMvc.perform(post("/mgmt/banners/layouts/adopt").param("folder", "acme2"))
                .andExpect(status().isForbidden());

        when(layoutService.adopt("acme2")).thenReturn(layout());
        mockMvc.perform(post("/mgmt/banners/layouts/adopt").param("folder", "acme2").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/layouts/acme-2026"));
        verify(layoutService).adopt("acme2");

        mockMvc.perform(post("/mgmt/banners/layouts/acme-2026/files")
                        .param("file", "js/x.js").param("content", "var a = 1;").with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(layoutService).saveFile("acme-2026", "js/x.js", "var a = 1;");

        mockMvc.perform(multipart("/mgmt/banners/layouts/acme-2026/slides/0/image")
                        .file(new MockMultipartFile("file", "new.webp", "image/webp", new byte[]{1}))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/banners/layouts/acme-2026"));
        verify(layoutService).replaceSlideImage(eq("acme-2026"), eq(0), any());
    }

    /**
     * The same screens with {@code app.image.dimension-check.enabled} off.
     *
     * <p>A separate context because the setting is read at startup, which is also what the console
     * documents to the operator. Everything else -- the mocks, the signed-in user, the MockMvc
     * build -- is inherited from the enclosing class.
     *
     * <p>What is under test is not the refusal (that is {@code ImageServiceValidationTest}'s) but
     * the sentences: a screen that goes on promising "поне 600×400 px, 3:2" while the server accepts
     * anything still renders, still answers 200, and still passes every assertion above. The
     * build cannot see a stale promise; only a rendered string can.
     */
    @Nested
    @TestPropertySource(properties = {
            "app.image.dimension-check.enabled=false",
            // Spelled the way an operator following this properties file's own idiom would spell
            // it -- spring.servlet.multipart.max-file-size=4MB sits nine lines below it. On a
            // plain long this fails String-to-long conversion at bean creation and the studio
            // container restart-loops; the field is a DataSize so that both spellings work. This
            // is the half ImageServiceValidationTest cannot prove: a genuine Spring Boot context,
            // which is what installs ApplicationConversionService in the first place.
            "app.image.max-bytes=2MB"})
    class DimensionCheckOff {

        @Autowired
        private ImageService boundRules;

        @Test
        @DisplayName("the byte cap binds from 2MB and reaches the screen as the number it means")
        void byteCapBindsFromADataSizeSpelling() throws Exception {
            org.assertj.core.api.Assertions.assertThat(boundRules.getMaxImageBytes())
                    .isEqualTo(2L * 1024 * 1024);

            // And the editor states what was bound rather than the default it no longer applies.
            org.assertj.core.api.Assertions.assertThat(
                            mockMvc.perform(get("/mgmt/articles/42"))
                                    .andReturn().getResponse().getContentAsString())
                    .contains("2048 KB")
                    .doesNotContain("128 KB");
        }

        @Test
        @DisplayName("the editor stops promising a size and shape but still states the other limits")
        void editorHintFollowsTheSetting() throws Exception {
            String html = mockMvc.perform(get("/mgmt/articles/42"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(html)
                    .doesNotContain("\"hint\">поне 600×400 px")
                    // The whole sentence. WxH is named as a recommendation rather than dropped --
                    // an operator with no number in front of them has nothing to aim at, and
                    // 600×400 is the shape every image uploaded under the default rule has. The
                    // byte cap and the per-article cap are untouched by THIS switch and go on being
                    // stated; dropping them would trade one wrong clause for a missing one. The cap
                    // reads 2048 KB because this context also binds app.image.max-bytes=2MB.
                    .contains("размерите не се проверяват (препоръчително поне 600×400 px, 3:2), "
                            + "до 2048 KB, най-много 10 на статия");
        }

        @Test
        @DisplayName("the image detail screen stops calling an off-size file non-standard")
        void detailNoteFollowsTheSetting() throws Exception {
            // fileOf() is stubbed at 800x531, which is off-shape at the default 600x400 -- so with
            // the check on this screen carries the note, and this asserts it has gone rather than
            // that it was never there.
            String html = mockMvc.perform(get("/mgmt/images/3"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(html)
                    .doesNotContain("стандартът за нови качвания")
                    // The dimensions themselves are still reported: what the file is remains a
                    // fact about it whether or not anything is required of it.
                    .contains("800")
                    .contains("531");
        }
    }

    /**
     * The other switch, on its own: {@code app.image.file-size-check.enabled} off with the
     * dimension check left on.
     *
     * <p>A second context rather than a second assertion, because one render cannot show both
     * sentences -- and this is the corner where "off" most easily becomes a lie. Saying nothing
     * about size would read as "any size", while Caddy and the servlet container go on refusing
     * above 4MB with a bare 413 that nothing in this application can phrase. So the hint has to
     * name the transport ceiling instead of the console's own, and that is what is asserted here.
     */
    @Nested
    @TestPropertySource(properties = "app.image.file-size-check.enabled=false")
    class FileSizeCheckOff {

        @Test
        @DisplayName("the editor names the server's ceiling in place of the console's cap")
        void editorHintStatesTheTransportCeiling() throws Exception {
            String html = mockMvc.perform(get("/mgmt/articles/42"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(html)
                    .doesNotContain("до 128 KB")
                    // The whole sentence: the dimension clause is untouched by this switch and
                    // still promises a size and shape, while the weight clause names the SERVER's ceiling
                    // in place of the console's. 4096 KB is spring.servlet.multipart.max-file-size
                    // read back, which this module sets to Caddy's request_body max_size -- not a
                    // number written down here a second time.
                    .contains("поне 600×400 px, 3:2, теглото не се проверява "
                            + "(сървърът приема до 4096 KB), най-много 10 на статия");
        }

        @Test
        @DisplayName("the image detail screen stops calling a heavy file over the limit")
        void detailNoteFollowsTheSetting() throws Exception {
            // fileOf() is stubbed at 96,000 bytes, which is under the 128 KB default -- so first
            // put a genuinely heavy file behind the screen, or this would assert the absence of a
            // note that was never going to render.
            when(imageService.fileOf(any())).thenReturn(
                    new MgmtImageService.ImageFile(true, 400_000L, 800, 531, LocalDateTime.now()));

            String html = mockMvc.perform(get("/mgmt/images/3"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(html)
                    .doesNotContain("над текущото ограничение за качване")
                    // The dimension note is on the other switch and still fires at 800x531.
                    .contains("стандартът за нови качвания");
        }
    }

    /* ------------------------------------------------------------------ /mgmt/social */

    private static SocialPage socialPage() {
        SocialPage page = new SocialPage();
        page.setPublished(true);
        page.putName("bg", "Примерен вестник");
        page.putBio("bg", "Новини и анализи.");

        SocialLink instagram = new SocialLink("instagra");
        instagram.setPlacement(SocialLinkPlacement.ICON);
        instagram.setIcon(SocialIcon.INSTAGRAM);
        instagram.setUrl("https://www.instagram.com/example/");
        page.getLinks().add(instagram);

        SocialLink subscribe = new SocialLink("button01");
        subscribe.setPlacement(SocialLinkPlacement.BUTTON);
        subscribe.putTitle("bg", "Абонамент");
        subscribe.setUrl("/p/subscription");
        page.getLinks().add(subscribe);
        return page;
    }

    private static List<MgmtSocialStatsService.Bucket> socialBuckets() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 11, 14, 0);
        return List.of(
                new MgmtSocialStatsService.Bucket(start.minusHours(1), "13:00", "11.10 13:00", 30, 12,
                        Map.of("INSTAGRAM", 25L, "FACEBOOK", 5L),
                        Map.of("button01", Map.of("INSTAGRAM", 4L)),
                        Map.of("3150", Map.of("INSTAGRAM", 1L))),
                new MgmtSocialStatsService.Bucket(start, "14:00", "11.10 14:00", 10, 6,
                        Map.of("INSTAGRAM", 10L),
                        Map.of("button01", Map.of("FACEBOOK", 1L), "deadlink", Map.of("INSTAGRAM", 2L)), Map.of()));
    }

    @Test
    @DisplayName("/mgmt/social renders the profile form, both link tables and the tagged addresses")
    void rendersSocialScreen() throws Exception {
        String html = mockMvc.perform(get("/mgmt/social"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"article-form\"", "id=\"name\"", "id=\"bio\"", "id=\"avatarImageId\"",
                        "id=\"latestArticlesCount\"", "name=\"lang\"")
                .contains("/mgmt/social/links/instagra/move", "/mgmt/social/links/button01/toggle")
                // Adding happens in place: a <details> add form inside each card, never a link to
                // another screen -- leaving this page with an unsaved bio raised the browser's
                // leave-page prompt, and declining it looked like a dead button.
                .contains("id=\"links-ICON\"", "id=\"links-BUTTON\"", "<details class=\"social-add\"",
                        "data-social-links", "name=\"placement\" value=\"ICON\"",
                        "name=\"placement\" value=\"BUTTON\"", "id=\"add-url-BUTTON\"")
                .doesNotContain("/mgmt/social/links/new")
                // An article link carries the article's cover beside its name.
                .containsPattern("<img class=\"thumb\"[^>]*src=\"/assets/img/articles/cover-3150.jpg\"")
                .contains("title=\"Статия № 3150\"")
                // The list's wording is «линк», not «бутон».
                .contains(">Линкове<", ">Нов линк<")
                .doesNotContain("Бутон", "бутон")
                // The address to paste names the website's own host, from its settings.
                .contains("https://www.example.org/social?src=instagram",
                        "https://www.example.org/social?src=qr")
                // The 30-day click column comes from the statistics service.
                .contains(">7<")
                .contains("<span class=\"nav-text\">Социална страница</span>",
                        "<span class=\"nav-text\">Социална статистика</span>")
                // TinyMCE binds to id="text"; the bio is plain text.
                .doesNotContain("id=\"text\"");

        // The row actions are their own forms AFTER the profile form; one inside it would be a
        // nested form, which HTML drops -- the ↑ would then submit the profile.
        org.assertj.core.api.Assertions.assertThat(html.indexOf("/mgmt/social/links/instagra/move"))
                .isGreaterThan(html.indexOf("id=\"save-status\""));
    }

    @Test
    @DisplayName("the German tab of /mgmt/social shows empty fields, never the Bulgarian text")
    void socialScreenShowsRawTranslations() throws Exception {
        String html = mockMvc.perform(get("/mgmt/social").param("lang", "de"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("value=\"de\"")
                .doesNotContain("value=\"Примерен вестник\"");
    }

    @Test
    @DisplayName("a social link renders its edit screen, which binds no id")
    void rendersSocialLinkScreens() throws Exception {
        String html = mockMvc.perform(get("/mgmt/social/links/button01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("Текст на линка", ">Линк<")
                .doesNotContain("Бутон", "бутон")
                .contains("id=\"title\"", "id=\"url\"", "id=\"placement\"", "id=\"icon\"",
                        "id=\"activeFrom\"", "id=\"activeTo\"", "id=\"active\"",
                        "value=\"Абонамент\"", "/mgmt/social/links/button01/delete",
                        "/mgmt/social/stats?link=button01")
                // The id is generated and is the statistics key; a bound one could be rewritten.
                .doesNotContain("name=\"id\"");

        mockMvc.perform(get("/mgmt/social/links/nosuchid")).andExpect(status().isNotFound());
    }

    /**
     * The in-place answers. mgmt.js posts a link card's forms with this header and swaps the card
     * for the answer, so the page -- and an unsaved bio in the profile form -- never unloads.
     */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder inCard(String path) {
        return post(path).with(csrf()).header("X-Mgmt-Fragment", "social-links");
    }

    @Test
    @DisplayName("adding a social link from its card answers with the card, not a redirect")
    void socialLinkIsAddedInPlace() throws Exception {
        String html = mockMvc.perform(inCard("/mgmt/social/links")
                        .param("placement", "BUTTON").param("lang", "bg").param("tab", "en")
                        .param("title", "Абонамент").param("url", "/p/subscription"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("data-social-card", "id=\"links-BUTTON\"", "Връзката е добавена.",
                        "/mgmt/social/links/button01/move")
                // The card alone: swapping in a whole page would nest one document in another.
                .doesNotContain("<html", "id=\"article-form\"")
                // The row forms still carry the tab the operator is on, for the no-script redirect.
                .contains("name=\"lang\" value=\"en\"");
        verify(socialService).createLink(any());
    }

    @Test
    @DisplayName("a refused add answers 422 with the card, the add form open and the typed values kept")
    void refusedSocialLinkKeepsTheForm() throws Exception {
        when(socialService.createLink(any()))
                .thenThrow(new MgmtOperationException("Адресът трябва да започва с http://"));

        String html = mockMvc.perform(inCard("/mgmt/social/links")
                        .param("placement", "ICON").param("lang", "bg")
                        .param("url", "javascript:alert(1)").param("icon", "INSTAGRAM"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"links-ICON\"", "Адресът трябва да започва с http://",
                        "value=\"javascript:alert(1)\"")
                .containsPattern("<details class=\"social-add\" open")
                .containsPattern("value=\"INSTAGRAM\"[^>]*selected");

        // Bean validation is refused the same way, before the service is asked at all.
        mockMvc.perform(inCard("/mgmt/social/links").param("placement", "BUTTON").param("url", " "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Адресът е задължителен.")));
    }

    @Test
    @DisplayName("↑/↓ and on/off answer with the card in place; without the header they redirect to it")
    void socialRowActionsAnswerInPlace() throws Exception {
        when(socialService.toggleLink("button01")).thenReturn(false);

        mockMvc.perform(inCard("/mgmt/social/links/button01/move").param("delta", "-1").param("lang", "bg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"links-BUTTON\"")));
        verify(socialService).moveLink("button01", -1);

        mockMvc.perform(inCard("/mgmt/social/links/button01/toggle").param("lang", "bg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Връзката е изключена.")));

        // The no-script halves: a plain POST, answered by a redirect back to the same card.
        mockMvc.perform(post("/mgmt/social/links/instagra/move").with(csrf()).param("delta", "1").param("lang", "de"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/social?lang=de#links-ICON"));
        mockMvc.perform(post("/mgmt/social/links").with(csrf())
                        .param("placement", "BUTTON").param("lang", "bg").param("tab", "en")
                        .param("title", "Абонамент").param("url", "/p/subscription"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mgmt/social?lang=en#links-BUTTON"));
    }

    @Test
    @DisplayName("the social statistics render, chart, sources, links, a deleted link and the matrix")
    void rendersSocialStats() throws Exception {
        String html = mockMvc.perform(get("/mgmt/social/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<polyline", "class=\"chart-svg\"", "chart-key clicks")
                .contains("Instagram", "Facebook", "Абонамент", "изтрита връзка", "Връзка × източник")
                // All-time comes from the day buckets.
                .contains(">42<")
                // 40 views, 7 link clicks and one article tile. The decimal separator follows the
                // request's locale.
                .contains(">40<")
                // A no-break space before the sign, so a narrow column cannot wrap the "%" alone.
                .containsPattern("CTR 20[.,]0\u00a0%")
                // Which article was clicked: its own row in the article table and in the matrix.
                .contains("Кликнати статии", "· № 3150, последни статии", "/mgmt/articles/3150");

        String filtered = mockMvc.perform(get("/mgmt/social/stats").param("source", "INSTAGRAM")
                        .param("link", "button01").param("range", "7d"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The range form carries both filters, or the next change of window would drop them.
        org.assertj.core.api.Assertions.assertThat(filtered)
                .containsPattern("name=\"source\"[^>]*value=\"INSTAGRAM\"")
                .containsPattern("name=\"link\"[^>]*value=\"button01\"")
                .contains("Връзка · Абонамент");

        // Lenient: an unknown filter is no filter, never a 400.
        mockMvc.perform(get("/mgmt/social/stats").param("source", "myspace").param("link", "<x>"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the banner form does not offer the social page, which has no ad columns")
    void bannerFormDoesNotOfferSocial() throws Exception {
        String html = mockMvc.perform(get("/mgmt/banners/5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("id=\"page-PAGE\"").doesNotContain("page-SOCIAL");
    }
}
