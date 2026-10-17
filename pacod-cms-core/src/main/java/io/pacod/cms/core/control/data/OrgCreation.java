package io.pacod.cms.core.control.data;

/** Who may create an organisation. */
public enum OrgCreation {

    /** Anyone signed in, up to {@link PlatformSettings#getMaxOrgsPerUser()} each. */
    SELF_SERVICE,

    /** Platform admins only. */
    PLATFORM_ADMINS
}
