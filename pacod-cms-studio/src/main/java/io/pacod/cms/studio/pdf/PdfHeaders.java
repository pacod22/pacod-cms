package io.pacod.cms.studio.pdf;

import org.springframework.web.util.HtmlUtils;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The running headers and footers Gotenberg prints into a PDF's margins, built from data alone.
 *
 * <p>Chromium renders a header or footer apart from the page: none of the page's styles reach it,
 * its default font size is close to nothing, and it loads nothing. So each one here is a whole
 * document carrying its own size and a font family that ships in Gotenberg's image (Noto, DejaVu;
 * Cyrillic included), and every value is HTML-escaped -- a journal name or an article title is
 * whatever an editor typed.
 *
 * <p><b>No translated word appears here</b>, only names, digits and month names from the JDK: the
 * studio's bundles cover its four interface languages, while a PDF is in the content's language,
 * which may be any.
 */
public final class PdfHeaders {

    private static final String STYLE = "<style>"
            + "html,body{margin:0;padding:0;-webkit-print-color-adjust:exact;}"
            + "body{font-family:'Noto Sans','DejaVu Sans','Liberation Sans',sans-serif;font-size:8pt;color:#555;"
            + "width:100%;padding:0 0.6in;box-sizing:border-box;}"
            + ".row{display:flex;justify-content:space-between;gap:12px;width:100%;}"
            + ".l,.r{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}"
            + ".l{max-width:55%;}.r{max-width:45%;text-align:right;}"
            + "</style>";

    private PdfHeaders() {
    }

    /** A header or footer reading {@code left} at the left and {@code right} at the right. */
    public static String line(String left, String right, double sideMarginInches) {
        return document("<div class=\"row\"><span class=\"l\">" + escape(left) + "</span>"
                + "<span class=\"r\">" + escape(right) + "</span></div>", sideMarginInches);
    }

    /** A footer with {@code label} at the left and "page / pages" at the right. */
    public static String pageFooter(String label, double sideMarginInches) {
        return document("<div class=\"row\"><span class=\"l\">" + escape(label) + "</span>"
                + "<span class=\"r\"><span class=\"pageNumber\"></span> / <span class=\"totalPages\"></span></span></div>",
                sideMarginInches);
    }

    /** An issue's line: {@code Journal · 12 (3) · October 2026}, the month in the PDF's language. */
    public static String issueLine(String journal, int volume, int number, int year, int month, String lang) {
        StringBuilder line = new StringBuilder();
        if (journal != null && !journal.isBlank()) {
            line.append(journal.strip()).append(" · ");
        }
        line.append(volume).append(" (").append(number).append(") · ").append(monthName(month, lang)).append(' ').append(year);
        return line.toString();
    }

    /** The month's standalone name in {@code lang}, as the JDK spells it. */
    public static String monthName(int month, String lang) {
        if (month < 1 || month > 12) {
            return "";
        }
        return Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag(lang == null ? "en" : lang));
    }

    private static String document(String body, double sideMarginInches) {
        String style = STYLE.replace("padding:0 0.6in", "padding:0 " + GotenbergClient.inches(sideMarginInches) + "in");
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" + style + "</head><body>" + body + "</body></html>";
    }

    private static String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text.strip(), "UTF-8");
    }
}
