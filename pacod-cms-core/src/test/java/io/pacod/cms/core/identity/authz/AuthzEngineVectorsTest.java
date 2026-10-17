package io.pacod.cms.core.identity.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java port of pacod-id's rules engine against pacod-id's own vectors, vendored under
 * {@code authz/engine-vectors/} (see VENDORED.md there). Every port must give the same answers;
 * a failure here is this port being wrong, never the vectors.
 */
class AuthzEngineVectorsTest {

    static final String DIR = "authz/engine-vectors/";

    static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestFactory
    @DisplayName("every check of every vector file holds")
    Stream<DynamicTest> vectorFiles() {
        return Stream.of("evaluation.yaml", "grants.yaml", "protected.yaml").map(file -> DynamicTest.dynamicTest(file, () -> {
            VectorRunner.Result result = VectorRunner.run(read(DIR + file),
                    name -> Manifest.fromYaml(read(DIR + "manifests/" + name + ".yaml")));
            assertThat(result.failures()).isEmpty();
            assertThat(result.checks()).isGreaterThan(5);
        }));
    }

    @TestFactory
    @DisplayName("every broken manifest is refused with its code")
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> manifestErrors() {
        Map<String, Object> file = (Map<String, Object>) Yaml12.load(read(DIR + "manifest-errors.yaml"));
        return ((List<Map<String, Object>>) file.get("cases")).stream().map(c -> DynamicTest.dynamicTest(
                (String) c.get("name"), () -> assertThatThrownBy(() -> Manifest.fromSource(c.get("manifest")))
                        .isInstanceOfSatisfying(ManifestException.class,
                                e -> assertThat(e.code()).isEqualTo(c.get("expect")))));
    }

    @Test
    @DisplayName("YAML 1.1 booleans are strings: implicit: yes is refused, not read as true")
    void yesIsNotABoolean() {
        assertThatThrownBy(() -> Manifest.fromYaml("""
                product: p
                roles: { user: { at: [platform], permissions: [x.y], implicit: yes } }
                grant: { default: x.y }
                """)).isInstanceOfSatisfying(ManifestException.class, e -> assertThat(e.code()).isEqualTo("bad_manifest"));
    }

    @Test
    @DisplayName("text that is not YAML is a bad manifest")
    void notYaml() {
        assertThatThrownBy(() -> Manifest.fromYaml("product: [unclosed"))
                .isInstanceOfSatisfying(ManifestException.class, e -> assertThat(e.code()).isEqualTo("bad_manifest"));
    }
}
