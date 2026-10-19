package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.IssueStatus;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.repository.JournalIssueRepository;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Journal issues against a real MongoDB, on a website of their own ({@code nu}): only a published
 * issue reaches {@code /issues}, its page and the sitemap; the print front page answers a print
 * token alone and shows drafts there; the print view takes the scientific format.
 */
class JournalIssuesIT extends TwoWebsitesIT {

    private static final String HOST = "nu.test";

    @Autowired
    JournalIssueRepository issues;

    @Autowired
    PrintTokens tokens;

    Tenant nu;

    @BeforeAll
    void nu() {
        if (directory.bySite("nu").isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "nu", "Nu Review", "en", "UTC", List.of(HOST)));
        }
        nu = directory.bySite("nu").orElseThrow();
        TenantContext.runAs(nu, () -> {
            Article article = new Article();
            article.setId(1);
            article.setTitle("Nu printed paper");
            article.setText("<h2>Method</h2><p>The body of nu.</p>");
            article.setDescription("What nu found.");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            articles.save(article);
            issues.save(issue(1, 1, IssueStatus.PUBLISHED, "Nu autumn issue"));
            issues.save(issue(2, 2, IssueStatus.DRAFT, "Nu secret draft issue"));
        });
    }

    private static JournalIssue issue(int id, int number, IssueStatus status, String title) {
        JournalIssue issue = new JournalIssue();
        issue.setId(id);
        issue.setVolume(1);
        issue.setNumber(number);
        issue.setYear(2026);
        issue.setMonth(10);
        issue.setStatus(status);
        issue.setTitles(List.of(new io.pacod.cms.core.data.TextItem(title, "en")));
        issue.setArticleIds(List.of(1));
        return issue;
    }

    private HttpResponse<String> print(String path, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-Forwarded-Host", HOST).header("Accept", "text/html");
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

    @Test
    @DisplayName("a reader sees the published issue on /issues, its page and the sitemap; never the draft")
    void onlyPublished() {
        HttpResponse<String> archive = get(HOST, "/issues");
        assertThat(archive.statusCode()).isEqualTo(200);
        assertThat(archive.body()).contains("Nu autumn issue", "/issues/1").doesNotContain("Nu secret draft issue");
        HttpResponse<String> one = get(HOST, "/issues/1");
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(one.body()).contains("Nu printed paper", "/article/1");
        assertThat(get(HOST, "/issues/2").statusCode()).isEqualTo(404);
        assertThat(get(HOST, "/sitemap.xml").body()).contains("/issues/1<").doesNotContain("/issues/2<");
        assertThat(get(HOST, "/robots.txt").body()).contains("Disallow: /issue-print/");
        // Another website lists none of nu's issues.
        assertThat(get(ALPHA_HOST, "/issues").body()).doesNotContain("Nu autumn issue");
    }

    @Test
    @DisplayName("the print front answers a valid print token only, drafts included; the print view takes the scientific format")
    void printFront() {
        assertThat(print("/issue-print/2/front?lang=en&ids=1&pages=3", null).statusCode()).isEqualTo(404);
        assertThat(print("/issue-print/2/front?lang=en&ids=1&pages=3", "garbage").statusCode()).isEqualTo(403);
        HttpResponse<String> front = print("/issue-print/2/front?lang=en&ids=1&pages=3", tokens.mint("nu"));
        assertThat(front.statusCode()).isEqualTo(200);
        assertThat(front.body()).contains("Nu secret draft issue", "Nu printed paper", "issue-toc__page");
        assertThat(print("/issue-print/2/front?lang=en&ids=1,1&pages=3", tokens.mint("nu")).statusCode()).isEqualTo(404);

        HttpResponse<String> scientific = print("/article-print/1?lang=en&format=scientific", tokens.mint("nu"));
        assertThat(scientific.statusCode()).isEqualTo(200);
        assertThat(scientific.body()).contains("sci-body", "What nu found.");
    }
}
