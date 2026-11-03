package io.pacod.cms.core.data;

/**
 * What kind of device a reader's page view came from. Stored by name, like {@link ReaderBrowser},
 * and never renamed for the same reason.
 */
public enum ReaderDevice {

    DESKTOP("Desktop"),
    MOBILE("Mobile"),
    TABLET("Tablet"),
    /** A television, a console, or a User-Agent that says nothing about it. */
    OTHER("Other");

    private final String label;

    ReaderDevice(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Lenient: an unknown or blank name is {@code null}, never an exception -- it comes off a query string. */
    public static ReaderDevice of(String name) {
        return ReaderEnums.of(ReaderDevice.class, name);
    }
}
