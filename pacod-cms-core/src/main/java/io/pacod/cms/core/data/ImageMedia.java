package io.pacod.cms.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(value="image")
@NoArgsConstructor
@AllArgsConstructor
public class ImageMedia {
    @Id
    private Integer id;
    private String caption;

    /**
     * The alternative text, per language: what the image shows, for a reader who cannot see it.
     * Absent in a language, the chain's next one stands in, then the caption ({@link #altFor}).
     */
    private List<TextItem> alts;
    private String content;
    private String source;
    @Indexed(unique = true)
    private String filename;

    @Transient
    private byte[] media;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    public ImageMedia(Integer id) {

        this.id = id;
        createdOn = LocalDateTime.now();
        lastModifiedOn = LocalDateTime.now();
    }

    public void setCaption(String caption) {
        this.caption = caption;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setContent(String content) {
        this.content = content;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setSource(String source) {
        this.source = source;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setFilename(String filename) {
        this.filename = filename;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setMedia(byte[] media) {
        this.media = media;
        lastModifiedOn = LocalDateTime.now();
    }

    /** The alt text for a reader asking for {@code lang}: the chain's first, else the caption, else "". */
    public String altFor(String lang) {
        String alt = TextItems.resolve(alts, LanguageChain.forRequest(lang));
        if (alt != null && !alt.isBlank()) {
            return alt;
        }
        return caption == null ? "" : caption;
    }
}
