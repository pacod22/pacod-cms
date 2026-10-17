package io.pacod.cms.core.data;

/**
 * Whether a crawler is who its User-Agent says it is, by reverse- then forward-DNS.
 *
 * <p>Three outcomes, not two, and the third is the important one. {@link #UNVERIFIED} is a
 * <em>positive</em> finding: the address has no PTR record, or one that names some other operator,
 * or a PTR the forward lookup does not confirm -- a client claiming to be Googlebot from somewhere
 * Google does not crawl from. {@link #UNKNOWN} means nobody checked or the check could not finish:
 * a crawler whose operator publishes no DNS scheme, a DNS timeout, a private address, verification
 * switched off. Folding the second into the first would label every Ahrefs hit and every DNS hiccup
 * as a forgery.
 *
 * <p>The name is stored on every {@link CrawlerVisit}; never rename a constant.
 */
public enum CrawlerVerification {

    VERIFIED("verified"),
    UNVERIFIED("fake"),
    UNKNOWN("unverified");

    private final String label;

    CrawlerVerification(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Lenient, for a query-string filter: unknown or blank is {@code null}. */
    public static CrawlerVerification of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (CrawlerVerification verification : values()) {
            if (verification.name().equalsIgnoreCase(name.strip())) {
                return verification;
            }
        }
        return null;
    }
}
