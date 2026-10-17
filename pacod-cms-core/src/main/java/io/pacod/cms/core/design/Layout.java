package io.pacod.cms.core.design;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A website's design of one page type: regions, each an ordered list of sections. The id is the
 * {@link PageType}'s name.
 *
 * <p>Two copies: {@link #draft}, which the studio edits and previews, and {@link #published}, which
 * readers see. Publishing copies one onto the other. A page type with no document -- or no
 * published copy -- renders with its template set's default layout.
 *
 * <p>Regions are named by the template set (for {@code editorial}: {@code main}, {@code aside},
 * {@code wide}). Layouts are stored per page type, not per set, so switching sets keeps them; a
 * region or section type the new set lacks is flagged, never dropped ({@link LayoutResolver}).
 */
@Data
@Document(collection = "layout")
public class Layout {

    @Id
    private String id;

    private Map<String, List<Section>> draft;

    private Map<String, List<Section>> published;

    private Instant updatedOn;
    private String updatedBy;
    private Instant publishedOn;
    private String publishedBy;

    public Layout() {
    }

    public Layout(PageType pageType) {
        this.id = pageType.name();
    }

    /** Whether the draft differs from what readers see. */
    public boolean hasUnpublishedChanges() {
        return draft != null && !draft.equals(published);
    }

    /** A deep copy of {@code regions}, so an edit of one copy never shows in the other. */
    public static Map<String, List<Section>> copy(Map<String, List<Section>> regions) {
        if (regions == null) {
            return null;
        }
        Map<String, List<Section>> copy = new LinkedHashMap<>();
        regions.forEach((name, sections) -> {
            List<Section> list = new ArrayList<>();
            for (Section s : sections == null ? List.<Section>of() : sections) {
                list.add(s.copy());
            }
            copy.put(name, list);
        });
        return copy;
    }
}
