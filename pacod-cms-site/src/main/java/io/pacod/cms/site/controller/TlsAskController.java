package io.pacod.cms.site.controller;

import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.site.config.UnboundPaths;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether a host may have a certificate: Caddy's on-demand TLS asks
 * {@code GET /_internal/tls-ask?domain=<host>} before it requests one, and issues it only on a 200.
 *
 * <p>200 only for a host of an {@code ACTIVE} website, through {@link TenantDirectory} -- the same
 * answer the site gives a reader at that host. Anything else is a 404, so a scanner pointing names
 * at the installation cannot make it request certificates and spend the CA's rate limit.
 *
 * <p>The proxy asks on the compose network with its own Host ({@code cms-site:8443}), so the path is
 * exempt from the website binding ({@link UnboundPaths}); Caddy answers {@code /_internal/} with a
 * 404 from outside. What it reveals -- that a host is a website's -- the website itself says
 * to anyone who opens it.
 */
@RestController
public class TlsAskController {

    private static final int MAX_HOST = 253;

    private final TenantDirectory directory;

    public TlsAskController(TenantDirectory directory) {
        this.directory = directory;
    }

    @GetMapping(UnboundPaths.TLS_ASK)
    public ResponseEntity<String> ask(@RequestParam(required = false) String domain) {
        boolean known = domain != null && !domain.isBlank() && domain.length() <= MAX_HOST
                && directory.byHost(domain).isPresent();
        return ResponseEntity.status(known ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN).cacheControl(CacheControl.noStore())
                .body(known ? "yes\n" : "no\n");
    }
}
