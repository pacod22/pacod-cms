package io.pacod.cms.site.config;

import io.pacod.cms.core.design.PreviewTokens;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.render.SitePreview;
import io.pacod.cms.site.services.CrawlerTraffic;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Opens the studio's draft preview for a request carrying {@code ?_preview=<token>}.
 *
 * <p>After {@link HostSiteResolverFilter} (-120), which binds the website the token must be for,
 * and before {@code CrawlerTrafficFilter} (-110) and Spring Security (-100): see
 * {@link PreviewConfig}. On the REQUEST dispatch it checks the token; the ERROR and ASYNC
 * dispatches re-apply what that check stored, because the error page of a preview is part of it.
 *
 * <ul>
 *   <li><b>Any {@code _preview}, valid or not, counts for nothing</b>
 *       ({@link CrawlerTraffic#markUncounted}) -- the preview reloads on every saved draft.</li>
 *   <li><b>An invalid, expired or other website's token is a plain-text 403</b>, never the
 *       published page: an editor would take that for their draft.</li>
 *   <li>A valid one is answered {@code no-store}, {@code Referrer-Policy: no-referrer} (the token
 *       is in the address) and {@code noindex}; {@link FramingHeaders} lets the studio frame it.
 *       A template set the token names, and this installation has, is bound for the request.</li>
 * </ul>
 */
public class PreviewFilter extends OncePerRequestFilter {

    private final PreviewTokens tokens;
    private final Set<String> installedSets;

    public PreviewFilter(PreviewTokens tokens, Set<String> installedSets) {
        this.tokens = tokens;
        this.installedSets = Set.copyOf(installedSets);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || UnboundPaths.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<SitePreview.Preview> preview;
        if (request.getDispatcherType() == DispatcherType.REQUEST) {
            String token = SitePreview.tokenIn(request.getQueryString());
            if (token == null) {
                chain.doFilter(request, response);
                return;
            }
            CrawlerTraffic.markUncounted(request);
            Optional<PreviewTokens.Claims> claims = TenantContext.find()
                    .flatMap(tenant -> tokens.verify(token, tenant.siteId(), Instant.now()));
            if (claims.isEmpty()) {
                refuse(response);
                return;
            }
            String set = claims.get().templateSet();
            preview = Optional.of(new SitePreview.Preview(token, set != null && installedSets.contains(set) ? set : null));
            request.setAttribute(SitePreview.ATTRIBUTE, preview.get());
        } else {
            preview = SitePreview.of(request);
        }
        if (preview.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        String set = preview.get().templateSet();
        if (set == null || TenantContext.find().isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            TenantContext.callChecked(TenantContext.current().withTemplateSet(set), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.getOutputStream().write(("This preview has expired or is for another website.\n"
                + "Open it again from the studio.\n").getBytes(StandardCharsets.UTF_8));
    }
}
