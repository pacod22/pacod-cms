package io.pacod.cms.core.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place a {@code /social} link's target is judged safe to redirect a reader to.
 *
 * <p>{@link HttpUrls}'s arrangement, for the same reason: the target is checked by
 * {@code MgmtSocialService} on save and again by {@code SocialController} before the
 * {@code Location} header is written, in two applications, and "what is refused on save is exactly
 * what is refused on redirect" only holds while there is one copy of the rule. The second check is
 * what stops a document written by hand in mongosh turning {@code /social/go/{id}} into an open
 * redirect or a {@code javascript:} URL into stored XSS.
 *
 * <p>It admits one thing {@code HttpUrls} does not: a <b>site-relative path</b>, so a button can
 * point at {@code /article/42} or {@code /p/about} without hardcoding the host. That is
 * where the care goes. A browser reads {@code //evil.example} as another host, and reads
 * {@code /\evil.example} the same way -- backslashes are normalised to slashes in the authority --
 * so a path must start with exactly one {@code /}, and backslashes, whitespace and control
 * characters are refused anywhere in it.
 *
 * <p>{@code mailto:} and {@code tel:} are not admitted. A 302 to a non-http scheme is honoured
 * unevenly, and least reliably inside the in-app browsers this page exists for.
 */
public final class SocialTargets {

    public static final int MAX_LENGTH = 2048;

    /**
     * An article on this site, as an operator is likely to paste it: site-relative or on the
     * website's own host, bare or behind a language prefix, with or without a query --
     * {@code /article/42}, {@code /de/article/42?lang=de},
     * {@code https://www.example.com/article/42}. {@code /article-print/…} and every other host
     * are deliberately not an article: the console shows the article's picture beside such a
     * link, and a guess would show the wrong one.
     *
     * <p>Matched against the <em>path</em>; whether an absolute address is the website's own is
     * decided first, against the hosts the caller passes -- the website's, from its settings.
     */
    private static final Pattern ARTICLE = Pattern.compile(
            "^(?:/[a-z]{2,3})?/article/(\\d{1,9})(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);

    /** A standing page, {@code /p/{slug}} -- the same host and prefix rules as {@link #ARTICLE}. */
    private static final Pattern PAGE = Pattern.compile(
            "^(?:/[a-z]{2,3})?/p/([a-z0-9][a-z0-9-]{0,63})/?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);

    /** A category listing, {@code /list/{id}}. */
    private static final Pattern CATEGORY = Pattern.compile(
            "^(?:/[a-z]{2,3})?/list/(\\d{1,9})/?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);

    private SocialTargets() {
    }

    public static boolean isValidTarget(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {
            return false;
        }
        String value = url.strip();
        if (HttpUrls.isHttpUrl(value)) {
            return hasNoUnsafeCharacters(value);
        }
        return isSitePath(value);
    }

    /**
     * The id of the article {@code url} points at, or {@code null} when it is not one of ours.
     *
     * @param ownHosts the hosts that make an absolute address the website's own, lowercase;
     *                 empty means only site-relative addresses are
     */
    public static Integer articleIdOf(String url, Collection<String> ownHosts) {
        Matcher matcher = matcher(ARTICLE, url, ownHosts);
        if (matcher == null) {
            return null;
        }
        int id = Integer.parseInt(matcher.group(1));
        return id > 0 ? id : null;
    }

    /** The slug of the standing page {@code url} points at, lowercase, or {@code null}. */
    public static String pageSlugOf(String url, Collection<String> ownHosts) {
        Matcher matcher = matcher(PAGE, url, ownHosts);
        return matcher != null ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** The id of the category listing {@code url} points at, or {@code null}. */
    public static Integer categoryIdOf(String url, Collection<String> ownHosts) {
        Matcher matcher = matcher(CATEGORY, url, ownHosts);
        if (matcher == null) {
            return null;
        }
        int id = Integer.parseInt(matcher.group(1));
        return id > 0 ? id : null;
    }

    /** Whether {@code url} is one of this website's own articles, pages or category listings. */
    public static boolean isSiteContent(String url, Collection<String> ownHosts) {
        return articleIdOf(url, ownHosts) != null || pageSlugOf(url, ownHosts) != null
                || categoryIdOf(url, ownHosts) != null;
    }

    /**
     * {@code url} reduced to the path, query and fragment when it is the website's own -- a
     * site-relative path, or an absolute http(s) address naming one of {@code ownHosts} -- else
     * {@code null}. The port is ignored: a host is the website's whatever port it was reached on.
     */
    static String ownPath(String url, Collection<String> ownHosts) {
        if (url == null) {
            return null;
        }
        String value = url.strip();
        if (isSitePath(value)) {
            return value;
        }
        if (!HttpUrls.isHttpUrl(value) || !hasNoUnsafeCharacters(value) || ownHosts == null
                || ownHosts.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || !ownHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return path
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "")
                + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
    }

    private static Matcher matcher(Pattern pattern, String url, Collection<String> ownHosts) {
        String path = ownPath(url, ownHosts);
        if (path == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(path);
        return matcher.matches() ? matcher : null;
    }

    public static boolean isSitePath(String value) {
        return value != null
                && value.startsWith("/")
                && !value.startsWith("//")
                && hasNoUnsafeCharacters(value);
    }

    private static boolean hasNoUnsafeCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
