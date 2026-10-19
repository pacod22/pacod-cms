package io.pacod.cms.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * One language's generated PDF of a {@link JournalIssue}: when, how long and how it was made.
 * Written by the issue job alone, as {@code renditions.<lang>}, never by the studio's form.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IssueRendition {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime generatedOn;

    private int pages;

    private PdfFormat format;

    private PdfMode mode;

    private String jobId;

    private long bytes;
}
