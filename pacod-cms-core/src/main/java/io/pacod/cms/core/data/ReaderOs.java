package io.pacod.cms.core.data;

/**
 * Which operating system a reader's page view came from. Stored by name, like
 * {@link ReaderBrowser}, and never renamed for the same reason.
 *
 * <p>iPadOS asks for desktop sites by default and then sends a Mac User-Agent, which nothing in
 * the request tells apart from a Mac's: those views are {@link #MACOS}, on a desktop.
 */
public enum ReaderOs {

    WINDOWS("Windows"),
    MACOS("macOS"),
    IOS("iOS"),
    ANDROID("Android"),
    CHROME_OS("ChromeOS"),
    LINUX("Linux"),
    OTHER("Other");

    private final String label;

    ReaderOs(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Lenient: an unknown or blank name is {@code null}, never an exception -- it comes off a query string. */
    public static ReaderOs of(String name) {
        return ReaderEnums.of(ReaderOs.class, name);
    }
}
