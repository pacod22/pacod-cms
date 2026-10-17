package io.pacod.cms.core.data;

/**
 * Where a {@link SocialLink} sits on {@code /social}: in the row of network icons under the
 * profile, or in the list of full-width buttons below it.
 *
 * <p>One list of links with a placement, not two lists, so a link keeps its id -- and with it its
 * statistics -- when the operator moves it from one to the other. Ordering is still per placement:
 * {@code MgmtSocialService.moveLink} swaps with the nearest link of the same placement.
 */
public enum SocialLinkPlacement {

    ICON("Icon"),
    BUTTON("Link");

    private final String label;

    SocialLinkPlacement(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
