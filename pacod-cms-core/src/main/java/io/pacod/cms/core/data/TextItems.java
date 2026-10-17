package io.pacod.cms.core.data;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-language list rules of {@link Page}, for documents that keep more than one translated
 * field: {@link SocialPage} and {@link SocialLink}.
 *
 * <p>The rules are {@code Page}'s, unchanged:
 * <ul>
 *   <li>A missing translation is an <b>absent entry</b>, never {@code ""}. Blank stored text reads
 *       as absent, and {@link #put} removes the entry rather than storing a blank.</li>
 *   <li>Resolution walks a {@link LanguageChain}; nothing here names a default language.</li>
 *   <li>{@link #put} returns a <b>new</b> list. A stored list can be {@code null} or immutable
 *       (the {@code ArticleTranslations.put} lesson), so the caller assigns the result.</li>
 * </ul>
 * {@code Page} keeps its own private copies of these; the two must keep the same rules.
 */
public final class TextItems {

    private TextItems() {
    }

    /** The text stored for exactly {@code lang}, or {@code null}. No fallback. */
    public static String find(List<TextItem> items, String lang) {
        if (items == null || lang == null) {
            return null;
        }
        String code = LanguageChain.normalise(lang);
        if (code == null) {
            return null;
        }
        for (TextItem item : items) {
            if (item != null && code.equalsIgnoreCase(item.getLang())
                    && item.getText() != null && !item.getText().isBlank()) {
                return item.getText();
            }
        }
        return null;
    }

    /** The text in the first language of {@code chain} that has one, or {@code null}. */
    public static String resolve(List<TextItem> items, List<String> chain) {
        String lang = resolvedLang(items, chain);
        return lang == null ? null : find(items, lang);
    }

    /** Which language of {@code chain} {@link #resolve} would answer in, or {@code null}. */
    public static String resolvedLang(List<TextItem> items, List<String> chain) {
        if (chain == null) {
            return null;
        }
        for (String lang : chain) {
            if (find(items, lang) != null) {
                return LanguageChain.normalise(lang);
            }
        }
        return null;
    }

    /** A copy of {@code items} with {@code lang}'s entry replaced, or removed when {@code text} is blank. */
    public static List<TextItem> put(List<TextItem> items, String lang, String text) {
        String code = LanguageChain.normalise(lang);
        List<TextItem> copy = new ArrayList<>(items == null ? List.of() : items);
        if (code == null) {
            return copy;
        }
        copy.removeIf(item -> item == null || code.equalsIgnoreCase(item.getLang()));
        if (text != null && !text.isBlank()) {
            copy.add(TextItem.builder().lang(code).text(text.strip()).build());
        }
        return copy;
    }

    /** Appends to {@code into} every language {@code items} holds text in, lowercase, once each. */
    static void collectLanguages(List<TextItem> items, List<String> into) {
        for (TextItem item : items == null ? List.<TextItem>of() : items) {
            if (item != null && item.getText() != null && !item.getText().isBlank()
                    && item.getLang() != null) {
                String code = item.getLang().toLowerCase(java.util.Locale.ROOT);
                if (!into.contains(code)) {
                    into.add(code);
                }
            }
        }
    }
}
