package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * One crawler file's choice, embedded in {@link SiteFiles}. {@link #custom} is kept whatever the
 * mode, so an operator who goes back to automatic and then to custom finds their text again.
 */
@Data
public class SiteFileSetting {

    private SiteFileMode mode = SiteFileMode.AUTO;

    /** Lines merged into the generated file in {@link SiteFileMode#AUTO_PLUS}. */
    private String additions;

    /** Paths left out of the generated file in {@link SiteFileMode#AUTO_PLUS}; one per line. */
    private String exclusions;

    /** Served verbatim in {@link SiteFileMode#CUSTOM}. */
    private String custom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    /** The {@code usr_…} id of who saved it last. */
    private String updatedBy;

    /** Never null, for a document written without the field. */
    public SiteFileMode mode() {
        return mode == null ? SiteFileMode.AUTO : mode;
    }
}
