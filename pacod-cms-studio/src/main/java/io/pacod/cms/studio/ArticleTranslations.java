package io.pacod.cms.studio;

import io.pacod.cms.core.data.TextItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes one language's entry in an article's {@code titles}, {@code descriptions} or
 * {@code texts} list.
 *
 * <p>Kept here rather than on {@code Article}: that class is serialized by the public
 * {@code /web/v1} controllers, and nothing outside the console needs these.
 *
 * <p>The rules are {@code Page.put}'s: a blank value is <b>removed</b>, never stored as {@code ""}.
 * {@code ArticleModel.getLocalizedText} does not test for blank, so a stored {@code ""} would be
 * served to a reader as an empty title or body instead of falling back to English or the
 * original language.
 */
public final class ArticleTranslations {

    private ArticleTranslations() {
    }

    /** The stored text for exactly this language, or null. Never falls back to another language. */
    public static String raw(List<TextItem> items, String lang) {
        if (items == null || lang == null) {
            return null;
        }
        for (TextItem item : items) {
            if (item != null && matches(item, lang) && item.getText() != null && !item.getText().isBlank()) {
                return item.getText();
            }
        }
        return null;
    }

    /**
     * A copy of {@code items} with this language's entries replaced by {@code value}, or removed
     * when it is blank.
     *
     * <p>Returns a new list rather than editing in place: the stored list can be null, and one
     * read back from MongoDB or built in a test with {@code List.of} need not be mutable.
     */
    public static List<TextItem> put(List<TextItem> items, String lang, String value) {
        List<TextItem> result = items == null ? new ArrayList<>() : new ArrayList<>(items);
        result.removeIf(item -> item == null || matches(item, lang));
        if (value != null && !value.isBlank()) {
            result.add(new TextItem(value, lang));
        }
        return result;
    }

    /** Stored codes may come from imports or other tools, so compare trimmed and ignoring case. */
    private static boolean matches(TextItem item, String lang) {
        return item.getLang() != null && item.getLang().trim().equalsIgnoreCase(lang);
    }
}
