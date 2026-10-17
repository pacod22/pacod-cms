package io.pacod.cms.studio.access;

import io.pacod.cms.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Checks {@link StudioPermission} before a website screen's handler runs.
 *
 * <p>Fails closed. Inside a website, a handler that carries neither {@link StudioPermission} nor
 * {@link PlatformScreen} is refused and logged -- the default for a forgotten annotation must be
 * a 403 an operator reports, not a screen that quietly answers everybody. Outside a website (the
 * platform screens) only {@link PlatformScreen} handlers are reached at all: the website filter
 * sends every other un-prefixed console address to the website list first.
 *
 * <p>An organisation's screens ({@link OrganisationPermission}) and the installation's
 * ({@link PlatformPermission}) are checked first, wherever they are reached from: both belong to no
 * website, and a {@code PlatformScreen} on the same class would otherwise wave them through.
 */
@Slf4j
public class PermissionInterceptor implements HandlerInterceptor {

    private final StudioAccess access;

    public PermissionInterceptor(StudioAccess access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (!method.getBeanType().getName().startsWith("io.pacod.cms.studio.")) {
            // Spring's own handlers -- the error controller among them.
            return true;
        }
        OrganisationPermission organisation = find(method, OrganisationPermission.class);
        if (organisation != null) {
            String orgId = pathVariable(request, OrganisationPermission.VARIABLE);
            if (orgId == null || orgId.isBlank()) {
                log.error("Refusing {} {}: {} has no {{{}}} to check against", request.getMethod(),
                        request.getRequestURI(), method.getShortLogMessage(), OrganisationPermission.VARIABLE);
                throw new StudioForbiddenException("An organisation screen with no organisation");
            }
            check(request, organisation.read(), organisation.write(), StudioAccess.pathOfOrganisation(orgId));
            return true;
        }
        PlatformPermission platform = find(method, PlatformPermission.class);
        if (platform != null) {
            check(request, platform.read(), platform.write(), StudioAccess.PLATFORM);
            return true;
        }

        // The method's own annotation decides; the class's only when the method has none.
        StudioPermission permission = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), StudioPermission.class);
        if (permission == null) {
            if (AnnotatedElementUtils.hasAnnotation(method.getMethod(), PlatformScreen.class)
                    || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), PlatformScreen.class)) {
                return true;
            }
            permission = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), StudioPermission.class);
        }
        if (permission == null) {
            log.error("Refusing {} {}: {} carries no @StudioPermission", request.getMethod(),
                    request.getRequestURI(), method.getShortLogMessage());
            throw new StudioForbiddenException("This screen declares no permission");
        }
        if (TenantContext.find().isEmpty()) {
            throw new StudioForbiddenException("A website screen with no website");
        }
        String needed = isRead(request) || permission.write().isEmpty() ? permission.read() : permission.write();
        if (!access.allowed(needed)) {
            throw new StudioForbiddenException("Not allowed: " + needed);
        }
        return true;
    }

    private void check(HttpServletRequest request, String read, String write, java.util.List<String> path) {
        String needed = isRead(request) || write.isEmpty() ? read : write;
        if (!access.allowedAt(needed, path)) {
            throw new StudioForbiddenException("Not allowed: " + needed);
        }
    }

    /** The method's annotation, else the class's. */
    private static <A extends Annotation> A find(HandlerMethod method, Class<A> type) {
        A found = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return found != null ? found : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }

    @SuppressWarnings("unchecked")
    private static String pathVariable(HttpServletRequest request, String name) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (String) ((Map<String, String>) map).get(name) : null;
    }

    private static boolean isRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }
}
