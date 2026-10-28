package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.PreviewTokens;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.core.design.Theme;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.repository.ThemeRepository;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.services.SiteMetricCollector;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The studio's draft preview through the real filter chain -- the website's binding at -120, the
 * preview at -115, the crawler filter and Spring Security after it -- which no MockMvc test runs.
 * A website of its own, {@code iota}, with a published and a draft layout, a published and a
 * draft theme, and a tag manager configured.
 */
class PreviewIT extends TwoWebsitesIT {

    private static final String HOST = "iota.test";
    private static final String GTM = "GTM-PREV1EW";

    @Autowired
    LayoutRepository layouts;
    @Autowired
    ThemeRepository themes;
    @Autowired
    PreviewTokens tokens;
    @Autowired
    SiteMetricCollector collector;

    Tenant iota;

    @BeforeAll
    void iota() {
        if (directory.bySite("iota").isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "iota", "Iota Post", "en", "UTC", List.of(HOST)));
        }
        iota = directory.bySite("iota").orElseThrow();
        TenantContext.runAs(iota, () -> {
            Article article = new Article();
            article.setId(1);
            article.setTitle("iota -- the lead story");
            article.setText("<p>The body of iota.</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);

            Article draft = new Article();
            draft.setId(2);
            draft.setTitle("iota -- a draft in the works");
            draft.setText("<p>Not yet.</p>");
            draft.setStatus(ArticleStatus.DRAFT);
            draft.setDate(LocalDateTime.now());
            draft.setCounter(0);
            articles.save(draft);

            SiteSettings site = settings.findById(SiteSettings.ID).orElseGet(SiteSettings::new);
            site.setGtmId(GTM);
            settings.save(site);

            // The cookie policy the reader's consent banner links.
            io.pacod.cms.core.data.Page cookies = new io.pacod.cms.core.data.Page("cookies");
            cookies.putTitle("en", "Cookies");
            cookies.putBody("en", "<p>The cookies of iota.</p>");
            pages.save(cookies);

            Layout home = new Layout(PageType.HOME);
            home.setPublished(Map.of("main", List.of(Section.of(SectionType.RICH_TEXT, Map.of("html", "<p>Published words.</p>")))));
            home.setDraft(Map.of("main", List.of(Section.of(SectionType.RICH_TEXT, Map.of("html", "<p>Draft words.</p>")))));
            layouts.save(home);

            Theme theme = new Theme();
            theme.setPublished(Map.of("accent", "#1d4e6f"));
            theme.setDraft(Map.of("accent", "#ff0000"));
            themes.save(theme);
        });
    }

    private String token() {
        return tokens.mint("iota", null);
    }

    @Test
    @DisplayName("an unpublished article shows to the studio's preview only, and stays out of every list there too")
    void draftArticleOnlyWithToken() {
        assertThat(get(HOST, "/article/2").statusCode()).isEqualTo(404);
        HttpResponse<String> preview = get(HOST, "/article/2?_preview=" + token());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("iota -- a draft in the works");
        assertThat(get(HOST, "/latest?_preview=" + token()).body()).doesNotContain("a draft in the works");
        // Another website's token is no way in.
        assertThat(get(HOST, "/article/2?_preview=" + tokens.mint("alpha", null)).body()).doesNotContain("a draft in the works");
    }

    @Test
    @DisplayName("the draft renders only with a token, and never reaches readers through a cache")
    void draftOnlyWithToken() {
        assertThat(get(HOST, "/").body()).contains("Published words.").doesNotContain("Draft words.", "pacod-preview");

        HttpResponse<String> preview = get(HOST, "/?_preview=" + token());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("Draft words.", "name=\"pacod-preview\"", "/site-assets/preview.js")
                .doesNotContain("Published words.");

        assertThat(get(HOST, "/").body()).contains("Published words.").doesNotContain("Draft words.");
    }

    @Test
    @DisplayName("a preview may be framed by the studio, is never stored, sends no referrer and is not indexed -- its error page too")
    void headers() {
        for (HttpResponse<String> response : List.of(get(HOST, "/?_preview=" + token()),
                get(HOST, "/p/no-such-page?_preview=" + token()))) {
            assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue("frame-ancestors 'self' " + STUDIO_ORIGIN);
            assertThat(response.headers().firstValue("X-Frame-Options")).isEmpty();
            assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(response.headers().firstValue("X-Robots-Tag").orElse("")).contains("noindex");
            assertThat(response.body()).contains("name=\"pacod-preview\"");
        }
        assertThat(get(HOST, "/p/no-such-page?_preview=" + token()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a preview counts no page view and no article view; the same page without the token does")
    void notCounted() throws InterruptedException {
        offThread(collector::flush);
        long views = views();
        int counter = counter();

        assertThat(get(HOST, "/article/1?_preview=" + token()).statusCode()).isEqualTo(200);
        assertThat(get(HOST, "/?_preview=" + token()).statusCode()).isEqualTo(200);
        offThread(collector::flush);
        assertThat(views()).isEqualTo(views);
        assertThat(counter()).isEqualTo(counter);

        // Not vacuous: a reader's request moves both.
        assertThat(get(HOST, "/article/1").statusCode()).isEqualTo(200);
        offThread(collector::flush);
        assertThat(views()).isEqualTo(views + 1);
        assertThat(counter()).isEqualTo(counter + 1);
    }

    @Test
    @DisplayName("a preview tells no third party: the configured tag manager and its consent banner are left out")
    void noTracking() {
        // A reader's page carries the container only as the consent banner's data: nothing from Google.
        assertThat(get(HOST, "/").body()).contains("id=\"consent\"", "data-gtm=\"" + GTM + "\"",
                        "href=\"/p/cookies?lang=en\"", "href=\"#cookie-preferences\" hidden")
                .doesNotContain("googletagmanager");
        assertThat(get(HOST, "/?_preview=" + token()).body())
                .doesNotContain(GTM, "googletagmanager", "id=\"consent\"", "cookie-preferences");
        // Nor does the error page ask, in the website's own chrome.
        HttpResponse<String> missing = get(HOST, "/p/no-such-page");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("site-footer").doesNotContain("id=\"consent\"", "data-gtm", "googletagmanager");
    }

    @Test
    @DisplayName("another website's token, an expired one or garbage is a plain 403 -- never the published page -- and counts nothing")
    void refused() throws InterruptedException {
        offThread(collector::flush);
        long views = views();
        for (String bad : List.of(tokens.mint("alpha", null), tokens.mint("iota", null, Instant.now().minusSeconds(1)), "garbage")) {
            HttpResponse<String> response = get(HOST, "/?_preview=" + bad);
            assertThat(response.statusCode()).as(bad).isEqualTo(403);
            assertThat(response.body()).contains("expired or is for another website").doesNotContain("Published words.");
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        }
        // And iota's own token on alpha's host.
        assertThat(get(ALPHA_HOST, "/?_preview=" + token()).statusCode()).isEqualTo(403);
        offThread(collector::flush);
        assertThat(views()).isEqualTo(views);
    }

    @Test
    @DisplayName("the preview links the draft theme by its token, served no-store; readers get the published one")
    void theme() {
        String token = token();
        assertThat(get(HOST, "/?_preview=" + token).body()).contains("href=\"/theme.css?_preview=" + token + "\"");
        HttpResponse<String> draft = get(HOST, "/theme.css?_preview=" + token, "text/css");
        assertThat(draft.body()).contains("#ff0000").doesNotContain("#1d4e6f");
        assertThat(draft.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(get(HOST, "/theme.css", "text/css").body()).contains("#1d4e6f").doesNotContain("#ff0000");
    }

    @Test
    @DisplayName("a token may name a template set to try; one the installation lacks leaves the website's own")
    void templateSet() {
        assertThat(get(HOST, "/?_preview=" + tokens.mint("iota", "minimal")).body()).contains("/site-assets/minimal.css", "Draft words.");
        assertThat(get(HOST, "/").body()).doesNotContain("minimal.css");
        HttpResponse<String> unknown = get(HOST, "/?_preview=" + tokens.mint("iota", "nonesuch"));
        assertThat(unknown.statusCode()).isEqualTo(200);
        assertThat(unknown.body()).doesNotContain("minimal.css");
    }

    private long views() {
        return TenantContext.callAs(iota, () -> {
            Document bucket = siteTemplate.getCollection(SiteMetricHour.COLLECTION)
                    .find(new Document("_id", SiteMetricHour.idFor(LocalDateTime.now()))).first();
            Object views = bucket == null ? null : bucket.get("views");
            return views == null ? 0L : ((Number) views).longValue();
        });
    }

    private int counter() {
        return TenantContext.callAs(iota, () -> articles.findById(1).map(Article::getCounter).orElse(0));
    }

    private static void offThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }
}
