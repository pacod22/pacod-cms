package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Banner;
import io.pacod.cms.studio.MgmtMonitoringService;
import io.pacod.cms.studio.MonitoringRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * The monitoring screen: who is reading the site right now, and what the traffic has been doing.
 *
 * <p>Read-only. There is no POST here and no form -- nothing on this screen changes anything, so
 * it needs no CSRF-bearing write and no flash. The range picker is a GET form, which makes every
 * view of the data a bookmarkable URL, exactly as the article and category filters are.
 *
 * <p>Must stay in this package: {@code MgmtControllerAdvice} is scoped to
 * {@code io.pacod.cms.studio.controller}, and a controller outside it silently loses
 * {@code currentUser}, which the topbar renders under a {@code th:if}.
 *
 * <p>{@code /mgmt/monitoring} needs <b>no security change</b>: it sits under {@code /mgmt/},
 * inside {@code MgmtSecurityConfig}'s authenticated matcher. Anything on a new prefix would fall
 * into the permit-all chain instead.
 *
 * <p>{@code MgmtHomeController}'s {@code @GetMapping("/mgmt/**")} catch-all 404s every unmapped
 * GET; both mappings here win on Spring's pattern specificity, as every console screen relies
 * on.
 */
@Slf4j
@StudioPermission(read = "stats.read")
@Controller
@RequestMapping("/mgmt/monitoring")
public class MgmtMonitoringController {

    /** Enough to see the shape of what is being read without turning the screen into a report. */
    private static final int TOP_ARTICLES = 10;

    private final MgmtMonitoringService monitoringService;

    public MgmtMonitoringController(MgmtMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * The screen, optionally narrowed to one booking.
     *
     * <p>{@code banner} is a {@code String} and is resolved against the bookings before it reaches
     * the model, never echoed: {@code ?banner=abc} or the id of a deleted booking becomes no
     * filter rather than a 400 or a chart heading naming something it is not drawing. The range
     * form carries it back as a hidden field, or picking a range would drop the filter -- a GET
     * form replaces the whole query string.
     */
    @GetMapping
    public String monitoring(@RequestParam(name = "range", required = false) String range,
                             @RequestParam(name = "banner", required = false) String banner,
                             Model model) {
        MonitoringRange selected = MonitoringRange.of(range);
        List<MgmtMonitoringService.Bucket> buckets = monitoringService.series(selected);
        // Read once: the filter and the table must agree about which bookings exist.
        List<Banner> bookings = monitoringService.bookings();
        Banner selectedBanner = MgmtMonitoringService.selected(bookings, banner);

        model.addAttribute("range", selected);
        model.addAttribute("ranges", MonitoringRange.values());
        model.addAttribute("live", monitoringService.liveVisitors());
        model.addAttribute("totals", monitoringService.totals(buckets));
        model.addAttribute("trafficChart", monitoringService.trafficChart(buckets));
        model.addAttribute("bannerChart", monitoringService.bannerChart(buckets, selectedBanner, selected));
        model.addAttribute("selectedBanner", selectedBanner);
        model.addAttribute("pageTraffic", monitoringService.trafficByPage(buckets));
        model.addAttribute("topArticles", monitoringService.topArticles(TOP_ARTICLES));
        model.addAttribute("bannerReport", monitoringService.bannerReport(buckets, bookings, selected));
        model.addAttribute("content", monitoringService.contentTotals());

        return "mgmt/monitoring";
    }

    /**
     * The live reader count, polled by {@code mgmt.js}.
     *
     * <p>Its own endpoint rather than a reload of the screen: everything else here is a window
     * aggregate that changes once a minute at most, and re-running eight queries to refresh one
     * number would be the wrong trade. The screen renders the same figure server-side, so with
     * scripting off it is simply the value as of page load.
     *
     * <p>Pinned separately from the screen in {@code MgmtSecurityTest} -- the argument
     * {@code /mgmt/images/search} and {@code /mgmt/jobs/&#123;id&#125;/status} both make. A matcher
     * covering the page but not its poll would answer anonymously with nothing on any screen to
     * show for it.
     */
    @GetMapping("/live")
    @ResponseBody
    public MgmtMonitoringService.LiveCount live() {
        return monitoringService.liveVisitors();
    }
}
