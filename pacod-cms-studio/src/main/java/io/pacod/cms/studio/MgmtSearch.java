package io.pacod.cms.studio;

import java.util.Locale;

/**
 * The substring matching behind the author and category list filters.
 *
 * <p>Deliberately in memory rather than a MongoDB query. Both screens already read their whole
 * collection and sort it in the JVM -- they are tens of rows, not thousands -- so a repository
 * query would add a round trip and a regex without removing any work. The article list is the
 * opposite case and correctly uses {@code fulltextSearch}: it is paged over a collection that
 * grows without bound.
 *
 * <p>Whitespace splits the query into terms that must <em>all</em> match, each against any field.
 * That is what makes "Morgan Alex" find the same author as "Alex Morgan" -- a plain
 * {@code contains} over the joined fields only ever matches the order they are joined in.
 */
final class MgmtSearch {

    private MgmtSearch() {
    }

    /**
     * @param query  the operator's search box; null or blank means "no filter"
     * @param fields the row's searchable values; nulls are skipped and numbers are matched as
     *               their decimal text, <b>as a substring like everything else</b> -- "1" finds
     *               ids 1, 17 and 100 alike. That is a filter box, not a lookup by id
     * @return true if every term of {@code query} occurs in some field, ignoring case. A blank
     *         query matches everything.
     */
    static boolean matches(String query, Object... fields) {
        if (query == null || query.isBlank()) {
            return true;
        }

        // Locale.ROOT, not the default locale: this runs under whatever locale the JVM was
        // started with, and case folding must not depend on that.
        StringBuilder joined = new StringBuilder(128);
        for (Object field : fields) {
            if (field != null) {
                joined.append(field.toString().toLowerCase(Locale.ROOT)).append(' ');
            }
        }

        String haystack = joined.toString();
        for (String term : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
