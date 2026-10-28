package io.pacod.cms.core.design;

import io.pacod.cms.core.util.HttpUrls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks a section's settings against its type's fields and returns them clean: numbers as
 * integers, text stripped, blanks absent, defaults filled in, unknown names dropped.
 *
 * <p>Run when the studio saves a layout <b>and</b> when the site renders one: a stored document can
 * predate a change to a type's fields, and a hand-written one can say anything. A section whose
 * settings fail is not rendered; the studio says why.
 */
public final class SectionSettings {

    /** A standing page's slug, as the studio makes them and as a menu's page link takes them. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private SectionSettings() {
    }

    /**
     * The clean settings, or the reasons they are refused.
     *
     * @param errors one entry per refused field: {@code <code>:<field>}, the code one of
     *               {@code required}, {@code number}, {@code range}, {@code choice}, {@code length},
     *               {@code url}, {@code slug}
     */
    public record Result(Map<String, Object> settings, List<String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Result check(SectionType type, Map<String, ?> raw) {
        Map<String, ?> in = raw == null ? Map.of() : raw;
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (SettingField field : type.fields()) {
            Object value = in.get(field.name());
            if (value instanceof String s && s.isBlank()) {
                value = null;
            }
            if (value == null) {
                if (field.defaultValue() != null) {
                    out.put(field.name(), field.defaultValue());
                } else if (field.required()) {
                    errors.add("required:" + field.name());
                }
                continue;
            }
            switch (field.kind()) {
                case NUMBER, CATEGORY -> {
                    Integer n = integer(value);
                    if (n == null) {
                        errors.add("number:" + field.name());
                    } else if (n < field.min() || n > field.max()) {
                        errors.add("range:" + field.name());
                    } else {
                        out.put(field.name(), n);
                    }
                }
                case CHOICE -> {
                    String s = String.valueOf(value).strip();
                    if (field.choices().contains(s)) {
                        out.put(field.name(), s);
                    } else {
                        errors.add("choice:" + field.name());
                    }
                }
                case TEXT, LONG_TEXT, HTML -> {
                    String s = String.valueOf(value);
                    s = field.kind() == SettingField.Kind.TEXT ? s.strip().replaceAll("\\s+", " ") : s.strip();
                    if (s.length() > field.max()) {
                        errors.add("length:" + field.name());
                    } else {
                        out.put(field.name(), s);
                    }
                }
                case URL -> {
                    String s = String.valueOf(value).strip();
                    if (isSiteRelative(s) || HttpUrls.isHttpUrl(s)) {
                        out.put(field.name(), s);
                    } else {
                        errors.add("url:" + field.name());
                    }
                }
                case PAGE -> {
                    // Checked as written, never lowercased: the studio and a seed look the page up
                    // by exactly this slug.
                    String s = String.valueOf(value).strip();
                    if (s.length() > field.max()) {
                        errors.add("length:" + field.name());
                    } else if (SLUG.matcher(s).matches()) {
                        out.put(field.name(), s);
                    } else {
                        errors.add("slug:" + field.name());
                    }
                }
            }
        }
        // A list drawn from a category needs the category.
        if (type == SectionType.ARTICLE_LIST && "category".equals(out.get("source")) && out.get("category") == null
                && errors.stream().noneMatch(e -> e.endsWith(":category"))) {
            errors.add("required:category");
        }
        return new Result(errors.isEmpty() ? Map.copyOf(out) : Map.of(), List.copyOf(errors));
    }

    /** One leading slash, then no slash or backslash next, no whitespace or control character. */
    static boolean isSiteRelative(String url) {
        if (url.length() < 1 || url.charAt(0) != '/' || url.startsWith("//") || url.startsWith("/\\")) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '\\' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return url.length() <= 2000;
    }

    private static Integer integer(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE ? (int) d : null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
