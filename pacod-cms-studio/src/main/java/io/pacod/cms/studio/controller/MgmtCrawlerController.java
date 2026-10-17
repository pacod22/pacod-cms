package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.studio.MgmtCrawlerService;
import io.pacod.cms.studio.MonitoringRange;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * «Роботи»: the crawlers that reached the public site. See {@link MgmtCrawlerService} for where
 * each number comes from.
 *
 * <p>Read-only and all GET, like {@code /mgmt/monitoring}, so every view of the numbers is a
 * bookmarkable URL. Every parameter is a {@code String} and is <b>normalised before it reaches the
 * model</b> -- the {@code ?banner=} rule and {@code MgmtSort}'s: an unknown bot, category or
 * verification is no filter, a page that does not parse is page one, and none of them is a 400 or
 * a heading naming something the log is not filtered by. The template builds every link from the
 * normalised values, so a nonsense parameter does not survive the next click either.
 *
 * <p>Under {@code /mgmt/}, so it sits inside {@code MgmtSecurityConfig}'s authenticated matcher
 * and among {@code StudioPaths}' website screens; {@code MgmtSecurityTest} pins it. Must stay
 * in this package for {@code MgmtControllerAdvice}.
 */
@StudioPermission(read = "stats.read")
@Controller
@RequestMapping("/mgmt/crawlers")
public class MgmtCrawlerController {

    private static final Pattern BOT_ID = Pattern.compile("[a-z0-9-]{1,64}");
    private static final int MAX_QUERY = 200;
    private static final int MAX_PAGE = 2000;

    private final MgmtCrawlerService crawlerService;
    private final MgmtText text;

    public MgmtCrawlerController(MgmtCrawlerService crawlerService, MgmtText text) {
        this.crawlerService = crawlerService;
        this.text = text;
    }

    @GetMapping
    public String crawlers(@RequestParam(name = "range", required = false) String range,
                           @RequestParam(name = "bot", required = false) String bot,
                           @RequestParam(name = "category", required = false) String category,
                           @RequestParam(name = "verification", required = false) String verification,
                           @RequestParam(name = "errors", required = false) String errors,
                           @RequestParam(name = "q", required = false) String q,
                           @RequestParam(name = "page", required = false) String page,
                           Model model) {
        MonitoringRange selected = MonitoringRange.of(range);
        LocalDateTime now = LocalDateTime.now();
        Instant instant = Instant.now();

        MgmtCrawlerService.LogFilter filter = new MgmtCrawlerService.LogFilter(botFilter(bot),
                CrawlerCategory.of(category), CrawlerVerification.of(verification), flag(errors), query(q));
        MgmtCrawlerService.Window window = MgmtCrawlerService.window(selected, now);

        // Read once: the per-bot table's column and nothing else uses it.
        Map<String, Instant> lastSeen = crawlerService.lastSeen();
        MgmtCrawlerService.Report report = crawlerService.report(crawlerService.series(selected), lastSeen);

        model.addAttribute("range", selected);
        model.addAttribute("ranges", MonitoringRange.values());
        model.addAttribute("report", report);
        model.addAttribute("watch", crawlerService.watch(instant));
        model.addAttribute("window", window);
        model.addAttribute("paths", crawlerService.topPaths(window.from()));
        model.addAttribute("filter", filter);
        model.addAttribute("log", crawlerService.log(filter, window.from(), pageNumber(page)));
        model.addAttribute("botOptions", botOptions(report, filter));
        model.addAttribute("categories", CrawlerCategory.values());
        model.addAttribute("verifications", CrawlerVerification.values());
        model.addAttribute("times", MgmtCrawlerService.Times.at(instant, text));
        model.addAttribute("retentionDays", MgmtCrawlerService.RETENTION.toDays());

        return "mgmt/crawlers";
    }

    /**
     * The log's bot picker: the bots the period's buckets saw, plus the one being filtered by if it
     * is not among them, so the select never silently shows "every bot" while the log is narrowed.
     */
    private static List<String> botOptions(MgmtCrawlerService.Report report, MgmtCrawlerService.LogFilter filter) {
        TreeSet<String> bots = new TreeSet<>();
        report.bots().forEach(row -> bots.add(row.bot()));
        if (filter.bot() != null) {
            bots.add(filter.bot());
        }
        return new ArrayList<>(bots);
    }

    static String botFilter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return BOT_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    static boolean flag(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        return trimmed.equals("1") || trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("on");
    }

    static String query(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() > MAX_QUERY ? trimmed.substring(0, MAX_QUERY) : trimmed;
    }

    static int pageNumber(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.min(MAX_PAGE, Math.max(0, Integer.parseInt(value.strip())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
