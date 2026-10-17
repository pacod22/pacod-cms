package io.pacod.cms.core.data;

/**
 * The two booking surfaces the public templates offer.
 *
 * <p>{@link #TOP} is the {@code .partner-row} grid above the main column; {@link #SIDEBAR} is the
 * advertiser column beside it. Both are rendered by {@code fragments/ad-sidebar.html}, which
 * loops over whatever the controller put in the model -- so the number of bookings on either
 * surface is data, not markup.
 */
public enum BannerPlacement {

    TOP("Top"),
    SIDEBAR("Sidebar");

    private final String label;

    BannerPlacement(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
