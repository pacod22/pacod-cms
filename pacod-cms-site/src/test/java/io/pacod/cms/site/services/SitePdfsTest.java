package io.pacod.cms.site.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** TestTenantExtension binds the website {@code test}: its folder is {@code sites/test}. */
class SitePdfsTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("an article's PDF is linked once the studio has made it, at the address its folder lays out")
    void linkedOnceItExists() throws Exception {
        SitePdfs pdfs = new SitePdfs(root.toString());
        assertThat(pdfs.article(12, "de")).isEmpty();

        Path file = root.resolve("sites/test/resources/pdfs/articles/de/article-12.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{'%', 'P', 'D', 'F'});

        assertThat(pdfs.article(12, "de")).contains("/resources/pdfs/articles/de/article-12.pdf");
        assertThat(pdfs.article(12, "en")).isEmpty();
    }

    @Test
    @DisplayName("a language that is not a code, or no storage, is no link -- never a path built from it")
    void refusesWhatIsNotACode() {
        SitePdfs pdfs = new SitePdfs(root.toString());
        assertThat(pdfs.article(12, "../../x")).isEmpty();
        assertThat(pdfs.article(12, null)).isEmpty();
        assertThat(pdfs.article(null, "de")).isEmpty();
        assertThat(new SitePdfs("").article(12, "de")).isEmpty();
    }
}
