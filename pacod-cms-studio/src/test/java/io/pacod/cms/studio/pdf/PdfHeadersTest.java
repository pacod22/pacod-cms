package io.pacod.cms.studio.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfHeadersTest {

    @Test
    @DisplayName("every value is escaped, and the document carries its own size and fonts")
    void escapes() {
        String header = PdfHeaders.line("Journal <b>&</b>", "\"Title\" <script>x</script>", 0.6);
        assertThat(header).startsWith("<!DOCTYPE html>")
                .contains("Journal &lt;b&gt;&amp;&lt;/b&gt;")
                .contains("&quot;Title&quot; &lt;script&gt;")
                .doesNotContain("<script>")
                .contains("font-size:8pt").contains("Noto Sans").contains("padding:0 0.6in");
        assertThat(PdfHeaders.pageFooter("J", 0.8)).contains("class=\"pageNumber\"", "class=\"totalPages\"", "padding:0 0.8in");
    }

    @Test
    @DisplayName("an issue's line is data and digits, the month in the PDF's language")
    void issueLine() {
        assertThat(PdfHeaders.issueLine("Демо", 3, 2, 2026, 10, "bg")).isEqualTo("Демо · 3 (2) · октомври 2026");
        assertThat(PdfHeaders.issueLine(" ", 1, 1, 2026, 1, "de")).isEqualTo("1 (1) · Januar 2026");
        assertThat(PdfHeaders.monthName(13, "en")).isEmpty();
    }
}
