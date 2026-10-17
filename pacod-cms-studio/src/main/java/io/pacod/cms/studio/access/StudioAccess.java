package io.pacod.cms.studio.access;

import io.pacod.cms.core.identity.authz.Catalog;
import io.pacod.cms.core.identity.authz.Manifest;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Who may do what, here: the signed-in person's bindings ({@link SubjectSource}) evaluated by
 * pacod-id's rules engine against pacod-cms's manifest, {@code authz/cms.yaml}.
 *
 * <p>Tokens carry no roles, so this is the only place a permission is decided. Every check is
 * at a scope path: the platform, an organisation, or a website below its organisation.
 */
@Component
public class StudioAccess {

    public static final String PRODUCT = "cms";
    public static final String MANIFEST = "authz/cms.yaml";

    private final Catalog catalog;
    private final Manifest manifest;
    private final SubjectSource subjects;

    public StudioAccess(SubjectSource subjects) {
        this.subjects = subjects;
        this.manifest = Manifest.fromYaml(read(MANIFEST));
        this.catalog = new Catalog(manifest);
    }

    public Manifest manifest() {
        return manifest;
    }

    public Catalog catalog() {
        return catalog;
    }

    /** {@code [platform, organisation:<org>, cms.website:<id>]}. */
    public static List<String> pathOf(Tenant tenant) {
        return List.of("platform", "organisation:" + tenant.organisationId(), "cms.website:" + tenant.siteId());
    }

    public static List<String> pathOfOrganisation(String organisationId) {
        return List.of("platform", "organisation:" + organisationId);
    }

    public static final List<String> PLATFORM = List.of("platform");

    /** The signed-in person's bindings; none when nobody is signed in. */
    public Subject subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated()
                ? Subject.user(List.of()) : subjects.subjectOf(authentication);
    }

    /** {@code permission} (local name) at the website bound to this request; false when none is. */
    public boolean allowed(String permission) {
        return TenantContext.find().map(tenant -> allowedAt(permission, pathOf(tenant))).orElse(false);
    }

    public boolean allowedAt(String permission, List<String> path) {
        return catalog.allowed(subject(), qualify(permission), path);
    }

    /** Whether the person holds any role at, above or below {@code path}: what decides whether a website is listed. */
    public boolean touches(List<String> path) {
        return catalog.touches(subject(), path);
    }

    public boolean isPlatformAdmin() {
        return catalog.hasRole(subject(), PRODUCT + ".platform_admin", "platform");
    }

    static String qualify(String permission) {
        return permission.startsWith(PRODUCT + ".") ? permission : PRODUCT + "." + permission;
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
