package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides which banners a given page renders, and records that they were shown.
 *
 * <p>The filtering runs per request against {@link BannerReader}'s cached list rather than being
 * cached itself -- see that class for why the clock has to be consulted after the cache and not
 * before it.
 *
 * <p>A failure here must never be the reason a page fails to render, which is the same rule
 * {@code ArticleViewCounter} follows: an advertising column is not worth a 500. Every entry point
 * therefore degrades to an empty list.
 */
@Slf4j
@Service
public class BannerService {

    private final BannerReader bannerReader;
    private final BannerImpressionCounter impressionCounter;

    public BannerService(BannerReader bannerReader, BannerImpressionCounter impressionCounter) {
        this.bannerReader = bannerReader;
        this.impressionCounter = impressionCounter;
    }

    /**
     * The banners booked for one surface of one page, in display order, with an impression
     * recorded for each.
     *
     * @param placement which surface -- the leaderboard row or the advertiser column
     * @param page      which public view is being rendered
     */
    public List<Banner> forPage(BannerPlacement placement, BannerPage page) {
        return forPage(placement, page, List.of());
    }

    /**
     * {@link #forPage(BannerPlacement, BannerPage)} on a page of these sections -- an article's, or
     * a section's list; see {@link #running(BannerPlacement, BannerPage, Collection)}.
     */
    public List<Banner> forPage(BannerPlacement placement, BannerPage page, Collection<Integer> categories) {
        List<Banner> running = running(placement, page, categories);
        for (Banner banner : running) {
            impressionCounter.recordImpression(banner.getId());
        }
        return running;
    }

    /**
     * The same selection without the impression side effect, for callers that are not rendering
     * a page view -- the studio's preview, and the tests.
     */
    public List<Banner> running(BannerPlacement placement, BannerPage page) {
        return running(placement, page, List.of());
    }

    /**
     * The selection on a page of these sections: <b>specific beats general</b>, per placement.
     * When a booking for one of the page's sections runs here, only those render; otherwise only
     * the bookings that name no section. A section's own advertiser takes the column on its
     * articles, and the general bookings fill it everywhere else -- the two never stack.
     */
    public List<Banner> running(BannerPlacement placement, BannerPage page, Collection<Integer> categories) {
        LocalDateTime now = LocalDateTime.now();
        List<Banner> selected = new ArrayList<>();

        try {
            for (Banner banner : bannerReader.findAll()) {
                if (banner.getPlacement() == placement && banner.runsOn(page, now)) {
                    selected.add(banner);
                }
            }
        } catch (RuntimeException e) {
            // An unreachable MongoDB must cost the ad column, not the article.
            log.warn("Could not load banners for placement={} page={}: {}", placement, page, e.getMessage());
            return List.of();
        }

        List<Banner> targeted = selected.stream()
                .filter(banner -> banner.isTargeted() && banner.coversCategories(categories)).toList();
        return !targeted.isEmpty() ? targeted : selected.stream().filter(banner -> !banner.isTargeted()).toList();
    }
}
