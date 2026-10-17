package io.pacod.cms.studio.form;

import io.pacod.cms.core.control.data.OrgCreation;
import io.pacod.cms.core.control.data.PlatformSettings;
import lombok.Data;

/** {@code /mgmt/platform}: who may create organisations, the limits, AI for new organisations. */
@Data
public class PlatformSettingsForm {

    private OrgCreation orgCreation;
    private int maxOrgsPerUser;
    private int maxSitesPerOrg;
    private boolean aiEnabledForNewOrgs;

    public static PlatformSettingsForm of(PlatformSettings settings) {
        PlatformSettingsForm form = new PlatformSettingsForm();
        form.setOrgCreation(settings.getOrgCreation());
        form.setMaxOrgsPerUser(settings.getMaxOrgsPerUser());
        form.setMaxSitesPerOrg(settings.getMaxSitesPerOrg());
        form.setAiEnabledForNewOrgs(settings.isAiEnabledForNewOrgs());
        return form;
    }
}
