package io.pacod.cms.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteStorageTest {

    @Test
    @DisplayName("article and published issue PDFs are public paths; draft issues are not")
    void pdfPaths() {
        assertThat(SiteStorage.articlePdf(5, "bg")).isEqualTo("resources/pdfs/articles/bg/article-5.pdf");
        assertThat(SiteStorage.issuePdf(3, "en")).isEqualTo("resources/pdfs/issues/en/issue-3.pdf");
        // MediaController serves assets/ and resources/ only.
        assertThat(SiteStorage.issueDraftPdf(3, "en")).isEqualTo("private/pdfs/issues/en/issue-3.pdf")
                .doesNotStartWith("resources/").doesNotStartWith("assets/");
    }

    @Test
    @DisplayName("a language that is not a bare code never becomes part of a path")
    void refusesWhatIsNotACode() {
        assertThatThrownBy(() -> SiteStorage.issuePdf(3, "../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteStorage.issueDraftPdf(3, "EN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteStorage.articlePdf(3, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
