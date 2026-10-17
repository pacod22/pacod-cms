package io.pacod.cms.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Bulgarian string literal is left in the console's Java: every flash message, validation
 * message and exception text is a bundle key.
 *
 * <p>The render tests cannot see a missed one. A literal reaches the screen as a flash or an error
 * line, in Bulgarian, on an English console -- and the stubbed data those tests render is Bulgarian
 * too, so nothing on the page distinguishes it from an article title. Reading the source is the only
 * place the difference shows. Comments and javadoc may stay Bulgarian.
 */
class MgmtJavaTextTest {

    private static final Path SOURCES = Path.of("src/main/java/io/pacod/cms/studio");

    /**
     * Files allowed to hold Cyrillic literals, and why. {@code MgmtText} carries each language's own
     * name for the switch -- "Русский" is Russian in every interface language.
     */
    private static final Set<String> ALLOWED = Set.of("MgmtText.java");

    /**
     * Bulgarian literals that are <i>stored data</i>, not interface text, and so must not follow the
     * operator's language: the comment written into a
     * generated banner stylesheet, and the reason stored on a job cancelled before it ran, which
     * the runner's own reasons sit beside in the same field.
     *
     * <p>{@code MonitoringRange}'s constructor labels are the {@code getLabel()} fallback that
     * {@code MgmtText.label} reaches only for a missing key, the same role the core enums' labels
     * play; {@code MgmtEnumTextTest} is what keeps the keys present.
     */
    private static final Set<String> STORED_DATA = Set.of(
            "MgmtBannerSkeletonService.java: \"От \"",
            "BannerLayoutStorage.java: \"/* Размери на макета, зададени от конзолата. */\"",
            "MonitoringRange.java: \"Последните 24 часа\"",
            "MonitoringRange.java: \"Последните 7 дни\"",
            "MonitoringRange.java: \"Последните 30 дни\"",
            "MonitoringRange.java: \"Последните 12 месеца\"");

    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    @Test
    @DisplayName("no Cyrillic string literal in the console's Java, outside comments")
    void noCyrillicLiterals() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (ALLOWED.contains(file.getFileName().toString())) {
                    continue;
                }
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = STRING.matcher(code);
                while (m.find()) {
                    String entry = file.getFileName() + ": " + m.group();
                    if (CYRILLIC.matcher(m.group()).find() && !STORED_DATA.contains(entry)) {
                        found.add(entry);
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }

    /** Strips block and line comments, leaving string literals (which may contain //) intact. */
    static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                if (source.startsWith("\"\"\"", i)) {
                    int end = source.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? n : end + 3;
                    out.append(source, i, end);
                    i = end;
                    continue;
                }
                int j = i + 1;
                while (j < n && source.charAt(j) != '"' && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(source, i, j);
                i = j;
            } else if (c == '\'' ) {
                int j = i + 1;
                while (j < n && source.charAt(j) != '\'' && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(source, i, j);
                i = j;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
