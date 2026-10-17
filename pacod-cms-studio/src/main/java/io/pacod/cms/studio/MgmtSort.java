package io.pacod.cms.studio;

/**
 * The column ordering behind the list screens that sort.
 *
 * <p>The companion to {@link MgmtSearch}, and package-private for the same reason: both are what
 * the {@code /mgmt} list screens do to a collection they have already read whole, and neither is
 * a query. The screens sort in the JVM because they are tens of rows -- the article list is the
 * opposite case, a paged collection sorted in MongoDB.
 *
 * <p>Both parameters arrive off a query string, so both are <b>allow-listed rather than trusted</b>:
 * {@link #key} never yields a column its caller did not name, which is what stops a URL turning
 * into a property path over a document. An unrecognised value is the caller's default rather than
 * an error -- this is an operator who edited an address bar, not an attack to fail loudly at.
 *
 * <p>The allow-list itself stays at the call site rather than living here: which columns a screen
 * has is a fact about that screen. What is shared is only the normalising, because two copies of
 * "asc unless it says desc" is exactly the kind of thing that drifts by one {@code equalsIgnoreCase}.
 */
final class MgmtSort {

    static final String ASC = "asc";
    static final String DESC = "desc";

    private MgmtSort() {
    }

    /**
     * @param requested the {@code sort} parameter, as typed into the URL; null and blank included
     * @param fallback  the screen's default column, returned for anything unrecognised
     * @param allowed   every column the screen actually offers a header for
     * @return one of {@code allowed}, or {@code fallback}
     */
    static String key(String requested, String fallback, String... allowed) {
        if (requested != null && !requested.isBlank()) {
            for (String candidate : allowed) {
                if (candidate.equalsIgnoreCase(requested)) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    /** @return {@code desc} only if that is what was asked for; ascending is the default. */
    static String direction(String requested) {
        return DESC.equalsIgnoreCase(requested) ? DESC : ASC;
    }

    /** Whether {@code direction(requested)} means the comparator should be reversed. */
    static boolean isDescending(String requested) {
        return DESC.equals(direction(requested));
    }

    /**
     * Nulls read as the empty string rather than sorting last, which is what makes a column's two
     * directions exact inverses of each other: with {@code nullsLast} the blank rows would sit at
     * the bottom of both and could never be brought into view.
     *
     * <p>Case is folded by {@code String.CASE_INSENSITIVE_ORDER} at the comparator, never by a
     * {@code Collator}: a collator is chosen by locale, and these rows must not be ordered by
     * whichever locale the JVM happened to start with -- the same reason {@link MgmtSearch} folds
     * with {@code Locale.ROOT}.
     */
    static String text(String value) {
        return value == null ? "" : value;
    }

    /** Kept beside {@link #text} so the two coalescing rules read as one decision. */
    static Integer number(Integer value) {
        return value == null ? Integer.MIN_VALUE : value;
    }
}
