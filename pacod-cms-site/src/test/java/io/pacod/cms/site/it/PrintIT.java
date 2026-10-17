package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF renderer's way in, as Gotenberg's Chromium takes it: the site's own port, the website
 * named by {@code X-Forwarded-Host}, a {@code X-Pacod-Print} token, a HeadlessChrome User-Agent.
 * A website of its own, {@code mu}, so its view counter is nobody else's.
 */
class PrintIT extends TwoWebsitesIT {

    private static final String HOST = "mu.test";
    private static final String CHROMIUM = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/140.0.0.0 Safari/537.36";

    @Autowired
    PrintTokens tokens;

    Tenant mu;

    @BeforeAll
    void mu() {
        if (directory.bySite("mu").isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "mu", "Mu Herald", "en", "UTC", List.of(HOST)));
        }
        mu = directory.bySite("mu").orElseThrow();
        TenantContext.runAs(mu, () -> {
            Article article = new Article();
            article.setId(1);
            article.setTitle("mu -- the printed story");
            article.setText("<p>The body of mu.</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);
        });
    }

    /** As Gotenberg sends it: to the site's own address, the website in X-Forwarded-Host. */
    private HttpResponse<String> render(String path, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-Forwarded-Host", HOST)
                    .header("User-Agent", CHROMIUM)
                    .header("Accept", "text/html");
            if (token != null) {
                request.header(PrintTokens.HEADER, token);
            }
            return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int counter() {
        return TenantContext.callAs(mu, () -> articles.findById(1).map(Article::getCounter).orElse(0));
    }

    @Test
    @DisplayName("a render reads the print view of the website X-Forwarded-Host names, uncounted and unstored")
    void renderIsUncounted() {
        int before = counter();
        HttpResponse<String> page = render("/article-print/1?lang=en", tokens.mint("mu"));
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("mu -- the printed story", "The body of mu.");
        assertThat(page.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // Its stylesheet, as the render's next request: also uncounted, also allowed.
        assertThat(render("/site-assets/site.css", tokens.mint("mu")).statusCode()).isEqualTo(200);
        assertThat(counter()).isEqualTo(before);

        // Not vacuous: a reader's view of the same page counts. (Gotenberg's own agent without a
        // token would not: HeadlessChrome is a crawler to the site.)
        assertThat(get(HOST, "/article-print/1?lang=en").statusCode()).isEqualTo(200);
        assertThat(counter()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("an expired token, another website's, or garbage is a plain 403")
    void badTokensRefused() {
        for (String bad : List.of(tokens.mint("mu", Instant.now().minusSeconds(1)), tokens.mint("alpha"), "garbage")) {
            HttpResponse<String> page = render("/article-print/1", bad);
            assertThat(page.statusCode()).as(bad).isEqualTo(403);
            assertThat(page.body()).doesNotContain("mu -- the printed story");
        }
    }
}
