package io.pacod.cms.studio.ai;

/** Why AI is not available for a website, or a person there; the first that applies (AiAccess). */
public enum AiRefusal {
    /** No gateway address or key on this installation. */
    NOT_SET_UP,
    /** A platform admin has not switched AI on for the organisation. */
    ORGANISATION_OFF,
    /** The website's own admin switched it off. */
    WEBSITE_OFF,
    /** The person holds no {@code ai.use} here. */
    NOT_ALLOWED
}
