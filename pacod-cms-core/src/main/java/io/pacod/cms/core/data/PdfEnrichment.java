package io.pacod.cms.core.data;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * What the AI gateway wrote for one article in one language, for its PDF in {@link PdfMode#LLM}:
 * an abstract, keywords and a running title. The body is never here -- it is laid out as the
 * author wrote it.
 *
 * <h2>A document of its own, never a field of {@link Article}</h2>
 * {@code Article} is serialised raw by {@code /web/v1} and saved whole by the studio's editor; a
 * field there would be published and could be overwritten by an editor's stale form. The id is
 * {@link #key}, so writing is one whole-document upsert of a freshly built object -- nothing read
 * and then modified around a call that takes seconds, and a rerun job writes the same row.
 *
 * <h2>{@link #sourceHash} ties it to the text it was written from</h2>
 * The studio skips the call while the hash still matches; the site ignores a row whose hash no
 * longer matches the article, so an edited article never prints an abstract of its old text.
 */
@Data
@NoArgsConstructor
@Document(collection = PdfEnrichment.COLLECTION)
public class PdfEnrichment {

    public static final String COLLECTION = "pdfEnrichment";

    @Id
    private String id;

    @Indexed(name = "article_idx")
    private Integer articleId;

    private String lang;

    private String sourceHash;

    private String abstractText;

    private List<String> keywords = new ArrayList<>();

    private String runningTitle;

    private String model;

    private String jobId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime generatedOn;

    /** The id of an article's row in one language: {@code "12:bg"}. */
    public static String key(int articleId, String lang) {
        if (lang == null || !lang.matches("[a-z]{2,3}")) {
            throw new IllegalArgumentException("Not a language code: " + lang);
        }
        return articleId + ":" + lang;
    }

    /**
     * SHA-256 of the title and the body, in hex: what the row was written from. Core's, so the
     * studio and the site compute it alike. Nulls count as empty.
     */
    public static String sourceHash(String title, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((title == null ? "" : title).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether this row was written from exactly this title and body. */
    public boolean matches(String title, String text) {
        return sourceHash != null && sourceHash.equals(sourceHash(title, text));
    }

    /** Never null, for a document written without the field. */
    public List<String> getKeywords() {
        return keywords == null ? List.of() : keywords;
    }
}
