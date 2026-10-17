package io.pacod.cms.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's templates, read as files: every {@code #{key}} they name exists, and no Bulgarian
 * is left in them outside comments.
 *
 * <p>Thymeleaf renders a missing key as {@code ??key_en??} with a 200, and a Bulgarian word left in
 * a {@code title=} renders into an English screen with a 200 too; neither fails anything else.
 * Prototype text inside an element that carries {@code th:text} is replaced at render time, but it
 * is still not allowed to be Cyrillic -- one rule is easier to keep than one with an exception.
 */
class MgmtTemplateTextTest {

    private static final Pattern KEY = Pattern.compile("#\\{([A-Za-z][A-Za-z0-9_.-]*)[}(]");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    static List<Resource> templates() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> all = new java.util.ArrayList<>(List.of(resolver.getResources("classpath:/templates/mgmt/*.html")));
        // The error page sits at the root, where BasicErrorController looks for the view `error`.
        all.add(resolver.getResource("classpath:/templates/error.html"));
        return all;
    }

    static String read(Resource r) throws IOException {
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    /** Removes HTML comments, block comments and line comments, which may stay Bulgarian. */
    static String withoutComments(String html) {
        String s = html.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)(^|[\\s;{}])//[^\\n]*", "$1");
        return s;
    }

    @Test
    @DisplayName("every #{key} a template names exists in the bundles")
    void everyKeyExists() throws IOException {
        Set<String> known = MgmtBundleTest.allKeys();
        List<String> missing = new ArrayList<>();
        for (Resource r : templates()) {
            Matcher m = KEY.matcher(withoutComments(read(r)));
            while (m.find()) {
                if (!known.contains(m.group(1))) {
                    missing.add(r.getFilename() + ": " + m.group(1));
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("no Bulgarian is left in a template, outside comments")
    void noCyrillicOutsideComments() throws IOException {
        List<String> found = new ArrayList<>();
        for (Resource r : templates()) {
            String[] lines = withoutComments(read(r)).split("\n");
            for (String line : lines) {
                if (CYRILLIC.matcher(line).find()) {
                    found.add(r.getFilename() + ": " + line.strip());
                }
            }
        }
        assertThat(found).isEmpty();
    }

}
