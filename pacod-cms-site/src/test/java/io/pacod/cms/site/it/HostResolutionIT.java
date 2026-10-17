package io.pacod.cms.site.it;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.tenant.SiteProvisioner;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The request's host picks the website, on every dispatch, and an unknown one gets none. */
class HostResolutionIT extends TwoWebsitesIT {

    @Test
    @DisplayName("each host renders its own website")
    void eachHostItsOwnWebsite() {
        HttpResponse<String> a = get(ALPHA_HOST, "/article/1");
        HttpResponse<String> b = get(BETA_HOST, "/article/1");

        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.body()).contains("Alpha Daily -- first story").doesNotContain("Beta Weekly");
        assertThat(b.statusCode()).isEqualTo(200);
        assertThat(b.body()).contains("Beta Weekly -- first story").doesNotContain("Alpha Daily");
    }

    @Test
    @DisplayName("a port and a different case still name the same website")
    void hostIsNormalised() {
        assertThat(get("ALPHA.test:" + port, "/article/1").body()).contains("Alpha Daily -- first story");
    }

    @Test
    @DisplayName("a host no website claims is a 404, never a default website")
    void unknownHostIs404() {
        HttpResponse<String> response = get("nobody.test", "/article/1");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("Alpha Daily", "Beta Weekly");
    }

    @Test
    @DisplayName("a website's 404 page is rendered in that website's chrome")
    void errorPageKeepsTheWebsite() {
        // The error page is a second dispatch, after the first has unwound: the website is
        // resolved again for it, or the page would render with no site at all.
        HttpResponse<String> response = get(BETA_HOST, "/article/999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(response.body()).contains("Beta Weekly").doesNotContain("Alpha Daily");
    }

    @Test
    @DisplayName("the TLS proxy may have a certificate for an active website's host only, asking with its own Host")
    void tlsAsk() {
        // Caddy asks on the compose network as http://site:8443, a host no website claims.
        assertThat(get("site:8443", "/_internal/tls-ask?domain=" + ALPHA_HOST, "text/plain").statusCode()).isEqualTo(200);
        assertThat(get("site:8443", "/_internal/tls-ask?domain=ALPHA.test", "text/plain").statusCode()).isEqualTo(200);
        assertThat(get("site:8443", "/_internal/tls-ask?domain=nobody.test", "text/plain").statusCode()).isEqualTo(404);
        assertThat(get("site:8443", "/_internal/tls-ask", "text/plain").statusCode()).isEqualTo(404);

        // A suspended website answers nobody, so it gets no certificate either.
        if (directory.bySite("kappa").isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", "IT", "kappa", "Kappa", "en", "UTC",
                    List.of("kappa.test")));
        }
        controlPlane.updateFirst(Query.query(Criteria.where("_id").is("kappa")),
                Update.update("status", WebsiteStatus.SUSPENDED), Website.class);
        directory.evictAll();
        assertThat(get("site:8443", "/_internal/tls-ask?domain=kappa.test", "text/plain").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the health check answers on any host, for the container's probe")
    void healthNeedsNoWebsite() {
        assertThat(get("nobody.test", "/web/v1/health", "application/json").statusCode()).isEqualTo(200);
    }
}
