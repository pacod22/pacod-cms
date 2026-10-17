package io.pacod.cms.core.design;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A website's theme: values for the {@link ThemeToken}s, and CSS of its own -- one document, id
 * {@value #ID}. Like a layout, a draft the studio edits and previews and a published copy readers
 * see. Only what is set overrides the template set's own stylesheet; nothing set, nothing served.
 *
 * <p>Stored per website, not per template set: the vocabulary is shared, so a theme survives a
 * switch of sets.
 */
@Data
@Document(collection = "theme")
public class Theme {

    public static final String ID = "theme";

    @Id
    private String id = ID;

    /** The set's preset last applied to the draft, for the studio to say so; nothing reads it. */
    private String preset;

    private Map<String, String> draft = new LinkedHashMap<>();
    private Map<String, String> published = new LinkedHashMap<>();
    private String draftCss;
    private String publishedCss;

    private Instant updatedOn;
    private String updatedBy;
    private Instant publishedOn;
    private String publishedBy;

    public boolean hasUnpublishedChanges() {
        return !java.util.Objects.equals(draft, published) || !java.util.Objects.equals(draftCss, publishedCss);
    }
}
