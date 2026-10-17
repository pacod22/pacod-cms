package io.pacod.cms.site.services;

import io.pacod.cms.core.util.CrawlerClassifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The public site's one definition of "not a reader".
 *
 * <p>A request is automated if {@link CrawlerClassifier} names its User-Agent, or -- failing that --
 * if {@link SocialSourceClassifier#isBot(String)} recognises it as a script or an unnamed bot. The
 * second case is filed under {@link CrawlerClassifier#OTHER_BOT}, {@code other}: without it
 * {@code curl} and {@code python-requests} would count as people. An empty User-Agent is
 * automated too, for {@code isBot}'s reason: a browser always sends one.
 *
 * <h2>Classified once, by the filter; read everywhere else</h2>
 * {@code CrawlerTrafficFilter} classifies each request and stores the result as
 * {@link #REQUEST_ATTRIBUTE}. Every counter that must not count a crawler -- page views and readers
 * in {@code SiteMetricCollector}, banner impressions and clicks in
 * {@code BannerImpressionCounter}, {@code Article.counter} in {@code WebArticleService} -- asks
 * {@link #isUncounted(HttpServletRequest)} or {@link #currentIsUncounted()}, which read that
 * attribute and the preview's {@link #UNCOUNTED_ATTRIBUTE} and <b>nothing else</b>. An absent attribute means "a reader": a unit test with no
 * filter, a standalone MockMvc, or a call made off a request thread all count exactly as they did
 * before this class existed. Re-classifying the User-Agent there instead would turn every
 * controller test's header-less request into a bot.
 *
 * <p>{@code /social} is the exception, and only in form: its controller classifies the User-Agent it
 * is handed with {@link #classify(String)}, because its tests assert on that header directly. The
 * definition is the same.
 */
public final class CrawlerTraffic {

    /** Holds the {@link CrawlerClassifier.Identity} of an automated request; absent for a reader. */
    public static final String REQUEST_ATTRIBUTE = CrawlerTraffic.class.getName() + ".identity";

    /**
     * Marks a request a person sent that must count for nothing all the same: the studio's draft
     * preview ({@code PreviewFilter}), which reloads on every saved draft.
     */
    public static final String UNCOUNTED_ATTRIBUTE = CrawlerTraffic.class.getName() + ".uncounted";

    private CrawlerTraffic() {
    }

    /** Who an automated client is, or {@code null} for a reader. */
    public static CrawlerClassifier.Identity classify(String userAgent) {
        CrawlerClassifier.Identity named = CrawlerClassifier.classify(userAgent);
        if (named != null) {
            return named;
        }
        return SocialSourceClassifier.isBot(userAgent) ? CrawlerClassifier.other() : null;
    }

    /** Whether the filter marked this request as automated. {@code null} is a reader. */
    public static boolean isAutomated(HttpServletRequest request) {
        return request != null && request.getAttribute(REQUEST_ATTRIBUTE) != null;
    }

    /** Marks the request as counting for nothing ({@link #UNCOUNTED_ATTRIBUTE}). */
    public static void markUncounted(HttpServletRequest request) {
        request.setAttribute(UNCOUNTED_ATTRIBUTE, Boolean.TRUE);
    }

    /**
     * What every counter asks: an automated client, or a request marked as counting for nothing.
     * {@link #isAutomated} stays the crawler question alone.
     */
    public static boolean isUncounted(HttpServletRequest request) {
        return request != null && (isAutomated(request) || request.getAttribute(UNCOUNTED_ATTRIBUTE) != null);
    }

    /** {@link #isUncounted} for code that is not handed the request; off a request thread, a reader. */
    public static boolean currentIsUncounted() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
                && (attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) != null
                || attributes.getAttribute(UNCOUNTED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) != null);
    }

    /**
     * The same question for code that is not handed the request -- the banner and article counters,
     * deep under a controller. Off a request thread there is no request, and the answer is "a
     * reader", which is what those callers did before.
     */
    public static boolean currentIsAutomated() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
                && attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) != null;
    }
}
