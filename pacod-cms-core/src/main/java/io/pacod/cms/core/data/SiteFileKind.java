package io.pacod.cms.core.data;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The crawler files a website answers at its root, each edited at
 * {@code /mgmt/site-files/<param>} and served by the site's {@code SiteFilesController}.
 *
 * <p>{@link #customLimit} is in UTF-8 bytes, and every one sits below the studio's 4 MB multipart
 * ceiling, the form being posted as {@code multipart/form-data} (an url-encoded sitemap roughly
 * triples, and Tomcat's form limit is 2 MB). {@code llms-full.txt} has no custom text: a
 * hand-edited dump of every article is not a thing anyone maintains.
 */
public enum SiteFileKind {

    ROBOTS("robots", "/robots.txt", "text/plain", 64 * 1024, true, false),
    SITEMAP("sitemap", "/sitemap.xml", "application/xml", 3 * 1024 * 1024, true, true),
    LLMS("llms", "/llms.txt", "text/plain", 1024 * 1024, true, true),
    LLMS_FULL("llms-full", "/llms-full.txt", "text/plain", 0, false, true);

    /** The paths the site answers, for {@code LocaleConfig}'s exclusion list. */
    public static final String[] PATHS = Arrays.stream(values()).map(SiteFileKind::path).toArray(String[]::new);

    private final String param;
    private final String path;
    private final String contentType;
    private final int customLimit;
    private final boolean customisable;
    private final boolean excludes;

    SiteFileKind(String param, String path, String contentType, int customLimit, boolean customisable,
                 boolean excludes) {
        this.param = param;
        this.path = path;
        this.contentType = contentType;
        this.customLimit = customLimit;
        this.customisable = customisable;
        this.excludes = excludes;
    }

    /** The studio's path segment. */
    public String param() {
        return param;
    }

    /** The public address, from the website's root. */
    public String path() {
        return path;
    }

    /** The type it is served as, always in UTF-8. */
    public String contentType() {
        return contentType;
    }

    /** The most a custom text or an addition may weigh, in UTF-8 bytes. */
    public int customLimit() {
        return customLimit;
    }

    /** Whether {@link SiteFileMode#CUSTOM} is offered. */
    public boolean customisable() {
        return customisable;
    }

    /** Whether exclusions apply: the files that list the website's addresses. */
    public boolean excludes() {
        return excludes;
    }

    /** The modes offered for this file. */
    public List<SiteFileMode> modes() {
        return Arrays.stream(SiteFileMode.values()).filter(m -> customisable || m != SiteFileMode.CUSTOM).toList();
    }

    /** The additions' limit: llms-full's own, as it has no custom text, else the custom text's. */
    public int additionsLimit() {
        return customLimit > 0 ? Math.min(customLimit, 256 * 1024) : 256 * 1024;
    }

    public static Optional<SiteFileKind> ofParam(String param) {
        return Arrays.stream(values()).filter(k -> k.param.equals(param)).findFirst();
    }
}
