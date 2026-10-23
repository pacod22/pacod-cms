package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.OrgCreation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The portal's configuration ({@code pacod.portal.*}).
 *
 * @param orgCreation          a ceiling: {@link OrgCreation#PLATFORM_ADMINS} restricts creating
 *                             organisations whatever the platform settings say
 * @param maxOrgsPerUser       the platform settings' starting value
 * @param maxSitesPerOrg       the platform settings' starting value
 * @param aiEnabledForNewOrgs  the platform settings' starting value
 * @param siteDomain           every new website's first address is {@code <id>.<siteDomain>};
 *                             blank, its creator names one
 * @param siteDomainShared     lets {@code siteDomain} share a registrable domain with the studio and
 *                             pacod-id ({@link StudioDomainCheck}, {@link DomainRules}): a website's
 *                             script can then set cookies they receive. Only for an installation
 *                             whose websites are all trusted, such as a demo on one domain
 */
@ConfigurationProperties("pacod.portal")
public record PortalProperties(
        @DefaultValue("SELF_SERVICE") OrgCreation orgCreation,
        @DefaultValue("3") int maxOrgsPerUser,
        @DefaultValue("5") int maxSitesPerOrg,
        @DefaultValue("false") boolean aiEnabledForNewOrgs,
        @DefaultValue("") String siteDomain,
        @DefaultValue("false") boolean siteDomainShared) {

    public PortalProperties {
        siteDomain = siteDomain == null ? "" : siteDomain.strip().toLowerCase(java.util.Locale.ROOT);
        while (siteDomain.startsWith(".")) {
            siteDomain = siteDomain.substring(1);
        }
    }
}
