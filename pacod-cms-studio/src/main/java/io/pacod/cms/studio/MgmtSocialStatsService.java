package io.pacod.cms.studio;

import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SiteMetric;
import io.pacod.cms.core.data.SiteMetricDay;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.SiteMetricDayRepository;
import io.pacod.cms.core.repository.SiteMetricHourRepository;
import io.pacod.cms.core.util.SocialTargets;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /mgmt/social/stats}: what {@code /social} did over a {@link MonitoringRange}.
 *
 * <p>It reads the same buckets {@link MgmtMonitoringService} does -- the public site writes the
 * {@code social*} maps into {@code siteMetricHour}/{@code siteMetricDay} in the same flush as
 * everything else -- and follows its rules: a missing bucket is an explicit zero, so the chart
 * never draws a straight line across an outage; a failed read degrades to zeroes and an ERROR log;
 * and the empty chart says so rather than drawing a flat line.
 *
 * <h2>Three numbers, three meanings</h2>
 * <ul>
 *   <li><b>Views</b> are renders of {@code /social}, bots and the page reloading itself excluded.
 *       The denominator of every rate on the screen.</li>
 *   <li><b>Readers</b> are distinct per bucket and do not sum; the tile shows the busiest bucket,
 *       as the monitoring screen does.</li>
 *   <li><b>Clicks</b> are taps on a tile that reached the redirect from this page. CTR is clicks per
 *       100 views, the figure link-in-bio tools report.</li>
 * </ul>
 *
 * <h2>Links that no longer exist</h2>
 * A deleted link's clicks happened, and stay in the buckets. They are listed as «изтрита връзка»
 * rather than dropped, so a period's total does not shrink because the page was tidied. Ids are
 * never reused (see {@code SocialLink}), so unlike the banner report nothing here needs a
 * {@code createdOn} guard.
 */
@Slf4j
@Service
public class MgmtSocialStatsService {

    private final SiteMetricHourRepository hourRepository;
    private final SiteMetricDayRepository dayRepository;
    private final ArticleRepository articleRepository;
    private final SiteSettingsRepository siteSettingsRepository;
    private final MongoTemplate mongoTemplate;
    private MgmtText text;

    public MgmtSocialStatsService(SiteMetricHourRepository hourRepository,
                                  SiteMetricDayRepository dayRepository,
                                  ArticleRepository articleRepository,
                                  MongoTemplate mongoTemplate,
                                  SiteSettingsRepository siteSettingsRepository) {
        this.hourRepository = hourRepository;
        this.dayRepository = dayRepository;
        this.articleRepository = articleRepository;
        this.mongoTemplate = mongoTemplate;
        this.siteSettingsRepository = siteSettingsRepository;
    }

    /** The hosts that make a link's address the website's own; empty when unreadable. */
    private Set<String> ownHosts() {
        try {
            return siteSettingsRepository.findById(SiteSettings.ID)
                    .map(SiteSettings::ownHosts).orElseGet(Set::of);
        } catch (RuntimeException e) {
            log.error("Could not read the site settings for the /social report: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * The console's text resolver, for the labels this service builds (chart series, tooltips, the
     * name of a deleted link). Set by Spring; a service constructed by hand -- as the tests do --
     * has none and speaks English, which is what the base bundle says anyway.
     */
    @Autowired(required = false)
    void setText(MgmtText text) {
        this.text = text;
    }

    private String t(String key, Object... args) {
        return text != null ? text.msg(key, args) : MgmtText.base(key, args);
    }

    /** One bucket of the window, gap-filled. Maps are never null. */
    public record Bucket(LocalDateTime start, String axisLabel, String tooltipLabel,
                         long views, long visitors,
                         Map<String, Long> viewsBySource,
                         Map<String, Map<String, Long>> clicks,
                         Map<String, Map<String, Long>> articleClicks) {
    }

    public record Totals(long views, long peakVisitors, long clicks, Double clickThroughRate,
                         SocialSource topSource) {
    }

    public record SourceRow(SocialSource source, long views, Double viewShare, long clicks,
                            Double clickThroughRate) {
    }

    /** {@code articleId} is the article the link opens, or {@code null} when it opens something else. */
    public record LinkRow(String id, String label, SocialLinkPlacement placement, SocialIcon icon,
                          String url, Integer articleId, boolean live, boolean deleted, long clicks,
                          Double clickThroughRate, Double clickShare, Long allTime) {
    }

    /**
     * One article clicked through {@code /social}, however it was reached: from a tile in the
     * latest-articles block, or from a link the operator pointed at it. {@code clicks} is the sum.
     */
    public record ArticleRow(int id, String title, long fromBlock, long fromLinks, long clicks,
                             Double clickThroughRate) {
    }

    /**
     * A row of the link × source matrix: a link ({@code linkId}), or one article clicked in the
     * latest-articles block ({@code articleId}) -- never the block as a single lump, which would
     * say how much the block was used and not what anybody read.
     */
    public record MatrixRow(String label, String linkId, Integer articleId, List<Long> cells, long total) {
    }

    public record Matrix(List<SocialSource> sources, List<MatrixRow> rows) {
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    public record Report(Totals totals, MgmtChart.Chart chart, List<SourceRow> sources,
                         List<LinkRow> links, List<ArticleRow> articles, Matrix matrix,
                         SocialSource sourceFilter, String linkFilter, String linkFilterLabel) {
    }

    /* ------------------------------------------------------------------ the series */

    public List<Bucket> series(MonitoringRange range) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = range.from(now);
        LocalDateTime to = range.to(now);

        Map<LocalDateTime, SiteMetric> recorded = new HashMap<>();
        try {
            List<? extends SiteMetric> found = range.isHourly()
                    ? hourRepository.findInWindow(from, to)
                    : dayRepository.findInWindow(from, to);
            for (SiteMetric metric : found) {
                if (metric.getStart() != null) {
                    recorded.put(metric.getStart().truncatedTo(range.getGrain()), metric);
                }
            }
        } catch (DataAccessException e) {
            log.error("Could not read the /social series; the screen will show zeroes: {}", e.getMessage());
        }

        List<Bucket> buckets = new ArrayList<>(range.getBuckets());
        for (int i = 0; i < range.getBuckets(); i++) {
            LocalDateTime start = from.plus(i, range.getGrain());
            SiteMetric metric = recorded.get(start);
            buckets.add(new Bucket(start, range.formatAxis(start), range.formatTooltip(start),
                    metric == null ? 0 : metric.socialViewTotal(),
                    metric == null ? 0 : metric.getSocialVisitors(),
                    metric == null ? Map.of() : metric.getSocialViewsBySource(),
                    metric == null ? Map.of() : metric.getSocialClicks(),
                    metric == null ? Map.of() : metric.getSocialArticleClicks()));
        }
        return buckets;
    }

    /**
     * Every link's clicks since the counting began: the sum over <em>every</em> day bucket, which
     * never expire. One query, projected to the two click maps, so a year of buckets is a few
     * hundred small documents. Empty on failure -- the column then shows a dash, not a zero.
     */
    public Map<String, Long> allTimeClicks() {
        Map<String, Long> totals = new HashMap<>();
        try {
            Query query = new Query();
            query.fields().include(SiteMetric.FIELD_SOCIAL_CLICKS);
            for (SiteMetricDay day : mongoTemplate.find(query, SiteMetricDay.class)) {
                day.getSocialClicks().forEach((id, bySource) -> totals.merge(id, sum(bySource), Long::sum));
            }
        } catch (DataAccessException e) {
            log.error("Could not read the all-time /social clicks: {}", e.getMessage());
            return Map.of();
        }
        return totals;
    }

    /** Clicks per link over the range -- the edit screen's «кликове» column. */
    public Map<String, Long> clicksByLink(MonitoringRange range) {
        Map<String, Long> totals = new HashMap<>();
        for (Bucket bucket : series(range)) {
            bucket.clicks().forEach((id, bySource) -> totals.merge(id, sum(bySource), Long::sum));
        }
        return totals;
    }

    /* ------------------------------------------------------------------ filters */

    /** {@code ?source=}, exact enum name or key; anything else is no filter -- never a 400. */
    public static SocialSource sourceFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SocialSource source : SocialSource.values()) {
            if (source.name().equalsIgnoreCase(value.strip())) {
                return source;
            }
        }
        return null;
    }

    /** {@code ?link=}: a link on the page or one the window has clicks for; anything else is no filter. */
    public static String linkFilter(String value, SocialPage page, List<Bucket> buckets) {
        if (!SocialPage.isLinkId(value)) {
            return null;
        }
        if (page.link(value).isPresent()) {
            return value;
        }
        return buckets.stream().anyMatch(b -> b.clicks().containsKey(value)) ? value : null;
    }

    /* ------------------------------------------------------------------ the report */

    public Report report(List<Bucket> buckets, SocialPage page, SocialSource sourceFilter,
                         String linkFilter, Map<String, Long> allTime) {
        List<String> chain = LanguageChain.forRequest(null);
        LocalDateTime now = LocalDateTime.now();

        // Totals per source over the window, before any filter: the source table shows them all.
        Map<SocialSource, Long> viewsBySource = new EnumMap<>(SocialSource.class);
        Map<SocialSource, Long> clicksBySource = new EnumMap<>(SocialSource.class);
        Map<String, Map<SocialSource, Long>> clicksByLink = new LinkedHashMap<>();
        Map<Integer, Map<SocialSource, Long>> blockByArticle = new LinkedHashMap<>();
        long peakVisitors = 0;

        for (Bucket bucket : buckets) {
            peakVisitors = Math.max(peakVisitors, bucket.visitors());
            bucket.viewsBySource().forEach((key, count) ->
                    viewsBySource.merge(SocialSource.fromStored(key), nz(count), Long::sum));
            bucket.clicks().forEach((id, bySource) -> bySource.forEach((key, count) -> {
                SocialSource source = SocialSource.fromStored(key);
                clicksByLink.computeIfAbsent(id, k -> new EnumMap<>(SocialSource.class))
                        .merge(source, nz(count), Long::sum);
                clicksBySource.merge(source, nz(count), Long::sum);
            }));
            bucket.articleClicks().forEach((id, bySource) -> bySource.forEach((key, count) -> {
                SocialSource source = SocialSource.fromStored(key);
                Integer articleId = parseId(id);
                if (articleId != null) {
                    blockByArticle.computeIfAbsent(articleId, k -> new EnumMap<>(SocialSource.class))
                            .merge(source, nz(count), Long::sum);
                }
                clicksBySource.merge(source, nz(count), Long::sum);
            }));
        }

        long views = sourceFilter == null
                ? viewsBySource.values().stream().mapToLong(Long::longValue).sum()
                : viewsBySource.getOrDefault(sourceFilter, 0L);
        long clicks = sourceFilter == null
                ? clicksBySource.values().stream().mapToLong(Long::longValue).sum()
                : clicksBySource.getOrDefault(sourceFilter, 0L);
        SocialSource top = viewsBySource.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .max(Map.Entry.<SocialSource, Long>comparingByValue()
                        .thenComparing(e -> -e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .orElse(null);

        Totals totals = new Totals(views, peakVisitors, clicks, rate(clicks, views), top);

        // Every article the window touches -- clicked in the block, or behind a link on the page --
        // named in one projected query, so the article table, the link table and the matrix all
        // call an article by the same name.
        Set<Integer> articleIds = new LinkedHashSet<>(blockByArticle.keySet());
        Set<String> ownHosts = ownHosts();
        for (SocialLink link : page.getLinks()) {
            Integer articleId = SocialTargets.articleIdOf(link.getUrl(), ownHosts);
            if (articleId != null) {
                articleIds.add(articleId);
            }
        }
        Map<Integer, String> titles = articleTitles(articleIds);

        List<SourceRow> sourceRows = sourceRows(viewsBySource, clicksBySource);
        List<LinkRow> linkRows = linkRows(page, chain, now, clicksByLink, sourceFilter, views, allTime, titles);
        List<ArticleRow> articleRows = articleRows(blockByArticle, linkRows, sourceFilter, views, titles);
        Matrix matrix = matrix(linkRows, clicksByLink, blockByArticle, titles);

        String linkLabel = linkFilter == null ? null : linkRows.stream()
                .filter(r -> r.id().equals(linkFilter)).map(LinkRow::label).findFirst().orElse(linkFilter);

        return new Report(totals, chart(buckets, sourceFilter, linkFilter), sourceRows, linkRows,
                articleRows, matrix, sourceFilter, linkFilter, linkLabel);
    }

    private MgmtChart.Chart chart(List<Bucket> buckets, SocialSource sourceFilter, String linkFilter) {
        List<Long> views = new ArrayList<>(buckets.size());
        List<Long> clicks = new ArrayList<>(buckets.size());
        List<String> tooltips = new ArrayList<>(buckets.size());

        for (Bucket bucket : buckets) {
            long v = sourceFilter == null
                    ? sum(bucket.viewsBySource())
                    : nz(bucket.viewsBySource().get(sourceFilter.name()));
            long c = 0;
            for (Map.Entry<String, Map<String, Long>> link : bucket.clicks().entrySet()) {
                if (linkFilter == null || linkFilter.equals(link.getKey())) {
                    c += sourceFilter == null ? sum(link.getValue()) : nz(link.getValue().get(sourceFilter.name()));
                }
            }
            if (linkFilter == null) {
                for (Map<String, Long> article : bucket.articleClicks().values()) {
                    c += sourceFilter == null ? sum(article) : nz(article.get(sourceFilter.name()));
                }
            }
            views.add(v);
            clicks.add(c);
            tooltips.add(t("pages.stats.tooltip", bucket.tooltipLabel(), v, bucket.visitors(), c));
        }

        List<MgmtChart.Input> inputs = new ArrayList<>();
        inputs.add(new MgmtChart.Input("views", t("pages.stats.views"), views));
        // Readers are counted per page, not per source, so the line would not be narrowed by a
        // source filter -- it is left out rather than drawn next to numbers it does not match.
        if (sourceFilter == null) {
            inputs.add(new MgmtChart.Input("visitors", t("pages.stats.visitors"), buckets.stream().map(Bucket::visitors).toList()));
        }
        inputs.add(new MgmtChart.Input("clicks", t("pages.stats.clicks"), clicks));

        return MgmtChart.of(buckets.stream().map(Bucket::axisLabel).toList(), tooltips, inputs);
    }

    private static List<SourceRow> sourceRows(Map<SocialSource, Long> views, Map<SocialSource, Long> clicks) {
        long totalViews = views.values().stream().mapToLong(Long::longValue).sum();
        List<SourceRow> rows = new ArrayList<>();
        for (SocialSource source : SocialSource.values()) {
            long v = views.getOrDefault(source, 0L);
            long c = clicks.getOrDefault(source, 0L);
            if (v > 0 || c > 0) {
                rows.add(new SourceRow(source, v, rate(v, totalViews), c, rate(c, v)));
            }
        }
        rows.sort(Comparator.comparingLong(SourceRow::views).reversed()
                .thenComparing(Comparator.comparingLong(SourceRow::clicks).reversed()));
        return rows;
    }

    private List<LinkRow> linkRows(SocialPage page, List<String> chain, LocalDateTime now,
                                          Map<String, Map<SocialSource, Long>> clicksByLink,
                                          SocialSource sourceFilter, long views, Map<String, Long> allTime,
                                          Map<Integer, String> articleTitles) {
        Set<String> ownHosts = ownHosts();
        Map<String, Long> clicks = new LinkedHashMap<>();
        clicksByLink.forEach((id, bySource) -> clicks.put(id, sourceFilter == null
                ? bySource.values().stream().mapToLong(Long::longValue).sum()
                : bySource.getOrDefault(sourceFilter, 0L)));
        long totalClicks = clicks.values().stream().mapToLong(Long::longValue).sum();

        List<LinkRow> rows = new ArrayList<>();
        // Icons first, then buttons, each in display order -- the order the page shows them in.
        List<SocialLink> ordered = new ArrayList<>(page.linksOf(SocialLinkPlacement.ICON));
        ordered.addAll(page.linksOf(SocialLinkPlacement.BUTTON));
        Set<String> seen = new LinkedHashSet<>();
        for (SocialLink link : ordered) {
            seen.add(link.getId());
            long c = clicks.getOrDefault(link.getId(), 0L);
            Integer articleId = SocialTargets.articleIdOf(link.getUrl(), ownHosts);
            // A link to an article with no text of its own is called by the article's title -- what
            // the public page shows on it -- rather than by its address.
            String label = link.title(chain) == null && articleId != null && articleTitles.containsKey(articleId)
                    ? articleTitles.get(articleId) : link.label(chain);
            rows.add(new LinkRow(link.getId(), label, link.getPlacement(), link.getIcon(),
                    link.getUrl(), articleId, link.isLive(now), false, c, rate(c, views), rate(c, totalClicks),
                    allTime.isEmpty() ? null : allTime.getOrDefault(link.getId(), 0L)));
        }
        for (Map.Entry<String, Long> entry : clicks.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                long c = entry.getValue();
                rows.add(new LinkRow(entry.getKey(), t("pages.stats.deletedLink"), null, null, null, null, false, true, c,
                        rate(c, views), rate(c, totalClicks),
                        allTime.isEmpty() ? null : allTime.getOrDefault(entry.getKey(), 0L)));
            }
        }
        return rows;
    }

    /**
     * Every article clicked in the window, by either route: a latest-articles tile, or a link on
     * the page that opens it. The link route is credited through the link's <em>current</em>
     * address, so a deleted link's clicks -- whose address is gone with it -- stay in the link
     * table as «изтрита връзка» and are not attributed here. Narrowed by {@code ?source=} like the
     * rest of the screen.
     */
    private List<ArticleRow> articleRows(Map<Integer, Map<SocialSource, Long>> blockByArticle,
                                                List<LinkRow> linkRows, SocialSource sourceFilter,
                                                long views, Map<Integer, String> titles) {
        Map<Integer, Long> fromBlock = new LinkedHashMap<>();
        blockByArticle.forEach((id, bySource) -> fromBlock.put(id, sourceFilter == null
                ? bySource.values().stream().mapToLong(Long::longValue).sum()
                : bySource.getOrDefault(sourceFilter, 0L)));
        Map<Integer, Long> fromLinks = new LinkedHashMap<>();
        for (LinkRow link : linkRows) {
            if (link.articleId() != null && link.clicks() > 0) {
                fromLinks.merge(link.articleId(), link.clicks(), Long::sum);
            }
        }

        Set<Integer> ids = new LinkedHashSet<>(fromBlock.keySet());
        ids.addAll(fromLinks.keySet());
        List<ArticleRow> rows = new ArrayList<>();
        for (Integer id : ids) {
            long block = fromBlock.getOrDefault(id, 0L);
            long linked = fromLinks.getOrDefault(id, 0L);
            if (block + linked > 0) {
                rows.add(new ArticleRow(id, titles.getOrDefault(id, t("pages.social.article", id)), block, linked,
                        block + linked, rate(block + linked, views)));
            }
        }
        rows.sort(Comparator.comparingLong(ArticleRow::clicks).reversed().thenComparingInt(ArticleRow::id));
        return rows;
    }

    /**
     * Titles in the original language, plain text -- the site's rule, {@code Article.titleIn}.
     * Empty on failure.
     */
    private Map<Integer, String> articleTitles(Set<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> titles = new HashMap<>();
        try {
            for (Article article : articleRepository.findTitlesByIdIn(ids)) {
                String title = MgmtSocialService.plain(article.titleIn(LanguageChain.original()));
                if (title != null) {
                    titles.put(article.getId(), title);
                }
            }
        } catch (DataAccessException e) {
            log.error("Could not name the articles clicked on /social: {}", e.getMessage());
        }
        return titles;
    }

    /**
     * Link × source. Columns are the sources that have any click in the window, busiest first;
     * the latest-articles block is one more row. Unfiltered by {@code ?source=} on purpose -- the
     * matrix is where the sources are compared.
     */
    private Matrix matrix(List<LinkRow> links, Map<String, Map<SocialSource, Long>> clicksByLink,
                                 Map<Integer, Map<SocialSource, Long>> blockByArticle, Map<Integer, String> titles) {
        Map<SocialSource, Long> columnTotals = new EnumMap<>(SocialSource.class);
        clicksByLink.values().forEach(bySource -> bySource.forEach((s, c) -> columnTotals.merge(s, c, Long::sum)));
        blockByArticle.values().forEach(bySource -> bySource.forEach((s, c) -> columnTotals.merge(s, c, Long::sum)));

        List<SocialSource> columns = columnTotals.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<SocialSource, Long>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .toList();
        if (columns.isEmpty()) {
            return new Matrix(List.of(), List.of());
        }

        List<MatrixRow> rows = new ArrayList<>();
        for (LinkRow link : links) {
            Map<SocialSource, Long> bySource = clicksByLink.getOrDefault(link.id(), Map.of());
            long total = bySource.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                rows.add(new MatrixRow(link.label(), link.id(), link.articleId(),
                        columns.stream().map(s -> bySource.getOrDefault(s, 0L)).toList(), total));
            }
        }
        // One row per article clicked in the latest-articles block, busiest first.
        List<MatrixRow> blockRows = new ArrayList<>();
        blockByArticle.forEach((id, bySource) -> {
            long total = bySource.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                blockRows.add(new MatrixRow(titles.getOrDefault(id, t("pages.social.article", id)), null, id,
                        columns.stream().map(s -> bySource.getOrDefault(s, 0L)).toList(), total));
            }
        });
        blockRows.sort(Comparator.comparingLong(MatrixRow::total).reversed().thenComparing(MatrixRow::articleId));
        rows.addAll(blockRows);
        return new Matrix(columns, rows);
    }

    /** {@code part} per 100 of {@code whole}, or {@code null} when there is no whole to divide. */
    static Double rate(long part, long whole) {
        return whole <= 0 ? null : part * 100d / whole;
    }

    private static long sum(Map<String, Long> counts) {
        long total = 0;
        for (Long count : counts.values()) {
            total += nz(count);
        }
        return total;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static Integer parseId(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
