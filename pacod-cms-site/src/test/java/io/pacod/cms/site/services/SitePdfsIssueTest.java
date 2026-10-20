package io.pacod.cms.site.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** TestTenantExtension binds {@code test}. */
class SitePdfsIssueTest {

    @TempDir
    Path storage;

    @Test
    @DisplayName("an issue's PDF is linked once its public file exists, never its private one")
    void linkedOnceItExists() throws Exception {
        SitePdfs pdfs = new SitePdfs(storage.toString());
        assertThat(pdfs.issue(3, "bg")).isEmpty();

        Path draft = storage.resolve("sites/test/private/pdfs/issues/bg/issue-3.pdf");
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, "%PDF");
        assertThat(pdfs.issue(3, "bg")).isEmpty();

        Path published = storage.resolve("sites/test/resources/pdfs/issues/bg/issue-3.pdf");
        Files.createDirectories(published.getParent());
        Files.writeString(published, "%PDF");
        assertThat(pdfs.issue(3, "bg")).contains("/resources/pdfs/issues/bg/issue-3.pdf");
        assertThat(pdfs.issue(3, "../bg")).isEmpty();
        assertThat(pdfs.issue(null, "bg")).isEmpty();
    }
}
