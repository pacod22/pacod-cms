package io.pacod.cms.studio.site;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds the website a studio request is for, and makes {@code /sites/<id>} look like the context
 * path.
 *
 * <p>{@code /sites/demo/mgmt/articles} reaches the application as {@code /mgmt/articles} under a
 * context path of {@code /sites/demo}, with website {@code demo} bound in {@link TenantContext} for
 * the whole request. The context path is what Spring MVC strips before matching, what Spring
 * Security's matchers compare against, what Thymeleaf puts in front of every {@code @{/…}} and what
 * a {@code redirect:} is relative to -- so the console's mappings, matchers, links and redirects
 * need no rewrite to be per-website. See {@link StudioPaths} for the platform screens, which
 * answer with or without a prefix.
 *
 * <h2>What else it has to take care of</h2>
 * <ul>
 *   <li><b>The error dispatch.</b> The container dispatches an error to {@code /error} with the
 *       original request, not with the wrapper, so this filter runs for {@code ERROR} as well and
 *       re-derives the website from {@code jakarta.servlet.error.request_uri}. Without that, an
 *       error page inside a website would render its links without the prefix.</li>
 *   <li><b>Cookie paths.</b> Code that defaults a cookie's path to the context path -- the
 *       session cookie, a CSRF repository left to its default -- would file it under
 *       {@code /sites/<id>}, and a cookie set on one website would not reach the next. Such paths
 *       are written back to the real context path.</li>
 *   <li><b>Image previews.</b> An article body references its images as {@code /assets/img/…}
 *       with no prefix, which is right for the public site and leaves the editor, rendering the same
 *       body inside the studio, with no website in the address. For those addresses only, the
 *       website comes from a same-origin {@code Referer} naming one.</li>
 * </ul>
 *
 * <p>An id that names no website is a 404, never a default website: a default is how one tenant's
 * screen ends up showing another's data.
 */
public class StudioSiteFilter extends HttpFilter {

    /** Request attribute: the real context path, for links that must not carry a website prefix. */
    public static final String STUDIO_ROOT = "studioRoot";

    private static final Pattern COOKIE_PATH = Pattern.compile("(?i)(;\\s*Path=)([^;]*)");

    private final TenantDirectory directory;

    public StudioSiteFilter(TenantDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI().substring(contextPath.length());
        // The studio's own root, without any website prefix: what the platform links are built on.
        request.setAttribute(STUDIO_ROOT, contextPath);

        if (request.getDispatcherType() == DispatcherType.ERROR) {
            Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            StudioPaths.SitePath site = original instanceof String uri && uri.startsWith(contextPath)
                    ? StudioPaths.parse(uri.substring(contextPath.length())) : null;
            Optional<Tenant> tenant = site == null ? Optional.empty() : directory.bySite(site.siteId());
            if (tenant.isPresent()) {
                String virtual = contextPath + "/sites/" + site.siteId();
                serve(tenant.get(), new SiteRequest(request, virtual, virtual + path),
                        new SiteResponse(response, virtual, contextPath), chain);
            } else {
                chain.doFilter(request, response);
            }
            return;
        }

        StudioPaths.SitePath site = StudioPaths.parse(path);
        if (site != null) {
            Optional<Tenant> tenant = directory.bySite(site.siteId());
            if (tenant.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            String virtual = contextPath + "/sites/" + site.siteId();
            if (site.rest().isEmpty() || site.rest().equals("/")) {
                response.sendRedirect(virtual + "/mgmt/dashboard");
                return;
            }
            serve(tenant.get(), new SiteRequest(request, virtual, request.getRequestURI()),
                    new SiteResponse(response, virtual, contextPath), chain);
            return;
        }

        if (StudioPaths.needsSite(path)) {
            response.sendRedirect(contextPath + StudioPaths.SITES_SCREEN);
            return;
        }

        if (StudioMediaController.isPreviewPath(path)) {
            Optional<Tenant> tenant = refererSite(request).flatMap(directory::bySite);
            if (tenant.isPresent()) {
                serve(tenant.get(), request, response, chain);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static void serve(Tenant tenant, HttpServletRequest request, HttpServletResponse response,
                              FilterChain chain) throws IOException, ServletException {
        try {
            TenantContext.callChecked(tenant, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    /**
     * The website named by the page that asked for this image, when that page is the studio's own.
     * Only a same-origin {@code Referer} counts: another site's page cannot pick which website's
     * files the studio reads.
     */
    static Optional<String> refererSite(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(referer);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(request.getServerName())
                || effectivePort(uri) != request.getServerPort()
                || uri.getRawPath() == null) {
            return Optional.empty();
        }
        String path = uri.getRawPath();
        String contextPath = request.getContextPath();
        if (!path.startsWith(contextPath)) {
            return Optional.empty();
        }
        StudioPaths.SitePath site = StudioPaths.parse(path.substring(contextPath.length()));
        return site == null ? Optional.empty() : Optional.of(site.siteId());
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** The request as the application sees it: {@code /sites/<id>} is the context path. */
    static final class SiteRequest extends HttpServletRequestWrapper {

        private final String contextPath;
        private final String requestUri;

        SiteRequest(HttpServletRequest request, String contextPath, String requestUri) {
            super(request);
            this.contextPath = contextPath;
            this.requestUri = requestUri;
        }

        @Override
        public String getContextPath() {
            return contextPath;
        }

        @Override
        public String getRequestURI() {
            return requestUri;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            String scheme = getScheme();
            int port = getServerPort();
            url.append(scheme).append("://").append(getServerName());
            if (!("http".equals(scheme) && port == 80) && !("https".equals(scheme) && port == 443)) {
                url.append(':').append(port);
            }
            return url.append(requestUri);
        }

        /** The DispatcherServlet is mapped to {@code /}, so the servlet path is the whole path within it. */
        @Override
        public String getServletPath() {
            return requestUri.substring(contextPath.length());
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }

    /** Puts a cookie filed under the virtual context path back under the real one. */
    static final class SiteResponse extends HttpServletResponseWrapper {

        private final String virtualPath;
        private final String realPath;

        SiteResponse(HttpServletResponse response, String virtualPath, String realPath) {
            super(response);
            this.virtualPath = virtualPath;
            this.realPath = realPath.isEmpty() ? "/" : realPath;
        }

        @Override
        public void addCookie(Cookie cookie) {
            if (cookie.getPath() != null && isVirtual(cookie.getPath())) {
                cookie.setPath(realPath);
            }
            super.addCookie(cookie);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, rewrite(name, value));
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, rewrite(name, value));
        }

        private String rewrite(String name, String value) {
            if (value == null || !HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                return value;
            }
            Matcher m = COOKIE_PATH.matcher(value);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String cookiePath = m.group(2).trim();
                m.appendReplacement(out, Matcher.quoteReplacement(
                        m.group(1) + (isVirtual(cookiePath) ? realPath : m.group(2))));
            }
            m.appendTail(out);
            return out.toString();
        }

        private boolean isVirtual(String path) {
            return path.equals(virtualPath) || path.startsWith(virtualPath + "/");
        }
    }
}
