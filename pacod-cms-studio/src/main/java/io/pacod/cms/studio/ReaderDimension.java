package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.ReaderBrowser;
import io.pacod.cms.core.data.ReaderDevice;
import io.pacod.cms.core.data.ReaderOs;
import io.pacod.cms.core.data.ReaderVisit;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The ways a reader's view is split: what the monitoring screen's reader cards show from the
 * buckets, and what the reader log ({@code /mgmt/monitoring/readers}) filters and groups its rows
 * by.
 *
 * <p>Each one names the query parameter that filters by it, the {@link ReaderVisit} field it
 * reads, and the enum its stored keys are names of -- {@code null} for the two languages, whose
 * keys are codes. The parameter for the page type is {@code type}, because {@code page} is the
 * log's page number, as it is on every other paged screen here.
 */
public enum ReaderDimension {

    PAGE("type", ReaderVisit.FIELD_PAGE, BannerPage.class, "Page type"),
    LANGUAGE("served", ReaderVisit.FIELD_SERVED_LANGUAGE, null, "Language read"),
    BROWSER_LANGUAGE("preferred", ReaderVisit.FIELD_BROWSER_LANGUAGE, null, "Browser language"),
    BROWSER("browser", ReaderVisit.FIELD_BROWSER, ReaderBrowser.class, "Browser"),
    OS("os", ReaderVisit.FIELD_OS, ReaderOs.class, "Operating system"),
    DEVICE("device", ReaderVisit.FIELD_DEVICE, ReaderDevice.class, "Device");

    /** What the site leaves of a language code: two or three letters, or {@code und}. */
    private static final Pattern LANGUAGE_KEY = Pattern.compile("[a-z]{2,3}");

    private final String param;
    private final String field;
    private final Class<? extends Enum<?>> values;
    private final String label;

    ReaderDimension(String param, String field, Class<? extends Enum<?>> values, String label) {
        this.param = param;
        this.field = field;
        this.values = values;
        this.label = label;
    }

    public String getParam() {
        return param;
    }

    public String getField() {
        return field;
    }

    public String getLabel() {
        return label;
    }

    public boolean isLanguage() {
        return values == null;
    }

    /**
     * The stored key a query-string value names, or {@code null} -- never an exception, and never
     * the value as typed: an enum constant's name, or a language code of the shape the site writes.
     */
    public String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (isLanguage()) {
            String code = value.toLowerCase(Locale.ROOT);
            return LANGUAGE_KEY.matcher(code).matches() ? code : null;
        }
        Enum<?> constant = constant(value);
        return constant == null ? null : constant.name();
    }

    /** The constant a stored key names, for its label; {@code null} for a language or an unknown name. */
    public Enum<?> constant(String key) {
        if (values == null || key == null) {
            return null;
        }
        for (Enum<?> constant : values.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(key.strip())) {
                return constant;
            }
        }
        return null;
    }

    /**
     * A language code's name in the operator's language -- {@code Deutsch}, {@code немски} --
     * capitalised, or the code itself when the JDK knows no name for it; {@code unknown} for
     * {@link ReaderVisit#UNKNOWN_LANGUAGE}, whose JDK name ("Unknown language") is English
     * whatever the operator reads.
     */
    public static String languageName(String code, Locale ui, String unknown) {
        if (code == null || ReaderVisit.UNKNOWN_LANGUAGE.equals(code)) {
            return unknown;
        }
        String name = Locale.of(code).getDisplayLanguage(ui);
        if (name == null || name.isBlank() || name.equalsIgnoreCase(code)) {
            return code;
        }
        return name.substring(0, 1).toUpperCase(ui) + name.substring(1);
    }
}
