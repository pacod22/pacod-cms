package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A website's crawler files -- {@code robots.txt}, {@code sitemap.xml}, {@code llms.txt},
 * {@code llms-full.txt} -- as the operator chose them at {@code /mgmt/site-files}. One document,
 * id {@value #ID}; absent means every file automatic with the defaults below.
 *
 * <p>Written by the studio one file at a time ({@code MgmtSiteFilesService} applies only the posted
 * file's setting), read by the site through {@code SiteFilesReader}.
 */
@Data
@Document(collection = SiteFiles.COLLECTION)
public class SiteFiles {

    public static final String COLLECTION = "siteFiles";
    public static final String ID = "files";

    public static final int DEFAULT_LLMS_ARTICLES = 50;
    public static final int MAX_LLMS_ARTICLES = 500;
    public static final int DEFAULT_LLMS_FULL_ARTICLES = 100;
    public static final int MAX_LLMS_FULL_ARTICLES = 1000;

    @Id
    private String id = ID;

    private SiteFileSetting robots = new SiteFileSetting();
    private SiteFileSetting sitemap = new SiteFileSetting();
    private SiteFileSetting llms = new SiteFileSetting();
    private SiteFileSetting llmsFull = new SiteFileSetting();

    /** The generated robots.txt shuts out the crawlers that gather training data ({@code AiCrawlers.TRAINING}). */
    private boolean blockAiTraining;

    /** The generated robots.txt shuts out AI search and assistants fetching for a person ({@code AiCrawlers.ANSWERING}). */
    private boolean blockAiAnswering;

    /** How many of the newest articles {@code llms.txt} lists. */
    private int llmsArticles = DEFAULT_LLMS_ARTICLES;

    /** How many of the newest articles {@code llms-full.txt} carries whole. */
    private int llmsFullArticles = DEFAULT_LLMS_FULL_ARTICLES;

    /** The setting for {@code kind}, never null. */
    public SiteFileSetting setting(SiteFileKind kind) {
        SiteFileSetting setting = switch (kind) {
            case ROBOTS -> robots;
            case SITEMAP -> sitemap;
            case LLMS -> llms;
            case LLMS_FULL -> llmsFull;
        };
        return setting != null ? setting : new SiteFileSetting();
    }

    /** The stored field holding {@code kind}'s setting, for a targeted {@code $set}. */
    public static String field(SiteFileKind kind) {
        return switch (kind) {
            case ROBOTS -> "robots";
            case SITEMAP -> "sitemap";
            case LLMS -> "llms";
            case LLMS_FULL -> "llmsFull";
        };
    }

    public void put(SiteFileKind kind, SiteFileSetting setting) {
        switch (kind) {
            case ROBOTS -> robots = setting;
            case SITEMAP -> sitemap = setting;
            case LLMS -> llms = setting;
            case LLMS_FULL -> llmsFull = setting;
        }
    }

    /** The mode in effect: custom text that cannot be custom (llms-full), or is empty, reads as off. */
    public SiteFileMode modeOf(SiteFileKind kind) {
        SiteFileMode mode = setting(kind).mode();
        if (mode == SiteFileMode.CUSTOM && !kind.customisable()) {
            return SiteFileMode.AUTO;
        }
        return mode;
    }

    /** Whether a file is answered at all. */
    public boolean isOn(SiteFileKind kind) {
        return modeOf(kind) != SiteFileMode.OFF;
    }

    /** {@link #llmsArticles} within its bounds, whatever was written by hand. */
    public int llmsArticleLimit() {
        return Math.clamp(llmsArticles, 0, MAX_LLMS_ARTICLES);
    }

    /** {@link #llmsFullArticles} within its bounds. */
    public int llmsFullArticleLimit() {
        return Math.clamp(llmsFullArticles, 0, MAX_LLMS_FULL_ARTICLES);
    }
}
