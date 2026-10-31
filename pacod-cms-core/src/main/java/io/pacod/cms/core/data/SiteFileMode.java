package io.pacod.cms.core.data;

/**
 * How a website answers one of its crawler files ({@link SiteFileKind}).
 *
 * <ul>
 *   <li>{@link #AUTO}: generated from the website's content on every request.</li>
 *   <li>{@link #AUTO_PLUS}: generated, with the operator's additions and exclusions applied.</li>
 *   <li>{@link #CUSTOM}: the operator's stored text, served verbatim; it no longer follows the
 *       content.</li>
 *   <li>{@link #OFF}: a 404.</li>
 * </ul>
 */
public enum SiteFileMode {

    AUTO("Automatic"),
    AUTO_PLUS("Automatic with additions"),
    CUSTOM("Custom"),
    OFF("Off");

    private final String label;

    SiteFileMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** The mode named, ignoring case; {@code fallback} for anything else. */
    public static SiteFileMode parse(String value, SiteFileMode fallback) {
        if (value != null) {
            for (SiteFileMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.strip())) {
                    return mode;
                }
            }
        }
        return fallback;
    }
}
