package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.MgmtCrawlerService;
import io.pacod.cms.studio.MgmtDashboardService;
import io.pacod.cms.studio.MonitoringRange;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;

/**
 * The dashboard: the console's landing page. Who is on the site and in the console right now, and
 * what the last 24 hours did -- each section a summary of a screen one click away. See
 * {@link MgmtDashboardService}.
 *
 * <p>Read-only, like the monitoring screen. Under {@code /mgmt/}, so it sits inside
 * {@code MgmtSecurityConfig}'s authenticated matcher; the literal path beats
 * {@code MgmtHomeController}'s {@code /mgmt/**} catch-all on specificity.
 */
@StudioPermission(read = "website.read")
@Controller
@RequestMapping("/mgmt/dashboard")
public class MgmtDashboardController {

    private final MgmtDashboardService dashboardService;
    private final MgmtText text;

    public MgmtDashboardController(MgmtDashboardService dashboardService, MgmtText text) {
        this.dashboardService = dashboardService;
        this.text = text;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("dash", dashboardService.dashboard());
        model.addAttribute("range", MonitoringRange.H24);
        model.addAttribute("times", MgmtCrawlerService.Times.at(Instant.now(), text));
        model.addAttribute("retentionDays", MgmtCrawlerService.RETENTION.toDays());
        return "mgmt/dashboard";
    }

    /**
     * The two "right now" tiles, polled by {@code mgmt.js} once a minute.
     *
     * <p>Not counted as operator activity -- see {@code MgmtPresenceInterceptor} -- or an open tab
     * would keep its operator "active" all day. Pinned separately from the screen in
     * {@code MgmtSecurityTest}: this answer names who is signed in.
     */
    @GetMapping("/live")
    @ResponseBody
    public MgmtDashboardService.Live live() {
        return dashboardService.live();
    }
}
