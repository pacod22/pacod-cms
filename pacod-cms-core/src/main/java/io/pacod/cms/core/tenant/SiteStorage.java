package io.pacod.cms.core.tenant;

import java.nio.file.Path;

/**
 * Where a website's files are.
 *
 * <p>Each website has a folder of its own, {@code <storage root>/sites/<site id>/}, laid out the way
 * its public addresses are: {@code assets/img/articles/…} is served at {@code /assets/img/articles/…},
 * {@code resources/banners/…} at {@code /resources/banners/…}. The folder settings
 * ({@code app.image.folder}, {@code app.banner.folder}) are therefore templates carrying
 * {@value #SITE} where the website's id goes, and every write resolves them for the website bound
 * in {@link TenantContext} -- no website bound, no file written.
 *
 * <p>A template without the placeholder is used as written, which is what the unit tests rely on
 * when they point a service at a temporary folder.
 */
public final class SiteStorage {

    public static final String SITE = "{site}";

    private SiteStorage() {
    }

    /** {@code template} with the bound website's id in place of {@value #SITE}. */
    public static String folder(String template) {
        if (template == null) {
            throw new IllegalStateException("No storage folder is configured");
        }
        return template.contains(SITE) ? template.replace(SITE, TenantContext.current().siteId()) : template;
    }

    /**
     * Where an article's PDF in one language lives, relative to the website's folder -- which is
     * also its public address, the folder being laid out like the addresses ({@code MediaController}).
     * The studio writes it; the site links it only once it exists.
     */
    public static String articlePdf(int articleId, String lang) {
        return "resources/pdfs/articles/" + code(lang) + "/article-" + articleId + ".pdf";
    }

    /**
     * Where a published journal issue's PDF in one language lives: public, like
     * {@link #articlePdf}. Written when the issue is published (or generated while it is).
     */
    public static String issuePdf(int issueId, String lang) {
        return "resources/pdfs/issues/" + code(lang) + "/issue-" + issueId + ".pdf";
    }

    /**
     * Where every generated issue PDF is kept, published or not. Under {@code private/}, which no
     * address names: {@code MediaController} serves the {@code assets} and {@code resources} trees
     * only, so a draft issue is downloaded through the studio and nowhere else.
     */
    public static String issueDraftPdf(int issueId, String lang) {
        return "private/pdfs/issues/" + code(lang) + "/issue-" + issueId + ".pdf";
    }

    private static String code(String lang) {
        if (lang == null || !lang.matches("[a-z]{2,3}")) {
            throw new IllegalArgumentException("Not a language code: " + lang);
        }
        return lang;
    }

    /** The bound website's own folder under {@code storageRoot}. */
    public static Path siteRoot(String storageRoot) {
        return Path.of(storageRoot, "sites", TenantContext.current().siteId()).toAbsolutePath().normalize();
    }
}
