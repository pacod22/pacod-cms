package io.pacod.cms.core.data;

/**
 * How an article is laid out in a PDF. The print view picks its template from this enum, never
 * from the request's string, so an unknown value is the default rather than a template name.
 */
public enum PdfFormat {

    /** The website's own print view: one column, its theme. */
    STANDARD,

    /** A scientific paper: a full-width head with abstract and keywords, then two columns. */
    SCIENTIFIC;

    /** The value as it travels in a job parameter or a print view's query string. */
    public String param() {
        return name().toLowerCase();
    }

    /** {@code value} read case-insensitively, or {@code fallback} when it is blank or unknown. */
    public static PdfFormat parse(String value, PdfFormat fallback) {
        if (value != null) {
            for (PdfFormat format : values()) {
                if (format.name().equalsIgnoreCase(value.strip())) {
                    return format;
                }
            }
        }
        return fallback;
    }
}
