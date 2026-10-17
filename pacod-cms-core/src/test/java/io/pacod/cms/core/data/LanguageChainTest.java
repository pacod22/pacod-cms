package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order pages are resolved in.
 *
 * <p>Worth its own test because every way of getting it wrong is silent: a page served in the
 * wrong language still renders, still returns 200, and reads perfectly well to anyone who happens
 * to speak the language it landed on.
 */
class LanguageChainTest {

    // The module's OriginalLanguageExtension makes Bulgarian the website's own language for every
    // test, which is what the cases below that name "bg" exercise.

    @Test
    @DisplayName("a reader who asked gets their language, then English, then Bulgarian")
    void requestedFirstThenEnglishThenBulgarian() {
        assertThat(LanguageChain.forRequest("de")).containsExactly("de", "en", "bg");
    }

    @Test
    @DisplayName("no duplicates when the reader asked for one of the fallbacks")
    void chainIsDeduplicated() {
        assertThat(LanguageChain.forRequest("en")).containsExactly("en", "bg");
        assertThat(LanguageChain.forRequest("bg")).containsExactly("bg", "en");
    }

    @Test
    @DisplayName("a reader who asked for NOTHING gets Bulgarian first, not English")
    void noPreferenceMeansBulgarianFirst() {
        // The order flips here, and it is not a detail: English is the courtesy extended to
        // someone who asked for a language we do not have -- not the default for someone who asked
        // for nothing, who is owed the website's own language. The console's page list resolves
        // titles through this path; with English first it would show every page under its English
        // title.
        assertThat(LanguageChain.forRequest(null)).containsExactly("bg", "en");
        assertThat(LanguageChain.forRequest("  ")).containsExactly("bg", "en");
    }

    @Test
    @DisplayName("region-qualified codes reduce to the bare subtag")
    void regionQualifiedCodesNormalise() {
        // CookieLocaleResolver serialises a whole Locale, so the lang cookie really does hold
        // values like en-US.
        assertThat(LanguageChain.normalise("en-US")).isEqualTo("en");
        assertThat(LanguageChain.normalise("pt_BR")).isEqualTo("pt");
        assertThat(LanguageChain.normalise(" DE ")).isEqualTo("de");
        assertThat(LanguageChain.normalise(null)).isNull();
        assertThat(LanguageChain.normalise("")).isNull();
        assertThat(LanguageChain.forRequest("en-US")).containsExactly("en", "bg");
    }

    @Test
    @DisplayName("out of the box the website's own language is English, and the chain collapses")
    void defaultsToEnglish() {
        String previous = LanguageChain.useOriginal(LanguageChain.DEFAULT_ORIGINAL);
        try {
            assertThat(LanguageChain.original()).isEqualTo("en");
            assertThat(LanguageChain.forRequest(null)).containsExactly("en");
            assertThat(LanguageChain.forRequest("de")).containsExactly("de", "en");
        } finally {
            LanguageChain.useOriginal(previous);
        }
    }

    @Test
    @DisplayName("the original is configurable, and normalised like a request")
    void originalIsConfigurable() {
        String previous = LanguageChain.useOriginal("FR-ca");
        try {
            assertThat(LanguageChain.original()).isEqualTo("fr");
            assertThat(LanguageChain.forRequest("de")).containsExactly("de", "en", "fr");
            assertThat(LanguageChain.forRequest(null)).containsExactly("fr", "en");
        } finally {
            LanguageChain.useOriginal(previous);
        }
    }

    @Test
    @DisplayName("a value that is not a language code is refused rather than resolved against")
    void refusesNonCodes() {
        for (String bad : new String[]{null, "", "  ", "english", "e1", "../x"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> LanguageChain.useOriginal(bad))
                    .as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(LanguageChain.original()).isEqualTo("bg");
    }
}
