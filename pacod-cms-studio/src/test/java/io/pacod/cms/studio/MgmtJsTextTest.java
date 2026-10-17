package io.pacod.cms.studio;

import io.pacod.cms.studio.i18n.MgmtText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mgmt.js, read as a file: every string it shows goes through {@code t()} and the {@code js.*} keys
 * of the common bundle, and the TinyMCE language packs it asks for are on the classpath.
 *
 * <p>The repository has no JavaScript toolchain, so nothing else can see this. A key mgmt.js names
 * and the bundle lacks renders as the key itself on screen; a Bulgarian literal left in the script
 * renders into an English console; a {@code data-tinymce} pointing at a pack that is not vendored
 * makes TinyMCE fail to load its language and fall back silently. All three answer 200.
 */
class MgmtJsTextTest {

    private static final String SCRIPT = "static/mgmt-assets/mgmt.js";

    /** t('key') / t("key") and tHtml('key'), the two ways mgmt.js asks for a message. */
    private static final Pattern CALL = Pattern.compile("\\b(?:t|tHtml)\\(\\s*(['\"])([A-Za-z][A-Za-z0-9_.-]*)\\1");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    static String script() throws IOException {
        return new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
    }

    static Set<String> usedKeys(String code) {
        Set<String> keys = new TreeSet<>();
        Matcher m = CALL.matcher(code);
        while (m.find()) {
            keys.add(m.group(2));
        }
        return keys;
    }

    @Test
    @DisplayName("every key mgmt.js asks for exists as js.<key> in the common bundle")
    void everyKeyExists() throws IOException {
        Properties common = MgmtText.load("i18n/common.properties");
        Set<String> used = usedKeys(withoutComments(script()));
        assertThat(used).as("t() calls found in mgmt.js").isNotEmpty();
        List<String> missing = new ArrayList<>();
        for (String key : used) {
            if (common.getProperty("js." + key) == null) {
                missing.add("js." + key);
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("every js.* key in the bundles is used by mgmt.js")
    void noDeadKeys() throws IOException {
        Set<String> used = usedKeys(withoutComments(script()));
        List<String> dead = new ArrayList<>();
        for (Properties bundle : MgmtText.baseBundles()) {
            for (String key : bundle.stringPropertyNames()) {
                if (key.startsWith("js.") && !used.contains(key.substring(3))) {
                    dead.add(key);
                }
            }
        }
        assertThat(dead).isEmpty();
    }

    @Test
    @DisplayName("mgmt.js has no Cyrillic outside comments")
    void noCyrillicOutsideComments() throws IOException {
        List<String> offending = new ArrayList<>();
        String[] lines = withoutComments(script()).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (CYRILLIC.matcher(lines[i]).find()) {
                offending.add((i + 1) + ": " + lines[i].trim());
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    @DisplayName("the TinyMCE language packs MgmtText can name are vendored")
    void editorLanguagePacksAreVendored() {
        for (String pack : List.of("bg-BG", "de", "ru")) {
            assertThat(new ClassPathResource("static/mgmt-assets/tinymce/langs/" + pack + ".js").exists())
                    .as("tinymce/langs/%s.js", pack)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the comment stripper keeps strings, URLs and regexes and drops only comments")
    void stripperIsNotFooledByStrings() {
        String code = "var a = 'http://x/*y*/'; // коментар\n"
                + "/* блок */ var b = \"//\" + `a//b`; var r = /\\/\\//g;\n"
                + "var c = 'Кирилица';";
        String stripped = withoutComments(code);
        assertThat(stripped).contains("'http://x/*y*/'", "\"//\"", "`a//b`", "/\\/\\//g", "'Кирилица'");
        assertThat(stripped).doesNotContain("коментар", "блок");
        // Line numbers survive, so a failure points at the right line.
        assertThat(stripped.split("\n", -1)).hasSize(3);
    }

    /**
     * Removes {@code //} and {@code /* *}{@code /} comments, leaving strings, template literals and
     * regex literals untouched -- the script is full of URLs, and a naive {@code //} strip would cut
     * every one of them in half. Newlines inside comments are kept so line numbers stay right.
     */
    static String withoutComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        char lastSignificant = ';';
        while (i < n) {
            char c = code.charAt(i);
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = code.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) {
                    if (code.charAt(k) == '\n') {
                        out.append('\n');
                    }
                }
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int start = i++;
                while (i < n && code.charAt(i) != c) {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(n, i + 1);
                out.append(code, start, i);
                lastSignificant = c;
            } else if (c == '/' && "(,=:[!&|?{};+-*%<>~^".indexOf(lastSignificant) >= 0) {
                // A regex literal: only where an operand may start, so `a / b` stays division.
                int start = i++;
                boolean inClass = false;
                while (i < n && code.charAt(i) != '\n') {
                    char r = code.charAt(i);
                    if (r == '\\') {
                        i += 2;
                        continue;
                    }
                    if (r == '[') {
                        inClass = true;
                    } else if (r == ']') {
                        inClass = false;
                    } else if (r == '/' && !inClass) {
                        break;
                    }
                    i++;
                }
                i = Math.min(n, i + 1);
                out.append(code, start, i);
                lastSignificant = '/';
            } else {
                out.append(c);
                if (!Character.isWhitespace(c)) {
                    lastSignificant = c;
                }
                i++;
            }
        }
        return out.toString();
    }
}
