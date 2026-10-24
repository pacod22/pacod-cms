package io.pacod.cms.site.it;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.LanguageRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.core.repository.TagRepository;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.seed.LanguageBootstrap;
import io.pacod.cms.core.seed.PageBootstrap;
import io.pacod.cms.core.seed.SiteBootstrap;
import io.pacod.cms.core.seed.SiteSeedStep;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo content as a reader meets it, against a real MongoDB: three new English websites -- the
 * journal on the editorial and the minimal set, the space travels on the magazine set -- provisioned
 * with the seed steps the studio runs and then given the demo content. Every page type answers with something in it -- a list that rendered empty at 200 is
 * what this exists to catch -- the translations are served, and the pictures are files the site
 * serves.
 *
 * <p>The installer is built by hand: the site scans neither {@code core.seed} nor anything that
 * writes content, which is the point of the split.
 */
class DemoSiteRenderIT extends TwoWebsitesIT {

    private static final String EDITORIAL = "omicron.test";
    private static final String MINIMAL = "sigma.test";
    private static final String SPACE = "tau.test";

    @Autowired
    CategoryRepository categories;
    @Autowired
    AuthorRepository authors;
    @Autowired
    ImageMediaRepository images;
    @Autowired
    TagRepository tags;
    @Autowired
    MenuRepository menus;
    @Autowired
    SocialPageRepository socialPages;
    @Autowired
    LanguageRepository languages;
    @Autowired
    ImageService imageService;
    @Autowired
    io.pacod.cms.core.repository.ThemeRepository themes;
    @Autowired
    io.pacod.cms.core.repository.LayoutRepository layouts;
    @Autowired
    io.pacod.cms.core.repository.JournalIssueRepository issues;

    /** Ids of their own: the context, and so every website in it, is shared by every IT class. */
    @BeforeAll
    void demoWebsites() {
        demoWebsite("omicron", "Omicron Journal", EDITORIAL, null);
        demoWebsite("sigma", "Sigma Notes", MINIMAL, "minimal");
        demoWebsite("tau", "Tau Travels", SPACE, DemoContent.Demo.SPACE.templateSet(), DemoContent.Demo.SPACE);
    }

    private void demoWebsite(String id, String name, String host, String templateSet) {
        demoWebsite(id, name, host, templateSet, DemoContent.Demo.JOURNAL);
    }

    private void demoWebsite(String id, String name, String host, String templateSet, DemoContent.Demo kind) {
        if (directory.bySite(id).isPresent()) {
            return;
        }
        provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", id, name, "en", "UTC", List.of(host)));
        if (templateSet != null) {
            controlPlane.updateFirst(Query.query(Criteria.where("_id").is(id)),
                    Update.update("templateSet", templateSet), Website.class);
            directory.evictAll();
        }
        Tenant tenant = directory.bySite(id).orElseThrow();
        JsonMapper mapper = JsonMapper.builder().build();
        SiteSeedStep.Website about = new SiteSeedStep.Website(id, name, "en");
        DemoContent demo = new DemoContent(articles, categories, authors, images, tags, menus, socialPages,
                imageService, mapper);
        demo.useSiteAndTheme(settings, themes);
        demo.useStandingContent(pages, languages, layouts, issues, new io.pacod.cms.core.design.TemplateSetCatalog());
        TenantContext.runAs(tenant, () -> {
            // What the studio's provisioning writes before anyone can ask for demo content.
            new LanguageBootstrap(languages, mapper).seed(about);
            new PageBootstrap(pages, mapper).seed(about);
            new SiteBootstrap(settings, menus, mapper).seed(about);
            DemoContent.Result result = demo.install(kind, about);
            assertThat(result.articles()).isEqualTo(demo.seed(kind).getArticles().size());
            assertThat(result.menuGroup()).isTrue();
        });
    }

    private String page(String host, String path) {
        HttpResponse<String> response = get(host, path);
        assertThat(response.statusCode()).as(host + path).isEqualTo(200);
        return response.body();
    }

    @Test
    @DisplayName("on both template sets every page type answers with the demo's content: home, latest, a section, an article, a page, search")
    void listsAreFilled() {
        for (String host : List.of(EDITORIAL, MINIMAL)) {
            // The newest is the highest id, the hero; the cards below it are the next ones.
            assertThat(page(host, "/")).as(host + " /")
                    .contains("Why your phone slows down after two years", "/assets/img/articles/demo-phone-battery.jpg",
                            "Forty-eight hours in Port Alder", "How much does a cloud weigh?");
            // The latest page is the newest article itself.
            assertThat(page(host, "/latest")).as(host + " /latest")
                    .contains("Why your phone slows down after two years", "Mara");
            assertThat(page(host, "/list/2")).as(host + " /list/2")
                    .contains("Travel", "Forty-eight hours in Port Alder", "Night trains are back")
                    .doesNotContain("How much does a cloud weigh?");
            // The other page types: an article, a standing page, search.
            assertThat(page(host, "/article/19")).as(host + " /article/19")
                    .contains("Forty-eight hours in Port Alder", "demo-port-alder-3.jpg");
            assertThat(page(host, "/p/about")).as(host + " /p/about").contains("About us");
            assertThat(page(host, "/search?q=cloud")).as(host + " /search")
                    .contains("How much does a cloud weigh?", "Where the cloud lives")
                    .doesNotContain("Night trains are back");
        }
        // The editorial set's aside ranks the most read.
        assertThat(page(EDITORIAL, "/")).contains("Where the cloud lives");
    }

    @Test
    @DisplayName("an article renders with its gallery, its byline and section, and its translations")
    void articlesAndTranslations() {
        String article = page(EDITORIAL, "/article/19");
        assertThat(article).contains("Forty-eight hours in Port Alder", "Teo", "Varga",
                "demo-port-alder.jpg", "demo-port-alder-2.jpg", "demo-port-alder-3.jpg",
                "Abstract illustration: layered blue waves");

        String german = page(EDITORIAL, "/de/article/19");
        assertThat(german).contains("lang=\"de\"", "Achtundvierzig Stunden in Port Alder", "Abstrakte Illustration");
        assertThat(page(MINIMAL, "/es/article/17")).contains("¿Cuánto pesa una nube?");
        // Not translated: the English original, in German chrome.
        assertThat(page(EDITORIAL, "/de/article/15")).contains("A plain-language guide to password managers");
    }

    @Test
    @DisplayName("the pictures are files the site serves, at the size the upload rules ask for")
    void picturesAreServed() throws Exception {
        HttpResponse<String> picture = get(EDITORIAL, "/assets/img/articles/demo-port-alder.jpg", "image/*");
        assertThat(picture.statusCode()).isEqualTo(200);
        assertThat(picture.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/jpeg");
        // Another website's picture is not this one's.
        assertThat(get(ALPHA_HOST, "/assets/img/articles/demo-port-alder.jpg", "image/*").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the main menu links the demo's sections, the link page is published and the sitemap lists the articles")
    void menuLinkPageAndSitemap() {
        assertThat(page(EDITORIAL, "/")).contains("Sections", "/list/1", "/list/6", "Opinion");
        assertThat(page(EDITORIAL, "/social")).contains("Omicron Journal", "/social/go/", "About this journal");
        assertThat(get(EDITORIAL, "/sitemap.xml", "application/xml").body())
                .contains("<loc>http://" + EDITORIAL + "/article/20</loc>", "<loc>http://" + EDITORIAL + "/list/6</loc>");
    }

    @Test
    @DisplayName("the space travels on the magazine set: a cover story, long articles in their blocks, photographs with credits, pages, issues")
    void spaceTravels() {
        assertThat(page(SPACE, "/")).as("home")
                .contains("/site-assets/magazine.css", "/theme.css?v=", "mz-cover",
                        // The newest article is the cover story, its photograph full width.
                        "Why the stars do not twinkle in space", "/assets/img/articles/demo-lantern-witch.jpg",
                        "Forty-eight hours on Halcyon Station", "Latest from the Reach", "Plan your crossing",
                        "Where our pictures come from", "Destinations")
                // Written in English alone: the website's languages were narrowed, the switcher is gone.
                .doesNotContain("lang-menu");
        String article = page(SPACE, "/article/8");
        assertThat(article).as("an article")
                .contains("Veyra: walking among the ice geysers", "class=\"body-lead\"", "class=\"body-pullquote\"",
                        "class=\"body-facts\"", "Liora Venn", "min read", "About the author",
                        // The lead photograph and its credit.
                        "demo-veyra-plumes.jpg", "Image: NASA/JPL/Space Science Institute.");
        // The picture placed inside the body is in it once, and not again in the gallery.
        assertThat(article.split("demo-veyra-fractures.jpg", -1)).hasSize(2);
        assertThat(page(SPACE, "/list/3")).contains("Journeys", "Mirren: forty days to the edge of the Reach");
        assertThat(page(SPACE, "/p/about")).contains("About Tau Travels", "The photographs in Tau Travels are real");
        assertThat(page(SPACE, "/p/aurelian-lines")).contains("Aurelian Lines", "Advertisement.");
        assertThat(page(SPACE, "/issues")).contains("The outer worlds", "Red world, grey moon");
        assertThat(get(SPACE, "/assets/img/articles/demo-tessaly-eclipse.jpg", "image/*").statusCode()).isEqualTo(200);
        assertThat(page(SPACE, "/social")).contains("Tau Travels", "Forty-eight hours on Halcyon Station");

        Tenant tau = directory.bySite("tau").orElseThrow();
        TenantContext.runAs(tau, () -> {
            assertThat(settings.findById(io.pacod.cms.core.data.SiteSettings.ID).orElseThrow().getTaglines())
                    .extracting(io.pacod.cms.core.data.TextItem::getText).contains("Travel writing from across the Reach");
            assertThat(themes.findById(io.pacod.cms.core.design.Theme.ID).orElseThrow().getPublished())
                    .containsEntry("accent", "#1b2a55").containsEntry("font-display", "fraunces");
            assertThat(layouts.findById("HOME")).isPresent();
            assertThat(languages.findAll()).extracting(io.pacod.cms.core.data.Language::getCode).containsExactly("en");
        });
        // The journal's websites got no theme: theirs is the template's own.
        TenantContext.runAs(directory.bySite("omicron").orElseThrow(),
                () -> assertThat(themes.existsById(io.pacod.cms.core.design.Theme.ID)).isFalse());
    }
}
