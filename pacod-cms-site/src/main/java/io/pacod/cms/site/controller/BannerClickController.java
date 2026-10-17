package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.util.HttpUrls;
import io.pacod.cms.site.services.BannerImpressionCounter;
import io.pacod.cms.site.services.BannerReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every banner interaction the public site reports: the image banner's click redirect, and the
 * two beacons {@code site.js} sends for HTML5 clicks and viewable impressions.
 *
 * <h2>One click path per type</h2>
 * An image banner's {@code href} points at {@code GET /b/{id}} instead of straight at
 * {@code targetUrl}, which is how its click is known. An HTML5 banner's click happens inside the
 * iframe, which never reaches this document as an event -- but the frame is <b>same-origin and
 * unsandboxed</b>, so the parent page's {@code site.js} listens inside the frame's own document and
 * reports the click with {@code POST /b/{id}/c}. The redirect therefore refuses an HTML5 booking
 * and the beacon refuses an image one: either accepting both would count one click twice.
 *
 * <h2>Viewable impressions</h2>
 * {@code POST /b/v} carries the ids of the creatives that were at least half on screen for a second
 * in a visible tab, batched by {@code site.js} into one beacon per page view. It never touches
 * the network itself: each id is a {@code LongAdder} increment, flushed with everything else.
 *
 * <h2>The paths were chosen against the blocker lists</h2>
 * {@code /b/}, {@code /b/v} and {@code /b/{id}/c} match no rule in EasyList or EasyPrivacy. The
 * latter <em>does</em> carry {@code /b/s/beacon}, {@code /b/ss/} and {@code /b/stats?} (Adobe
 * Analytics), so a "clearer" name such as {@code /b/stats} would be blocked for every reader with
 * uBlock Origin's defaults. Grep both lists before renaming anything here.
 *
 * <p>Deliberately short and outside {@code /web/v1}: the redirect appears in the markup of every
 * page that carries an image banner.
 *
 * <p>The public filter chain is stateless with CSRF disabled and everything permitted, so none of
 * this needs security configuration. The worst a forged request can do is inflate a number the
 * operator reads -- which is why the two beacons refuse a request the browser marks as
 * cross-site: otherwise any other website could beacon into these numbers from its own readers'
 * browsers. The redirect carries no such guard and needs none to be safe.
 */
@Slf4j
@RestController
public class BannerClickController {

    /** Viewable ids per beacon. A page carries at most eight bookings; this is slack, not a target. */
    static final int MAX_VIEWABLE_IDS = 16;

    /** The longest beacon body worth parsing. Sixteen ids of four digits are under eighty. */
    static final int MAX_VIEWABLE_BODY = 256;

    private static final String SAME_ORIGIN = "same-origin";

    private final BannerReader bannerReader;
    private final BannerImpressionCounter counter;

    public BannerClickController(BannerReader bannerReader, BannerImpressionCounter counter) {
        this.bannerReader = bannerReader;
        this.counter = counter;
    }

    @GetMapping("/b/{id}")
    public ResponseEntity<Void> click(@PathVariable Integer id) {

        Banner banner = bannerReader.findAll().stream()
                .filter(b -> id.equals(b.getId()))
                .findFirst()
                .orElse(null);

        if (banner == null) {
            log.debug("Click on unknown banner id={}", id);
            return ResponseEntity.notFound().build();
        }

        // An HTML5 booking's clicks arrive by beacon. One with a stray targetUrl would redirect
        // and count here as well, invisibly -- every click counted twice.
        if (banner.getType() == BannerType.HTML5) {
            log.debug("Click redirect requested for HTML5 banner id={}; refusing", id);
            return ResponseEntity.notFound().build();
        }

        String target = banner.getTargetUrl();

        // Re-checked here and not only at save time. The studio validates on the way in, but a
        // document written any other way -- by hand in mongosh, say -- would otherwise turn this
        // into an open redirect, and a `javascript:` URL into stored XSS.
        if (!isHttpUrl(target)) {
            log.warn("Banner id={} has an unusable target URL; refusing to redirect", id);
            return ResponseEntity.notFound().build();
        }

        counter.recordClick(id);

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /**
     * A click inside a same-origin HTML5 banner, reported by {@code site.js}.
     *
     * <p>Always {@code 204}: a beacon's response is never read, and answering differently for an
     * unknown or refused id would only tell a prober which ids exist.
     */
    @PostMapping("/b/{id}/c")
    public ResponseEntity<Void> html5Click(@PathVariable Integer id,
                                           @RequestHeader(name = "Sec-Fetch-Site", required = false) String fetchSite) {
        if (isSameOriginOrUnknown(fetchSite)) {
            Banner banner = running().get(id);

            if (banner != null && banner.getType() == BannerType.HTML5 && banner.isClickTracked()) {
                counter.recordClick(id);
            }
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The creatives a reader actually saw, as comma-separated ids in a {@code text/plain} body --
     * what {@code navigator.sendBeacon} sends for a string.
     *
     * <p>Each id counts at most once per beacon; {@code site.js} queues each banner once per page
     * view, so a repeated id is not something a browser sends. Unknown ids and anything that does
     * not parse are skipped, not fatal: one stale id from a page rendered before a booking was
     * deleted must not cost the others.
     */
    @PostMapping("/b/v")
    public ResponseEntity<Void> viewable(@RequestBody(required = false) String body,
                                         @RequestHeader(name = "Sec-Fetch-Site", required = false) String fetchSite) {
        if (isSameOriginOrUnknown(fetchSite) && body != null && !body.isBlank()
                && body.length() <= MAX_VIEWABLE_BODY) {
            Map<Integer, Banner> running = running();

            for (Integer id : parseIds(body)) {
                if (running.containsKey(id)) {
                    counter.recordViewable(id);
                }
            }
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Distinct ids in the order sent, capped at {@link #MAX_VIEWABLE_IDS}. Package-private for the
     * test; never throws.
     */
    static Set<Integer> parseIds(String body) {
        Set<Integer> ids = new LinkedHashSet<>();

        for (String part : body.split(",")) {
            if (ids.size() >= MAX_VIEWABLE_IDS) {
                break;
            }
            try {
                ids.add(Integer.valueOf(part.strip()));
            } catch (NumberFormatException e) {
                // A malformed entry is skipped; the rest of the beacon still counts.
            }
        }
        return ids;
    }

    /**
     * The bookings running now, by id. Read off the cached collection, so a beacon costs no query.
     * Placement and page are not checked: the reader's browser reported a creative it was shown,
     * and the page it was shown on already decided that.
     */
    private Map<Integer, Banner> running() {
        LocalDateTime now = LocalDateTime.now();

        return bannerReader.findAll().stream()
                .filter(b -> b.getId() != null && b.isActive() && b.withinWindow(now))
                .collect(Collectors.toMap(Banner::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * Whether the browser says this request came from our own pages.
     *
     * <p>{@code Sec-Fetch-Site} is set by the browser and cannot be overridden by page script, so a
     * beacon from another website arrives marked {@code cross-site} and counts nothing. An absent
     * header is accepted: older browsers do not send it, and a script outside a browser can forge
     * anything anyway -- this guard is about other sites' readers, not about curl.
     */
    private static boolean isSameOriginOrUnknown(String fetchSite) {
        return fetchSite == null || SAME_ORIGIN.equalsIgnoreCase(fetchSite.strip());
    }

    /**
     * Whether a stored target is safe to put in a {@code Location} header.
     *
     * <p>Delegates to {@link HttpUrls#isHttpUrl}, which is in core because the studio applies the
     * same rule on save, in another process. Kept as a method here for the call sites above and
     * the tests.
     */
    static boolean isHttpUrl(String url) {
        return HttpUrls.isHttpUrl(url);
    }
}
