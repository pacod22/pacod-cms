package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layouts through the real dispatcher and view resolution -- where Thymeleaf 3.1's restricted
 * mode applies to fragments chosen by data: a website with none renders its set's default; one
 * with a published layout renders that, its draft never.
 */
class LayoutRenderIT extends TwoWebsitesIT {

    @Autowired
    LayoutRepository layouts;

    private Tenant website(String id, String host) {
        if (directory.bySite(id).isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", id, id + " Times", "en", "UTC", List.of(host)));
        }
        Tenant tenant = directory.bySite(id).orElseThrow();
        TenantContext.runAs(tenant, () -> {
            Article article = new Article();
            article.setId(1);
            article.setTitle(id + " -- the lead story");
            article.setText("<p>The body of " + id + ".</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);
        });
        return tenant;
    }

    @Test
    @DisplayName("no layout: the set's default -- the newest article leads the home page")
    void defaultLayout() {
        website("zeta", "zeta.test");
        String home = get("zeta.test", "/").body();
        assertThat(home).contains("zeta -- the lead story", "class=\"lead\"");
    }

    @Test
    @DisplayName("a published layout renders, around the page's own content; the draft does not")
    void publishedLayout() {
        Tenant eta = website("eta", "eta.test");
        TenantContext.runAs(eta, () -> {
            Layout home = new Layout(PageType.HOME);
            home.setPublished(Map.of(
                    "main", List.of(Section.of(SectionType.RICH_TEXT, Map.of("heading", "From the layout",
                            "html", "<p>Composed by its owner.</p>"))),
                    "wide", List.of(Section.of(SectionType.CALL_TO_ACTION, Map.of("heading", "Subscribe",
                            "label", "Sign up", "url", "/p/contact")))));
            home.setDraft(Map.of("main", List.of(Section.of(SectionType.RICH_TEXT, Map.of("html", "<p>Not yet.</p>")))));
            layouts.save(home);

            Layout article = new Layout(PageType.ARTICLE);
            article.setPublished(Map.of("main", List.of(
                    Section.of(SectionType.CALL_TO_ACTION, Map.of("heading", "Before the story", "label", "Go", "url", "/")),
                    new Section("p", "primary", Map.of()))));
            layouts.save(article);
        });

        String home = get("eta.test", "/").body();
        assertThat(home).contains("From the layout", "<p>Composed by its owner.</p>", "region-wide", ">Sign up<")
                .doesNotContain("Not yet.", "class=\"lead\"");

        String story = get("eta.test", "/article/1").body();
        assertThat(story).contains("Before the story", "The body of eta.");
        assertThat(story.indexOf("Before the story")).isLessThan(story.indexOf("The body of eta."));
    }

    @Autowired
    io.pacod.cms.core.repository.ThemeRepository themes;

    @Test
    @DisplayName("a website on minimal renders minimal, its published theme linked and served; its draft never")
    void minimalWithTheme() {
        Tenant theta = website("theta", "theta.test");
        controlPlane.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is("theta")),
                org.springframework.data.mongodb.core.query.Update.update("templateSet", "minimal"),
                io.pacod.cms.core.control.data.Website.class);
        directory.evictAll();
        theta = directory.bySite("theta").orElseThrow();
        TenantContext.runAs(theta, () -> {
            // A second story, for the list under the lead (a list with nothing in it renders nothing).
            Article second = new Article();
            second.setId(2);
            second.setTitle("theta -- the second story");
            second.setText("<p>More.</p>");
            second.setStatus(ArticleStatus.PUBLISHED);
            second.setDate(LocalDateTime.now().minusDays(2));
            second.setCounter(0);
            articles.save(second);

            io.pacod.cms.core.design.Theme theme = new io.pacod.cms.core.design.Theme();
            theme.setPublished(Map.of("accent", "#1d4e6f", "font-display", "georgia"));
            theme.setDraft(Map.of("accent", "#ff0000"));
            theme.setPublishedCss(".masthead { letter-spacing: 1px; }");
            themes.save(theme);
        });

        String home = get("theta.test", "/").body();
        assertThat(home).contains("/site-assets/minimal.css", "plain-list", "theta -- the lead story", "theta -- the second story")
                .doesNotContain("layout__aside");
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("/theme\\.css\\?v=([0-9a-f]+)").matcher(home);
        assertThat(link.find()).isTrue();

        java.net.http.HttpResponse<String> css = get("theta.test", "/theme.css?v=" + link.group(1), "text/css");
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.headers().firstValue("Content-Type").orElse("")).startsWith("text/css");
        assertThat(css.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(css.headers().firstValue("Cache-Control").orElse("")).contains("immutable");
        assertThat(css.body()).contains("--accent: #1d4e6f;", "Georgia", ".masthead { letter-spacing: 1px; }")
                .doesNotContain("#ff0000");

        // Another website has no theme, and is not served this one.
        assertThat(get(ALPHA_HOST, "/").body()).doesNotContain("/theme.css");
        assertThat(get(ALPHA_HOST, "/theme.css", "text/css").body()).doesNotContain("#1d4e6f");
    }

    @Test
    @DisplayName("a page section: the page's body in the reader's language, full width below magazine's columns; a missing page, nothing")
    void pageSection() {
        website("iota", "iota.test");
        controlPlane.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is("iota")),
                org.springframework.data.mongodb.core.query.Update.update("templateSet", "magazine"),
                io.pacod.cms.core.control.data.Website.class);
        directory.evictAll();
        Tenant iota = directory.bySite("iota").orElseThrow();
        TenantContext.runAs(iota, () -> {
            io.pacod.cms.core.data.Page platform = new io.pacod.cms.core.data.Page("platform");
            platform.putTitle("en", "One platform");
            platform.putBody("en", "<p class=\"body-lead\">Four languages, one layout.</p>");
            platform.putBody("de", "<p class=\"body-lead\">Vier Sprachen, ein Layout.</p>");
            pages.save(platform);

            Layout home = new Layout(PageType.HOME);
            home.setPublished(Map.of(
                    "wide", List.of(Section.of(SectionType.PAGE, Map.of("page", "platform"))),
                    "aside", List.of(Section.of(SectionType.PAGE, Map.of("page", "gone")))));
            layouts.save(home);
        });

        String english = get("iota.test", "/").body();
        assertThat(english).containsSubsequence("class=\"layout__aside\"", "class=\"mz-wide\"",
                        "mz-section--page\" lang=\"en\"", "<div class=\"article-body\"><p class=\"body-lead\">Four languages, one layout.</p>")
                .doesNotContain("One platform", "Vier Sprachen");
        assertThat(get("iota.test", "/?lang=de").body()).contains("mz-section--page\" lang=\"de\"", "Vier Sprachen, ein Layout.")
                .doesNotContain("Four languages");
        // No French: English, as /p/platform would serve it.
        assertThat(get("iota.test", "/?lang=fr").body()).contains("Four languages, one layout.");
        assertThat(get("iota.test", "/").statusCode()).isEqualTo(200);
    }
}
