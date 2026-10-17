package io.pacod.cms.core.data;

/**
 * What kind of automated client a crawler hit came from -- the grouping the crawlers screen
 * charts by.
 *
 * <p>The enum <b>name</b> is stored on every {@link CrawlerVisit} row, so a constant must never be
 * renamed: rows already written under the old name would stop mapping. The traffic buckets do not
 * store it at all -- they are keyed by bot id, and the category is derived through
 * {@code CrawlerClassifier.categoryOf}, which is why a bucket written before a table edit still
 * lands in some category rather than none.
 *
 * <p>{@link #getChartKey()} is a CSS class: {@code mgmt.css} carries one rule set per key, and a
 * key without one draws no line at all, since the base {@code polyline} rule sets no stroke.
 */
public enum CrawlerCategory {

    SEARCH("Search engines", "crawl-search"),
    AI("Artificial intelligence", "crawl-ai"),
    SEO("SEO tools", "crawl-seo"),
    SOCIAL("Social previews", "crawl-social"),
    /**
     * Automated, but not named by the table: scripts ({@code curl}, {@code python-requests}), a
     * generic {@code bot}/{@code spider}, a request with no User-Agent at all. Never counted as
     * readers: they are kept out of the readers and shown here as one row.
     */
    OTHER("Other", "crawl-other");

    private final String label;
    private final String chartKey;

    CrawlerCategory(String label, String chartKey) {
        this.label = label;
        this.chartKey = chartKey;
    }

    public String getLabel() {
        return label;
    }

    public String getChartKey() {
        return chartKey;
    }

    /** Lenient: an unknown or blank name is {@code null}, never an exception -- it comes off a query string. */
    public static CrawlerCategory of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (CrawlerCategory category : values()) {
            if (category.name().equalsIgnoreCase(name.strip())) {
                return category;
            }
        }
        return null;
    }
}
