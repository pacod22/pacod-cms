package io.pacod.cms.site.data;

import java.util.Map;

/**
 * What a {@code /social} link points at, when that is the site's own translated content -- an
 * article, a standing page or a category listing: its title per language, plain text, keyed by bare
 * code and present only for the languages it is actually written in, and, for an article, its cover
 * as a public path ({@code null} otherwise). Read with the page by {@code SocialPageReader} and
 * cached with it.
 *
 * <p>The titles are what lets a link follow the reader's language without anyone translating it: a
 * German reader sees the German title of the page or article the link opens, where one exists,
 * rather than the Bulgarian text written on the link.
 */
public record LinkTarget(String cover, Map<String, String> titles) {

    public LinkTarget {
        titles = titles == null ? Map.of() : Map.copyOf(titles);
    }

    /** The title in exactly {@code lang}, or {@code null}. */
    public String titleIn(String lang) {
        return lang == null ? null : titles.get(lang);
    }
}
