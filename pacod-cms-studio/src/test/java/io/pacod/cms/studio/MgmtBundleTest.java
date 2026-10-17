package io.pacod.cms.studio;

import io.pacod.cms.studio.i18n.MgmtText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's message bundles, checked as files: {@code i18n/<area>.properties} is English and
 * {@code _bg}, {@code _de}, {@code _ru} beside it must say the same things.
 *
 * <p>A key missing from one language does not fail anywhere else -- the MessageSource quietly
 * falls back to English -- so a German console with one English button in it would pass every
 * render test. This is what stops that.
 */
class MgmtBundleTest {

    static final List<String> TRANSLATIONS = List.of("bg", "de", "ru");

    private static final Pattern ARG = Pattern.compile("\\{(\\d+)[^}]*}");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    static Properties bundle(String basename, String lang) {
        return MgmtText.load(basename + (lang == null ? "" : "_" + lang) + ".properties");
    }

    /** Every key across all areas, English base files. */
    static Set<String> allKeys() {
        Set<String> keys = new TreeSet<>();
        for (Properties p : MgmtText.baseBundles()) {
            keys.addAll(p.stringPropertyNames());
        }
        return keys;
    }

    @Test
    @DisplayName("every area has the same keys in all four languages")
    void sameKeys() {
        for (String basename : MgmtText.BASENAMES) {
            Set<String> base = bundle(basename, null).stringPropertyNames();
            for (String lang : TRANSLATIONS) {
                Set<String> translated = bundle(basename, lang).stringPropertyNames();
                assertThat(new TreeSet<>(translated))
                        .as("%s_%s.properties keys", basename, lang)
                        .isEqualTo(new TreeSet<>(base));
            }
        }
    }

    @Test
    @DisplayName("no key is defined in two areas")
    void noKeyInTwoAreas() {
        Set<String> seen = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Properties p : MgmtText.baseBundles()) {
            for (String key : p.stringPropertyNames()) {
                if (!seen.add(key)) {
                    duplicates.add(key);
                }
            }
        }
        assertThat(duplicates).isEmpty();
    }

    @Test
    @DisplayName("values are not blank, parse as MessageFormat, and keep the same {n} in every language")
    void valuesAreWellFormed() {
        List<String> problems = new ArrayList<>();
        for (String basename : MgmtText.BASENAMES) {
            Properties base = bundle(basename, null);
            for (String lang : withBase()) {
                Properties p = lang == null ? base : bundle(basename, lang);
                for (String key : p.stringPropertyNames()) {
                    String value = p.getProperty(key);
                    String where = basename + (lang == null ? "" : "_" + lang) + ": " + key;
                    if (value.isBlank()) {
                        problems.add(where + " is blank");
                        continue;
                    }
                    if (hasLoneApostrophe(value)) {
                        problems.add(where + " has a lone ' (write '')");
                    }
                    try {
                        new MessageFormat(value);
                    } catch (IllegalArgumentException e) {
                        problems.add(where + " is not a valid MessageFormat: " + e.getMessage());
                    }
                    String baseValue = base.getProperty(key);
                    if (baseValue != null && !args(value).equals(args(baseValue))) {
                        problems.add(where + " uses " + args(value) + " but English uses " + args(baseValue));
                    }
                    if (value.contains("<") && !args(value).isEmpty()) {
                        problems.add(where + " carries markup AND arguments -- with th:utext that is "
                                + "stored XSS; split the sentence around the element instead");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("English and German contain no Cyrillic")
    void noCyrillicInLatinTranslations() {
        List<String> problems = new ArrayList<>();
        for (String basename : MgmtText.BASENAMES) {
            for (String lang : java.util.Arrays.asList(null, "de")) {
                Properties p = bundle(basename, lang);
                for (String key : p.stringPropertyNames()) {
                    if (CYRILLIC.matcher(p.getProperty(key)).find() && !key.endsWith(".native")) {
                        problems.add(basename + (lang == null ? "" : "_" + lang) + ": " + key + " = " + p.getProperty(key));
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    static List<String> withBase() {
        List<String> all = new ArrayList<>();
        all.add(null);
        all.addAll(TRANSLATIONS);
        return all;
    }

    static Set<String> args(String value) {
        Set<String> found = new TreeSet<>();
        Matcher m = ARG.matcher(value.replace("''", ""));
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /**
     * Every value goes through MessageFormat, where {@code '} starts a quoted section: "don't" would
     * lose its apostrophe and quote everything after it, braces included. Written {@code ''}.
     */
    static boolean hasLoneApostrophe(String value) {
        return value.replace("''", "").contains("'");
    }
}
