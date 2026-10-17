package io.pacod.cms.studio;

import io.pacod.cms.studio.i18n.MgmtText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The studio's default UI language is configuration, and only a language it is offered in. */
class MgmtDefaultLanguageTest {

    @Test
    @DisplayName("the base bundles are English, and English is first in the language list")
    void englishIsTheBase() {
        assertThat(MgmtText.BASE_LANGUAGE).isEqualTo("en");
        assertThat(MgmtText.LANGUAGES.getFirst().code()).isEqualTo("en");
        assertThat(MgmtText.base("chrome.close")).isEqualTo("Close");
    }

    @Test
    @DisplayName("a default the studio is not offered in is refused, and the previous one kept")
    void refusesUnofferedDefaults() {
        String before = MgmtText.defaultLanguage();
        assertThatThrownBy(() -> MgmtText.useDefaultLanguage("fr")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MgmtText.useDefaultLanguage(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(MgmtText.defaultLanguage()).isEqualTo(before);
    }
}
