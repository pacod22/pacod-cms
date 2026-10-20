package io.pacod.cms.core.data;

/**
 * How a PDF's article front matter is made. Either way the author's body is laid out as written;
 * the mode decides only where the abstract, the keywords and the running title come from.
 */
public enum PdfMode {

    /** From the article itself: its description and its tags. No gateway call. */
    CODE,

    /** Written by the AI gateway from the article's text ({@link PdfEnrichment}), checked, then laid out by code. */
    LLM;

    /** The value as it travels in a job parameter or a print view's query string. */
    public String param() {
        return name().toLowerCase();
    }

    /** {@code value} read case-insensitively, or {@code fallback} when it is blank or unknown. */
    public static PdfMode parse(String value, PdfMode fallback) {
        if (value != null) {
            for (PdfMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.strip())) {
                    return mode;
                }
            }
        }
        return fallback;
    }
}
