package io.pacod.cms.studio.access;

import io.pacod.cms.studio.config.MgmtLocaleConfig;
import io.pacod.cms.studio.site.StudioSites;

/**
 * What the chrome asks before it offers a link: {@code ${can.site('members.manage')}},
 * {@code ${can.platform()}}. A request attribute ({@link MgmtLocaleConfig#CAN_ATTRIBUTE}), as
 * Thymeleaf's restricted mode refuses bean references. Only for what to show -- every screen checks
 * its own permission whatever the chrome offered.
 */
public final class StudioCan {

    private final StudioAccess access;

    public StudioCan(StudioAccess access) {
        this.access = access;
    }

    /** {@code permission} at the website this request is for; false outside one. */
    public boolean site(String permission) {
        return access.allowed(permission);
    }

    /**
     * {@code permission} at {@code site}, bound or not: what the sidebar asks, since on a platform
     * screen it shows the website kept from the screens before ({@code navSite}), and
     * {@link #site} answers false where none is bound. False for none.
     */
    public boolean at(StudioSites.SiteView site, String permission) {
        return site != null && access.allowedAt(permission, StudioAccess.pathOfWebsite(site.organisationId(), site.id()));
    }

    /** Whether the person administers the installation. */
    public boolean platform() {
        return access.allowedAt("platform.manage", StudioAccess.PLATFORM);
    }

    /** {@code permission} at an organisation. */
    public boolean organisation(String organisationId, String permission) {
        return organisationId != null && access.allowedAt(permission, StudioAccess.pathOfOrganisation(organisationId));
    }
}
