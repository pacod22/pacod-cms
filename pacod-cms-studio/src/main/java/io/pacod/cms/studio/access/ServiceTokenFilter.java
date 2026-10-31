package io.pacod.cms.studio.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Signs a script in on {@code /internal/**}: {@code Authorization: Bearer <pidt_…>}, checked by
 * {@link ServiceTokenCheck}, the account's {@code sub} becoming the authentication's name -- which
 * is what {@code FeedSubjects} looks its bindings up by, so {@code PermissionInterceptor} decides
 * for the account exactly as for a person. No session, no cookie.
 *
 * <p>Every refusal is JSON, {@code {"error", "code"}}: 404 under a website's prefix (the API has
 * none), 401 without a usable token, 503 while the answer cannot be trusted (pacod-id off,
 * unreachable, its feed old).
 */
@Slf4j
public class ServiceTokenFilter extends OncePerRequestFilter {

    private final ObjectProvider<ServiceTokenCheck> tokens;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public ServiceTokenFilter(ObjectProvider<ServiceTokenCheck> tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Only at the root: StudioSiteFilter would present /sites/<id>/internal/... as /internal/...
        // under a context path, and Caddy's 404 for /internal/* does not cover that address.
        if (!request.getContextPath().isEmpty()) {
            refuse(response, 404, "not_found", "Not found");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            response.setHeader("WWW-Authenticate", "Bearer");
            refuse(response, 401, "unauthenticated", "A bearer token is required");
            return;
        }
        ServiceTokenCheck check = tokens.getIfAvailable();
        if (check == null) {
            refuse(response, 503, "identity_off", "pacod-id is not enabled on this studio");
            return;
        }
        Optional<String> sub;
        try {
            sub = check.subjectOf(header.substring(7).strip());
        } catch (ServiceTokenCheck.Unavailable e) {
            log.warn("Internal API: {}", e.getMessage());
            refuse(response, 503, "identity_unavailable", "pacod-id cannot be asked now; try again");
            return;
        }
        if (sub.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            refuse(response, 401, "invalid_token", "The token is not an active service token");
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(sub.get(), null, AuthorityUtils.NO_AUTHORITIES));
        contexts.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contexts.clearContext();
        }
    }

    public static void refuse(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        // Fixed strings only: nothing from the request is echoed.
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
