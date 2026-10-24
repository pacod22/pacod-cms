package io.pacod.cms.studio.form;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /mgmt/sites/new}. Checked by {@code PortalWebsites.create}, which re-checks everything the
 * screen offered -- the organisation above all, which arrives as a posted value.
 *
 * <p>{@code hosts}: further addresses, one per line, beside the automatic {@code <id>.<site domain>}.
 */
@Data
public class NewSiteForm {

    private String organisationId;
    private String id;
    private String name;
    private String language = "en";
    private String timezone = "UTC";
    private String hosts;
    /** Start with the demo content. */
    private boolean demo;
    /** Which demo, {@code DemoContent.Demo}'s id: the journal unless chosen. */
    private String demoKind = "journal";
    /** The template set; the default one unless chosen. */
    private String templateSet = io.pacod.cms.core.tenant.Tenant.DEFAULT_TEMPLATE_SET;

    public List<String> hostList() {
        return hosts == null ? List.of()
                : Arrays.stream(hosts.split("[\\s,]+")).map(String::strip).filter(h -> !h.isEmpty()).toList();
    }
}
