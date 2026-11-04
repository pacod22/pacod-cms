package io.pacod.cms.studio.form;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import lombok.Data;

/**
 * {@code /mgmt/settings}: a website's name, language, time zone, whether it is serving, and
 * whether it uses AI ({@code Website.aiDisabled}, the opposite way round so an old document reads
 * as allowed).
 */
@Data
public class SiteSettingsForm {

    private String name;
    private String language;
    private String timezone;
    private WebsiteStatus status;
    private boolean ai;
    /**
     * pacod-broker: {@code organisation}, {@code platform}, {@code public}, or blank for not offered.
     * Null when the form did not carry it (no broker configured): the stored value stays.
     */
    private String brokerOffer;
    private String brokerSummary;

    public static SiteSettingsForm of(Website website) {
        SiteSettingsForm form = new SiteSettingsForm();
        form.setName(website.getName());
        form.setLanguage(website.getDefaultLanguage());
        form.setTimezone(website.getTimezone());
        form.setStatus(website.getStatus());
        form.setAi(!website.isAiDisabled());
        form.setBrokerOffer(website.getBrokerOffer() == null ? "" : website.getBrokerOffer());
        form.setBrokerSummary(website.getBrokerSummary());
        return form;
    }
}
