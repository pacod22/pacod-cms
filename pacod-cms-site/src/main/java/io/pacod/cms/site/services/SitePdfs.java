package io.pacod.cms.site.services;

import io.pacod.cms.core.tenant.SiteStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Whether the studio has made an article's PDF in a language yet ({@code PDF_ARTICLES} jobs), and
 * its address if so. Asked on every article view: one file check, cheaper than any cache that
 * would then have to learn of a new file. Without it the page would link a PDF that does not
 * exist.
 */
@Service
public class SitePdfs {

    private final String storageRoot;

    public SitePdfs(@Value("${pacod.storage.root:}") String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public Optional<String> article(Integer articleId, String lang) {
        if (articleId == null || !usable(lang)) {
            return Optional.empty();
        }
        return existing(SiteStorage.articlePdf(articleId, lang));
    }

    /** A published journal issue's PDF in a language ({@code PDF_ISSUE} jobs, or publishing one). */
    public Optional<String> issue(Integer issueId, String lang) {
        if (issueId == null || !usable(lang)) {
            return Optional.empty();
        }
        return existing(SiteStorage.issuePdf(issueId, lang));
    }

    private boolean usable(String lang) {
        return lang != null && lang.matches("[a-z]{2,3}") && storageRoot != null && !storageRoot.isBlank();
    }

    private Optional<String> existing(String relative) {
        Path file = SiteStorage.siteRoot(storageRoot).resolve(relative);
        return Files.isRegularFile(file) ? Optional.of("/" + relative) : Optional.empty();
    }
}
