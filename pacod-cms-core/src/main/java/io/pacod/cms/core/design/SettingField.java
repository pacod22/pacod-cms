package io.pacod.cms.core.design;

import java.util.List;

/**
 * One setting of a section type: what the studio's form shows and what {@link SectionSettings}
 * accepts.
 *
 * @param kind      how it is entered and checked
 * @param required  whether it must have a value
 * @param defaultValue what an absent value becomes; null for none
 * @param min       for numbers, the least value
 * @param max       for numbers the greatest value; for text the most characters
 * @param choices   for {@link Kind#CHOICE}, the values allowed, the first being the usual one
 */
public record SettingField(String name, Kind kind, boolean required, Object defaultValue, int min, int max,
                           List<String> choices) {

    public enum Kind {
        /** One line of plain text. */
        TEXT,
        /** A paragraph of plain text. */
        LONG_TEXT,
        /** HTML the website's owner writes, as in an article body. */
        HTML,
        NUMBER,
        CHOICE,
        /** A category's numeric id. */
        CATEGORY,
        /** An http(s) address, or one on the website itself ({@code /…}). */
        URL
    }

    public SettingField {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    static SettingField text(String name, boolean required, int maxLength) {
        return new SettingField(name, Kind.TEXT, required, null, 0, maxLength, List.of());
    }

    static SettingField longText(String name, boolean required, int maxLength) {
        return new SettingField(name, Kind.LONG_TEXT, required, null, 0, maxLength, List.of());
    }

    static SettingField html(String name, boolean required, int maxLength) {
        return new SettingField(name, Kind.HTML, required, null, 0, maxLength, List.of());
    }

    static SettingField number(String name, int defaultValue, int min, int max) {
        return new SettingField(name, Kind.NUMBER, true, defaultValue, min, max, List.of());
    }

    static SettingField choice(String name, String... choices) {
        return new SettingField(name, Kind.CHOICE, true, choices[0], 0, 0, List.of(choices));
    }

    static SettingField category(String name) {
        return new SettingField(name, Kind.CATEGORY, false, null, 1, Integer.MAX_VALUE, List.of());
    }

    static SettingField url(String name, boolean required) {
        return new SettingField(name, Kind.URL, required, null, 0, 2000, List.of());
    }
}
