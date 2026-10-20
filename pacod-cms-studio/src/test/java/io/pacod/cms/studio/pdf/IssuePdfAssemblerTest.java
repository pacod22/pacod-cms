package io.pacod.cms.studio.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** On PDFs made here, blank pages of A4: what is merged, bookmarked and numbered. */
class IssuePdfAssemblerTest {

    @TempDir
    Path work;

    static Path blank(Path file, int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(file.toFile());
        }
        return file;
    }

    @Test
    @DisplayName("pages are counted; the merge keeps the order, bookmarks each article and numbers every page but the cover")
    void assemble() throws IOException {
        Path front = blank(work.resolve("front.pdf"), 2);
        Path first = blank(work.resolve("a.pdf"), 3);
        Path second = blank(work.resolve("b.pdf"), 1);
        assertThat(IssuePdfAssembler.pages(first)).isEqualTo(3);

        Path target = work.resolve("issue.pdf");
        IssuePdfAssembler.assemble(front, List.of(
                new IssuePdfAssembler.Part(first, "Първа статия", 3),
                new IssuePdfAssembler.Part(second, "Second", 6)), "Journal · 1 (1)", 0.4, target);

        try (PDDocument document = Loader.loadPDF(target.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(6);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Journal · 1 (1)");

            List<String> titles = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            for (PDOutlineItem item : document.getDocumentCatalog().getDocumentOutline().children()) {
                titles.add(item.getTitle());
                pages.add(document.getPages().indexOf(item.findDestinationPage(document)) + 1);
            }
            assertThat(titles).containsExactly("Първа статия", "Second");
            assertThat(pages).containsExactly(3, 6);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            assertThat(stripper.getText(document).strip()).isEmpty();
            for (int page = 2; page <= 6; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                assertThat(stripper.getText(document).strip()).isEqualTo(Integer.toString(page));
            }
        }
        // Nothing left beside it.
        assertThat(work.resolve("issue.pdf.numbered")).doesNotExist();
    }

    @Test
    @DisplayName("each article starts after the front and every article before it")
    void starts() {
        assertThat(PdfIssueHandler.starts(2, List.of(3, 1, 4))).containsExactly(3, 6, 7);
        assertThat(PdfIssueHandler.starts(0, List.of(2))).containsExactly(1);
    }
}
