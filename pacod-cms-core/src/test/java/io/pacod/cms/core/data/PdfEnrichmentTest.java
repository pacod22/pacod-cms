package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfEnrichmentTest {

    @Test
    @DisplayName("the key is the article and a language code, nothing else")
    void key() {
        assertThat(PdfEnrichment.key(12, "bg")).isEqualTo("12:bg");
        assertThatThrownBy(() -> PdfEnrichment.key(12, "../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PdfEnrichment.key(12, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the hash is stable, separates title from body and follows any edit")
    void sourceHash() {
        String hash = PdfEnrichment.sourceHash("Заглавие", "<p>Тяло</p>");
        assertThat(hash).hasSize(64).isEqualTo(PdfEnrichment.sourceHash("Заглавие", "<p>Тяло</p>"));
        assertThat(PdfEnrichment.sourceHash("ab", "c")).isNotEqualTo(PdfEnrichment.sourceHash("a", "bc"));
        assertThat(PdfEnrichment.sourceHash(null, null)).isEqualTo(PdfEnrichment.sourceHash("", ""));

        PdfEnrichment row = new PdfEnrichment();
        row.setSourceHash(hash);
        assertThat(row.matches("Заглавие", "<p>Тяло</p>")).isTrue();
        assertThat(row.matches("Заглавие", "<p>Тяло.</p>")).isFalse();
        assertThat(new PdfEnrichment().matches("", "")).isFalse();
    }

    @Test
    @DisplayName("the enums read their parameters case-insensitively and fall back on anything else")
    void enumsParse() {
        assertThat(PdfFormat.parse("Scientific", PdfFormat.STANDARD)).isEqualTo(PdfFormat.SCIENTIFIC);
        assertThat(PdfFormat.parse("sheet", PdfFormat.STANDARD)).isEqualTo(PdfFormat.STANDARD);
        assertThat(PdfFormat.parse(null, null)).isNull();
        assertThat(PdfMode.parse(" llm ", PdfMode.CODE)).isEqualTo(PdfMode.LLM);
        assertThat(PdfMode.LLM.param()).isEqualTo("llm");
    }

    @Test
    @DisplayName("PDF settings clamp what was written by hand and print only a well-formed ISSN")
    void settingsClamp() {
        PdfSettings settings = new PdfSettings();
        settings.setMarginMm(200);
        settings.setMaxKeywords(0);
        settings.setIssn("1234-567X");
        assertThat(settings.marginInches()).isEqualTo(30 / 25.4);
        assertThat(settings.keywordLimit()).isEqualTo(PdfSettings.MIN_KEYWORDS);
        assertThat(settings.validIssn()).isEqualTo("1234-567X");
        settings.setIssn("ISSN 1234");
        assertThat(settings.validIssn()).isNull();
        settings.setDefaultFormat(null);
        assertThat(settings.format()).isEqualTo(PdfFormat.STANDARD);
    }
}
