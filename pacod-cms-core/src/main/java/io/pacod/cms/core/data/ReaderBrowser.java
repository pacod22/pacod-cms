package io.pacod.cms.core.data;

/**
 * Which browser a reader's page view came from -- one of the splits the monitoring screen and the
 * reader log report, decided once per view from the User-Agent by the site's
 * {@code ReaderAgentClassifier}.
 *
 * <p>The enum <b>name</b> is stored: as a key of {@code viewsByBrowser} in the traffic buckets and
 * on every {@link ReaderVisit} row. A constant must never be renamed -- buckets already written
 * under the old name would stop being counted under any row. Adding one is safe.
 *
 * <p>The in-app browsers carry the {@link SocialSource} names of the networks whose User-Agent
 * tokens identify them, which is how the classifier maps one onto the other.
 */
public enum ReaderBrowser {

    CHROME("Chrome"),
    SAFARI("Safari"),
    FIREFOX("Firefox"),
    EDGE("Edge"),
    OPERA("Opera"),
    SAMSUNG("Samsung Internet"),
    YANDEX("Yandex Browser"),
    VIVALDI("Vivaldi"),
    INTERNET_EXPLORER("Internet Explorer"),
    INSTAGRAM("Instagram app"),
    FACEBOOK("Facebook app"),
    MESSENGER("Messenger app"),
    THREADS("Threads app"),
    TIKTOK("TikTok app"),
    LINKEDIN("LinkedIn app"),
    SNAPCHAT("Snapchat app"),
    X("X app"),
    PINTEREST("Pinterest app"),
    WHATSAPP("WhatsApp app"),
    /** A browser the table does not name, or no User-Agent worth reading. */
    OTHER("Other");

    private final String label;

    ReaderBrowser(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Lenient: an unknown or blank name is {@code null}, never an exception -- it comes off a query string. */
    public static ReaderBrowser of(String name) {
        return ReaderEnums.of(ReaderBrowser.class, name);
    }
}
