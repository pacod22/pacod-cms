package io.pacod.cms.site.config;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Binds the website a request is for -- found by its Host -- for everything that runs while it is
 * handled: controllers, repositories, caches, the counters, the error page.
 *
 * <p>Three things about it are deliberate:
 * <ul>
 *   <li><b>An unknown host is a 404, never a default website.</b> Answering an unknown host with
 *       some other website's pages is how one website's content gets indexed under another's
 *       name. The answer is written here, as plain text, rather than through {@code sendError}:
 *       the error page needs a website, and there is none.</li>
 *   <li><b>It runs on ERROR and ASYNC dispatches as well as REQUEST.</b> The container renders an
 *       error page after this filter's scope for the original request has ended, so the error
 *       dispatch has to bind the website again -- otherwise a 404 on a real page could not render
 *       that website's chrome. {@link OncePerRequestFilter} skips both by default.</li>
 *   <li><b>It is ordered ahead of Spring Security and the crawler filter</b>, both of which can
 *       write responses -- and the crawler filter counts into the website's database.</li>
 * </ul>
 *
 * <p>The Host is read after {@code ForwardedHeaderFilter}, so behind the proxy it is the address
 * the reader typed.
 */
public class HostSiteResolverFilter extends OncePerRequestFilter {

    private final TenantDirectory directory;

    public HostSiteResolverFilter(TenantDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** Requests that are about no website ({@link UnboundPaths}). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return UnboundPaths.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Tenant> tenant = directory.byHost(request.getServerName());
        if (tenant.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getOutputStream().write("No website answers at this address.\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            TenantContext.callChecked(tenant.get(), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
