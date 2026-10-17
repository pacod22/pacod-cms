package io.pacod.cms.site.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
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
 * The public site's chrome bundles, {@code messages*.properties}.
 *
 * <p>Each failure here renders as a page that answers 200: a key missing from a translation
 * shows English in the middle of German chrome, a key a template uses but no bundle defines shows
 * {@code ??key_de??}, and a lone apostrophe in a value that takes arguments silently drops the
 * rest of the sentence through {@code MessageFormat}.
 */
class MessageBundleTest {

    private static final Pattern MESSAGE_EXPR = Pattern.compile("#\\{([a-zA-Z0-9.]+)[}(]");
    private static final Pattern MESSAGES_MSG = Pattern.compile("#messages\\.msg\\('([a-zA-Z0-9.]+)'");
    private static final Pattern ARGUMENT = Pattern.compile("\\{\\d+}");

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static List<String> translations() throws IOException {
        List<String> names = new ArrayList<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:messages_*.properties")) {
            names.add(r.getFilename());
        }
        return names;
    }

    @Test
    @DisplayName("every translation carries exactly the base bundle's keys")
    void translationsMatchTheBase() throws IOException {
        Set<String> base = load("messages.properties").stringPropertyNames();
        List<String> translations = translations();

        assertThat(translations).contains("messages_bg.properties", "messages_de.properties",
                "messages_fr.properties", "messages_es.properties", "messages_it.properties");
        for (String name : translations) {
            assertThat(load(name).stringPropertyNames()).as(name).isEqualTo(base);
        }
    }

    @Test
    @DisplayName("every key a public template uses is defined")
    void templateKeysExist() throws IOException {
        Set<String> base = load("messages.properties").stringPropertyNames();
        Set<String> used = new TreeSet<>();
        for (Resource template : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:templates/**/*.html")) {
            String html = template.getContentAsString(StandardCharsets.UTF_8);
            for (Pattern pattern : List.of(MESSAGE_EXPR, MESSAGES_MSG)) {
                Matcher matcher = pattern.matcher(html);
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }

        assertThat(used).isNotEmpty();
        assertThat(base).as("keys used by the templates").containsAll(used);
    }

    @Test
    @DisplayName("no value is blank, and a value with arguments doubles its apostrophes")
    void valuesAreWellFormed() throws IOException {
        List<String> names = new ArrayList<>(translations());
        names.add("messages.properties");
        for (String name : names) {
            Properties bundle = load(name);
            for (String key : bundle.stringPropertyNames()) {
                String value = bundle.getProperty(key);
                assertThat(value).as(name + " " + key).isNotBlank();
                if (ARGUMENT.matcher(value).find()) {
                    assertThat(value.replace("''", "")).as(name + " " + key + " (MessageFormat)")
                            .doesNotContain("'");
                }
            }
        }
    }
}
