package io.pacod.cms.studio.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A journal issue's PDF, put together from the pieces Gotenberg rendered: the front matter (cover,
 * editorial, contents) and each article. Pure PDFBox, no Spring, so a test drives it with PDFs it
 * makes itself.
 *
 * <ul>
 *   <li>{@link #pages} counts a piece's pages: the contents need each article's first page.</li>
 *   <li>{@link #assemble} merges the pieces in order, adds a bookmark per article at its first
 *       page, names the document, and stamps the page number at the foot of every page but the
 *       cover.</li>
 * </ul>
 *
 * <p><b>Only digits are drawn here</b>, in the Standard 14 Helvetica, which needs no font file and
 * no system font -- and covers no Cyrillic. Every word of the issue (titles, running heads,
 * the contents) is laid out by Chromium; bookmark titles are PDF text strings and carry any
 * script without a font.
 */
public final class IssuePdfAssembler {

    /** One article's piece: its file, its bookmark's title, and the page it starts on. */
    public record Part(Path file, String title, int firstPage) {
    }

    private IssuePdfAssembler() {
    }

    /** How many pages the PDF at {@code file} has. */
    public static int pages(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return document.getNumberOfPages();
        }
    }

    /**
     * Front matter and articles merged into {@code target}, bookmarked, titled and numbered.
     *
     * @param numberBottomInches how far above the paper's bottom edge the page number sits
     */
    public static void assemble(Path front, List<Part> parts, String title, double numberBottomInches,
                                Path target) throws IOException {
        PDFMergerUtility merger = new PDFMergerUtility();
        merger.addSource(front.toFile());
        for (Part part : parts) {
            merger.addSource(part.file().toFile());
        }
        merger.setDestinationFileName(target.toString());
        merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());

        Path finished = target.resolveSibling(target.getFileName() + ".numbered");
        try (PDDocument document = Loader.loadPDF(target.toFile())) {
            bookmark(document, parts);
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(title);
            stamp(document, numberBottomInches);
            document.save(finished.toFile());
        }
        Files.move(finished, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void bookmark(PDDocument document, List<Part> parts) {
        PDDocumentOutline outline = new PDDocumentOutline();
        document.getDocumentCatalog().setDocumentOutline(outline);
        for (Part part : parts) {
            int index = part.firstPage() - 1;
            if (index < 0 || index >= document.getNumberOfPages()) {
                continue;
            }
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(document.getPage(index));
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(part.title());
            item.setDestination(destination);
            outline.addLast(item);
        }
        outline.openNode();
    }

    /** The page number, centred at the foot of every page but the first (the cover). */
    private static void stamp(PDDocument document, double bottomInches) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float size = 9;
        for (int i = 1; i < document.getNumberOfPages(); i++) {
            PDPage page = document.getPage(i);
            PDRectangle box = page.getCropBox();
            String number = Integer.toString(i + 1);
            float width = font.getStringWidth(number) / 1000 * size;
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.beginText();
                stream.setFont(font, size);
                stream.setNonStrokingColor(0.35f, 0.35f, 0.35f);
                stream.newLineAtOffset(box.getLowerLeftX() + (box.getWidth() - width) / 2,
                        box.getLowerLeftY() + (float) (bottomInches * 72));
                stream.showText(number);
                stream.endText();
            }
        }
    }
}
