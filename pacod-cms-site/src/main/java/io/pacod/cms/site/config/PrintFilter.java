package io.pacod.cms.site.config;

import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.render.SitePrint;
import io.pacod.cms.site.services.CrawlerTraffic;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Lets the studio's PDF renderer read a website without being counted: a request carrying
 * {@code X-Pacod-Print} -- every request of a render, the page and each image and stylesheet --
 * with a {@link PrintTokens} token for the website its host names is marked uncounted and
 * {@link SitePrint}; one with a bad or expired token is a plain 403.
 *
 * <p>Next to {@code PreviewFilter} ({@link PreviewConfig}): after the website's binding, before
 * the crawler filter (which then skips it -- Gotenberg's Chromium calls itself HeadlessChrome and
 * would fill the crawler log) and Spring Security. Caddy removes the header on the way in; the
 * site's own port is published, which is why the header is signed.
 */
public class PrintFilter extends OncePerRequestFilter {

    private final PrintTokens tokens;

    public PrintFilter(PrintTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || UnboundPaths.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(PrintTokens.HEADER);
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        CrawlerTraffic.markUncounted(request);
        boolean valid = TenantContext.find()
                .map(tenant -> tokens.verify(token.strip(), tenant.siteId(), Instant.now())).orElse(false);
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getOutputStream().write("This print request is not valid for this website.\n"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        request.setAttribute(SitePrint.ATTRIBUTE, Boolean.TRUE);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
