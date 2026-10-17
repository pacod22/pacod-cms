package io.pacod.cms.studio;

import com.mongodb.DBRef;
import org.bson.Document;

/**
 * Reads the id out of a {@code @DBRef} that arrived as an aggregation group key.
 *
 * <p>Two screens group articles by a {@code @DBRef} list -- the category list by
 * {@code categories}, the image library by {@code images} -- because a field path beginning with
 * {@code $} is not a legal aggregation expression, so {@code categories.$id} cannot be grouped on
 * and the whole element has to be. One rule, two callers, so the rule lives where they meet,
 * exactly as {@code HttpUrls.isHttpUrl} does for the banner redirect.
 *
 * <h2>Why both shapes</h2>
 * <b>The driver decodes {@code {$ref, $id}} into a {@link DBRef}</b>, not an {@link Document} --
 * verified against the default codec registry directly and end to end against the running stack.
 * Code that reads only the {@code Document} shape returns an empty map without a word, and the
 * category list's article-count column shows 0 for every category. A unit test does not catch it
 * if it builds the group key as a {@code Document} -- the shape the code expects rather than the
 * one MongoDB sends -- so build test keys the way MongoDB sends them.
 *
 * <p>The {@code Document} branch is kept as a guard against a different codec registry; it is not
 * the path that fires. Reading the wrong shape does not throw. It answers "nothing", which is a
 * zero in a column on one screen and a list of images falsely offered for deletion on the other.
 */
final class MgmtDbRefs {

    private MgmtDbRefs() {
    }

    /** MongoDB stores a {@code @DBRef} as {@code {$ref: "category", $id: 17}}. */
    private static final String DBREF_ID = "$id";

    /**
     * @param groupKey the {@code _id} of an aggregation result grouped on a {@code @DBRef} field
     * @return the referenced document's id, or null if the key is neither shape or carries a
     *         non-numeric id. Every id in this application is an application-assigned integer.
     */
    static Integer idOf(Object groupKey) {
        if (groupKey instanceof DBRef ref && ref.getId() instanceof Number id) {
            return id.intValue();
        }
        if (groupKey instanceof Document ref && ref.get(DBREF_ID) instanceof Number id) {
            return id.intValue();
        }
        return null;
    }
}
