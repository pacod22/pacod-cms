package io.pacod.cms.core.services.sitefiles;

import java.util.ArrayList;
import java.util.List;

/**
 * The paths an operator leaves out of a generated sitemap or llms file: one per line, each an
 * exact path ({@code /p/terms}) or a prefix ending in {@code *} ({@code /list/*}). A line starting
 * with {@code #} is a comment. Lines {@link SiteFileRules} would refuse are ignored here, so a
 * document written by hand cannot break the file.
 */
public final class PathExclusions {

    private static final PathExclusions NONE = new PathExclusions(List.of(), List.of());

    private final List<String> exact;
    private final List<String> prefixes;

    private PathExclusions(List<String> exact, List<String> prefixes) {
        this.exact = exact;
        this.prefixes = prefixes;
    }

    public static PathExclusions none() {
        return NONE;
    }

    public static PathExclusions of(String text) {
        List<String> exact = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        for (String line : SiteFileRules.lines(text)) {
            if (SiteFileRules.isExclusion(line)) {
                if (line.endsWith("*")) {
                    prefixes.add(line.substring(0, line.length() - 1));
                } else {
                    exact.add(line);
                }
            }
        }
        return exact.isEmpty() && prefixes.isEmpty() ? NONE : new PathExclusions(List.copyOf(exact), List.copyOf(prefixes));
    }

    /** Whether {@code path} (from the website's root, no query) is left out. */
    public boolean excludes(String path) {
        if (path == null) {
            return false;
        }
        if (exact.contains(path)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
