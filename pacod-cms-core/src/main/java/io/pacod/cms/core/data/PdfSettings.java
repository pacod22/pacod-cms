package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A website's PDF defaults, edited at {@code /mgmt/pdf}: the format and mode a job uses when it
 * names none, the paper, the journal's name and ISSN for running heads and issue covers, and what
 * the AI gateway is asked for. One document, id {@value #ID}; absent means every default below.
 *
 * <p>Read by the studio's PDF jobs when a job is queued (so a queued job keeps the values it was
 * queued with) and by the site's issue front page. No job writes it, so the screen saves it whole.
 */
@Data
@Document(collection = PdfSettings.COLLECTION)
public class PdfSettings {

    public static final String COLLECTION = "pdfSettings";
    public static final String ID = "pdf";

    public static final int MIN_MARGIN_MM = 10;
    public static final int MAX_MARGIN_MM = 30;
    public static final int MIN_KEYWORDS = 3;
    public static final int MAX_KEYWORDS = 10;

    /** Four digits, a hyphen, three digits and a check digit or {@code X}. */
    public static final Pattern ISSN = Pattern.compile("\\d{4}-\\d{3}[\\dX]");

    @Id
    private String id = ID;

    private PdfFormat defaultFormat = PdfFormat.STANDARD;

    private PdfMode defaultMode = PdfMode.CODE;

    private PaperSize paperSize = PaperSize.A4;

    /** Every side's margin; read through {@link #marginInches()}, which clamps it. */
    private int marginMm = 15;

    /** The journal's name per language, for running heads and issue covers; the website's name while absent. */
    private List<TextItem> journalNames = new ArrayList<>();

    /** Printed on issue covers when set. */
    private String issn;

    /** A running head on single-article scientific PDFs. */
    private boolean runningHeader = true;

    /** Page numbers in the footer of single-article PDFs. */
    private boolean pageNumbers = true;

    /** Whether the gateway is asked for an abstract in {@link PdfMode#LLM}. */
    private boolean llmAbstract = true;

    /** Whether the gateway is asked for keywords in {@link PdfMode#LLM}. */
    private boolean llmKeywords = true;

    private int maxKeywords = 6;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    public String journalName(List<String> chain) {
        return TextItems.resolve(journalNames, chain);
    }

    public String rawJournalName(String lang) {
        return TextItems.find(journalNames, lang);
    }

    /** {@link #marginMm} clamped to its bounds, in inches. */
    public double marginInches() {
        return Math.clamp(marginMm, MIN_MARGIN_MM, MAX_MARGIN_MM) / 25.4;
    }

    /** {@link #maxKeywords} clamped to its bounds, whatever was written by hand. */
    public int keywordLimit() {
        return Math.clamp(maxKeywords, MIN_KEYWORDS, MAX_KEYWORDS);
    }

    /** Never null, for a document written without the field. */
    public PdfFormat format() {
        return defaultFormat == null ? PdfFormat.STANDARD : defaultFormat;
    }

    /** Never null, for a document written without the field. */
    public PdfMode mode() {
        return defaultMode == null ? PdfMode.CODE : defaultMode;
    }

    /** Never null, for a document written without the field. */
    public PaperSize paper() {
        return paperSize == null ? PaperSize.A4 : paperSize;
    }

    /** {@link #issn} when it has the ISSN's shape, else {@code null}: never printed malformed. */
    public String validIssn() {
        return issn != null && ISSN.matcher(issn.strip()).matches() ? issn.strip() : null;
    }
}
