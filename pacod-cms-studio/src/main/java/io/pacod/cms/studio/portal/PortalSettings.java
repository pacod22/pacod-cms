package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.OrgCreation;
import io.pacod.cms.core.control.data.PlatformSettings;
import io.pacod.cms.core.control.repository.PlatformSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The installation's settings ({@link PlatformSettings}), with {@link PortalProperties} as their
 * starting values and {@code org-creation} as a ceiling.
 */
@Slf4j
@Service
public class PortalSettings {

    public static final int MAX_LIMIT = 10_000;

    private final PlatformSettingsRepository repository;
    private final PortalProperties properties;

    public PortalSettings(PlatformSettingsRepository repository, PortalProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** The saved settings, or the configured starting values; never throws. */
    public PlatformSettings current() {
        try {
            return repository.findById(PlatformSettings.ID).orElseGet(this::defaults);
        } catch (DataAccessException e) {
            log.error("Could not read the platform settings: {}", e.getMessage());
            return defaults();
        }
    }

    /** Whether configuration restricts creating organisations, whatever the settings say. */
    public boolean orgCreationFixed() {
        return properties.orgCreation() == OrgCreation.PLATFORM_ADMINS;
    }

    /** Who may create organisations, after the ceiling. */
    public OrgCreation orgCreation() {
        return orgCreationFixed() ? OrgCreation.PLATFORM_ADMINS : current().getOrgCreation();
    }

    public PlatformSettings save(OrgCreation orgCreation, int maxOrgsPerUser, int maxSitesPerOrg,
                                 boolean aiEnabledForNewOrgs, String by) {
        PlatformSettings settings = current();
        if (orgCreation != null) {
            // Null when the control was disabled (fixed by configuration): keep what is stored.
            settings.setOrgCreation(orgCreation);
        }
        settings.setMaxOrgsPerUser(clamp(maxOrgsPerUser));
        settings.setMaxSitesPerOrg(clamp(maxSitesPerOrg));
        settings.setAiEnabledForNewOrgs(aiEnabledForNewOrgs);
        settings.setUpdatedOn(Instant.now());
        settings.setUpdatedBy(by);
        return repository.save(settings);
    }

    private PlatformSettings defaults() {
        PlatformSettings settings = new PlatformSettings();
        settings.setOrgCreation(properties.orgCreation());
        settings.setMaxOrgsPerUser(clamp(properties.maxOrgsPerUser()));
        settings.setMaxSitesPerOrg(clamp(properties.maxSitesPerOrg()));
        settings.setAiEnabledForNewOrgs(properties.aiEnabledForNewOrgs());
        return settings;
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(MAX_LIMIT, value));
    }
}
