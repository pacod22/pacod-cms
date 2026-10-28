package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeBlocksTest {

    @Test
    @DisplayName("a language class names a language: lowercase, digits and + # -, at most 24 of them")
    void languageClasses() {
        assertThat(CodeBlocks.isLanguageClass("language-java")).isTrue();
        assertThat(CodeBlocks.isLanguageClass("language-c++")).isTrue();
        assertThat(CodeBlocks.isLanguageClass("language-c#")).isTrue();
        assertThat(CodeBlocks.isLanguageClass("language-objective-c")).isTrue();
        assertThat(CodeBlocks.isLanguageClass("language-" + "a".repeat(24))).isTrue();

        assertThat(CodeBlocks.isLanguageClass("language-" + "a".repeat(25))).isFalse();
        assertThat(CodeBlocks.isLanguageClass("language-")).isFalse();
        assertThat(CodeBlocks.isLanguageClass("language-Java")).isFalse();
        assertThat(CodeBlocks.isLanguageClass("language-java x")).isFalse();
        assertThat(CodeBlocks.isLanguageClass("lang-java")).isFalse();
        assertThat(CodeBlocks.isLanguageClass("body-callout")).isFalse();
        assertThat(CodeBlocks.isLanguageClass("")).isFalse();
        assertThat(CodeBlocks.isLanguageClass(null)).isFalse();
    }

    @Test
    @DisplayName("only a pre or a code carries one")
    void elements() {
        assertThat(CodeBlocks.allowed("pre", "language-rust")).isTrue();
        assertThat(CodeBlocks.allowed("code", "language-rust")).isTrue();
        assertThat(CodeBlocks.allowed("p", "language-rust")).isFalse();
        assertThat(CodeBlocks.allowed("div", "language-rust")).isFalse();
    }
}
