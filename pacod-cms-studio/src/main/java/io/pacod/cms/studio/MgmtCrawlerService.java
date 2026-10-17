package io.pacod.cms.studio;

import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.CrawlerVisit;
import io.pacod.cms.core.data.SiteMetric;
import io.pacod.cms.core.repository.CrawlerVisitRepository;
import io.pacod.cms.core.repository.SiteMetricDayRepository;
import io.pacod.cms.core.repository.SiteMetricHourRepository;
import io.pacod.cms.core.util.CrawlerClassifier;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code /mgmt/crawlers}: which crawlers reached the public site, what they asked for, what they
 * were answered, and whether they were who they claimed to be.
 *
 * <p>The data comes from two places:
 * <ul>
 *   <li><b>The traffic buckets</b> -- {@code crawlerHitsByBot} and its three siblings, written by
 *       the public site's flush beside everything {@code /mgmt/monitoring} reads. The tiles,
 *       the chart, the per-bot table and the categories come from these, so they cover every
 *       {@link MonitoringRange}, twelve months included, and cost one bucket read.</li>
 *   <li><b>{@code crawlerVisit}</b>, one row per request, kept {@link #RETENTION}. The log, the top
 *       paths and "last seen" come from here, so they reach back at most that far -- and the screen
 *       says so when the range is longer.</li>
 * </ul>
 *
 * <h2>Two answers that are easy to get wrong</h2>
 * <ul>
 *   <li><b>"Last seen" is read over the whole retained collection</b>, not the selected window.
 *       The maximum inside the window would, on the default 24 hours, report a Googlebot visit 25
 *       hours ago as "not visited in over 48 h".</li>
 *   <li><b>The warning waits until there is history to judge by.</b> On a fresh install the
 *       collection is empty, and "Googlebot has not been here in 48 hours" would be the flat zero
 *       line {@code /mgmt/monitoring} refuses to draw. Until the oldest row is itself
 *       {@link #STALE_AFTER} old, the screen says the data is still being collected instead.</li>
 * </ul>
 *
 * <p>Every read degrades to zeroes or an empty list and an ERROR log, card by card -- the
 * monitoring screen's rule. A forged visit does not count as the bot having been here: the
 * warning reads the newest visit that was <em>not</em> {@link CrawlerVerification#UNVERIFIED}.
 */
@Slf4j
@Service
public class MgmtCrawlerService {

    /** How long {@code crawlerVisit} keeps a row -- the TTL on {@link CrawlerVisit#getOccurredAt()}. */
    public static final Duration RETENTION = Duration.ofDays(31);

    /** The age past which a watched crawler's absence is worth a warning. */
    public static final Duration STALE_AFTER = Duration.ofHours(48);

    /** The crawlers whose absence means the site is not being indexed. */
    public static final List<String> WATCHED = List.of("googlebot", "bingbot");

    public static final int PAGE_SIZE = 50;
    static final int TOP_PATHS = 20;

    private final SiteMetricHourRepository hourRepository;
    private final SiteMetricDayRepository dayRepository;
    private final CrawlerVisitRepository visitRepository;
    private final MongoTemplate mongoTemplate;

    public MgmtCrawlerService(SiteMetricHourRepository hourRepository,
                              SiteMetricDayRepository dayRepository,
                              CrawlerVisitRepository visitRepository,
                              MongoTemplate mongoTemplate) {
        this.hourRepository = hourRepository;
        this.dayRepository = dayRepository;
        this.visitRepository = visitRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /** One bucket of the window, gap-filled. Maps are keyed by bot id and never null. */
    public record Bucket(LocalDateTime start, String axisLabel, String tooltipLabel,
                         Map<String, Long> hits, Map<String, Long> errors,
                         Map<String, Long> verified, Map<String, Long> unverified) {
    }

    public record Totals(long hits, long errors, long spoofed, int bots) {
    }

    /** {@code lastSeen} is over the whole retained log, or {@code null} when it holds no row for the bot. */
    public record BotRow(String bot, CrawlerCategory category, long hits, long errors, long verified,
                         long spoofed, Instant lastSeen) {
    }

    public record CategoryRow(CrawlerCategory category, long hits, Double share) {
    }

    public record Report(Totals totals, MgmtChart.Chart chart, List<BotRow> bots, List<CategoryRow> categories) {
    }

    public record PathRow(String path, long hits, long errors) {
    }

    /** A watched crawler not seen for {@link #STALE_AFTER}; {@code lastSeen} null means not within the retained log. */
    public record Stale(String bot, Instant lastSeen) {
    }

    /**
     * The stale-crawler warning, or why there is none. {@code collecting}: the log is younger than
     * {@link #STALE_AFTER}, so an absence proves nothing yet; {@code since} is its oldest row, or
     * {@code null} when it is empty.
     */
    public record Watch(boolean collecting, Instant since, List<Stale> stale) {

        static Watch unavailable() {
            return new Watch(false, null, List.of());
        }
    }

    /** The log's filters, already normalised -- see {@code MgmtCrawlerController}. */
    public record LogFilter(String bot, CrawlerCategory category, CrawlerVerification verification,
                            boolean errorsOnly, String q) {

        public boolean isActive() {
            return bot != null || category != null || verification != null || errorsOnly || q != null;
        }
    }

    public record LogPage(List<CrawlerVisit> rows, int page, boolean hasNext) {

        public boolean hasPrevious() {
            return page > 0;
        }

        public int from() {
            return page * PAGE_SIZE + 1;
        }

        public int to() {
            return page * PAGE_SIZE + rows.size();
        }
    }

    /**
     * Where the log's part of the screen starts reading: the range's start, or the retention
     * boundary when the range reaches further back than any row does.
     */
    public record Window(Instant from, boolean clamped) {
    }

    /**
     * Instants as the screen prints them, in this JVM's zone -- the zone the traffic buckets' ids
     * are written in. A model object rather than {@code #temporals}, which needs a zone it cannot
     * be given for an {@link Instant}.
     *
     * <p>{@code text} words the relative times in the operator's language; without it -- as a
     * unit test constructs it -- they are English (the base bundle), never the JVM's default
     * locale.
     */
    public record Times(Instant now, ZoneId zone, MgmtText text) {

        private static final java.time.format.DateTimeFormatter STAMP =
                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        private static final java.time.format.DateTimeFormatter DAY =
                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

        public Times(Instant now, ZoneId zone) {
            this(now, zone, null);
        }

        public static Times at(Instant now, MgmtText text) {
            return new Times(now, ZoneId.systemDefault(), text);
        }

        private String msg(String key, Object... args) {
            return text == null ? MgmtText.base(key, args) : text.msg(key, args);
        }

        public String stamp(Instant at) {
            return at == null ? "—" : STAMP.withZone(zone).format(at);
        }

        public String day(Instant at) {
            return at == null ? "—" : DAY.withZone(zone).format(at);
        }

        /** Minutes, then hours up to 48, then days. */
        public String ago(Instant at) {
            if (at == null) {
                return "—";
            }
            long minutes = Math.max(0, Duration.between(at, now).toMinutes());
            if (minutes < 1) {
                return msg("ops.crawl.ago.now");
            }
            if (minutes < 60) {
                return msg("ops.crawl.ago.minutes", minutes);
            }
            long hours = minutes / 60;
            if (hours < 48) {
                return msg("ops.crawl.ago.hours", hours);
            }
            return msg("ops.crawl.ago.days", hours / 24);
        }
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
            log.error("Could not read the crawler series; the screen will show zeroes: {}", e.getMessage());
        }

        List<Bucket> buckets = new ArrayList<>(range.getBuckets());
        for (int i = 0; i < range.getBuckets(); i++) {
            LocalDateTime start = from.plus(i, range.getGrain());
            SiteMetric metric = recorded.get(start);
            buckets.add(new Bucket(start, range.formatAxis(start), range.formatTooltip(start),
                    metric == null ? Map.of() : metric.getCrawlerHitsByBot(),
                    metric == null ? Map.of() : metric.getCrawlerErrorsByBot(),
                    metric == null ? Map.of() : metric.getCrawlerVerifiedByBot(),
                    metric == null ? Map.of() : metric.getCrawlerUnverifiedByBot()));
        }
        return buckets;
    }

    /* ------------------------------------------------------------------ the report */

    /**
     * Tiles, chart, per-bot and per-category tables -- all from the buckets, so all for the period
     * and nothing else; the log's filters do not reach them, and the screen labels them so.
     *
     * @param lastSeen {@link #lastSeen()}, read once by the controller
     */
    public Report report(List<Bucket> buckets, Map<String, Instant> lastSeen) {
        Map<String, Long> hits = new HashMap<>();
        Map<String, Long> errors = new HashMap<>();
        Map<String, Long> verified = new HashMap<>();
        Map<String, Long> spoofed = new HashMap<>();
        for (Bucket bucket : buckets) {
            bucket.hits().forEach((bot, n) -> hits.merge(bot, nz(n), Long::sum));
            bucket.errors().forEach((bot, n) -> errors.merge(bot, nz(n), Long::sum));
            bucket.verified().forEach((bot, n) -> verified.merge(bot, nz(n), Long::sum));
            bucket.unverified().forEach((bot, n) -> spoofed.merge(bot, nz(n), Long::sum));
        }

        Map<String, Instant> seen = lastSeen == null ? Map.of() : lastSeen;
        List<BotRow> bots = new ArrayList<>();
        hits.forEach((bot, n) -> {
            if (n > 0) {
                bots.add(new BotRow(bot, CrawlerClassifier.categoryOf(bot), n, errors.getOrDefault(bot, 0L),
                        verified.getOrDefault(bot, 0L), spoofed.getOrDefault(bot, 0L), seen.get(bot)));
            }
        });
        bots.sort(Comparator.comparingLong(BotRow::hits).reversed().thenComparing(BotRow::bot));

        long total = bots.stream().mapToLong(BotRow::hits).sum();
        Totals totals = new Totals(total,
                bots.stream().mapToLong(BotRow::errors).sum(),
                bots.stream().mapToLong(BotRow::spoofed).sum(),
                bots.size());

        Map<CrawlerCategory, Long> byCategory = new EnumMap<>(CrawlerCategory.class);
        for (BotRow row : bots) {
            byCategory.merge(row.category(), row.hits(), Long::sum);
        }
        List<CategoryRow> categories = new ArrayList<>();
        byCategory.forEach((category, n) -> categories.add(new CategoryRow(category, n, share(n, total))));
        categories.sort(Comparator.comparingLong(CategoryRow::hits).reversed());

        return new Report(totals, chart(buckets, byCategory.keySet()), bots, categories);
    }

    /**
     * One line per category that has any hits in the window, in the enum's order so a category
     * keeps its colour from one range to the next. No line for a category with nothing in it: five
     * flat lines along the baseline would read as five things measured at zero.
     */
    private static MgmtChart.Chart chart(List<Bucket> buckets, java.util.Set<CrawlerCategory> present) {
        List<MgmtChart.Input> inputs = new ArrayList<>();
        for (CrawlerCategory category : CrawlerCategory.values()) {
            if (!present.contains(category)) {
                continue;
            }
            List<Long> values = new ArrayList<>(buckets.size());
            for (Bucket bucket : buckets) {
                long sum = 0;
                for (Map.Entry<String, Long> entry : bucket.hits().entrySet()) {
                    if (CrawlerClassifier.categoryOf(entry.getKey()) == category) {
                        sum += nz(entry.getValue());
                    }
                }
                values.add(sum);
            }
            // The label is the bundle key; mgmt/chart resolves it in the operator's language.
            inputs.add(new MgmtChart.Input(category.getChartKey(), MgmtText.enumKey(category, "label"), values));
        }
        return MgmtChart.of(buckets.stream().map(Bucket::axisLabel).toList(),
                buckets.stream().map(Bucket::tooltipLabel).toList(), inputs);
    }

    /* ------------------------------------------------------------------ the log */

    /**
     * The newest visit per bot in the retained log, forged ones included -- this is the per-bot
     * table's column, not the warning. One {@code $sort} + {@code $group $first}, which MongoDB
     * serves off the {@code {bot, occurredAt}} index. Empty on failure; the column then shows a
     * dash.
     */
    public Map<String, Instant> lastSeen() {
        Map<String, Instant> seen = new HashMap<>();
        try {
            Aggregation aggregation = Aggregation.newAggregation(
                    Aggregation.sort(Sort.by(Sort.Order.asc(CrawlerVisit.FIELD_BOT),
                            Sort.Order.desc(CrawlerVisit.FIELD_OCCURRED_AT))),
                    Aggregation.group(CrawlerVisit.FIELD_BOT).first(CrawlerVisit.FIELD_OCCURRED_AT).as("last"));
            for (Document row : mongoTemplate.aggregate(aggregation, CrawlerVisit.COLLECTION, Document.class)) {
                Object bot = row.get("_id");
                Instant last = instantOf(row.get("last"));
                if (bot != null && last != null) {
                    seen.put(bot.toString(), last);
                }
            }
        } catch (DataAccessException e) {
            log.error("Could not read when each crawler was last seen: {}", e.getMessage());
            return Map.of();
        }
        return seen;
    }

    /** The warning for {@link #WATCHED} crawlers; see the class javadoc for when it holds back. */
    public Watch watch(Instant now) {
        try {
            Optional<CrawlerVisit> oldest = visitRepository.findFirstByOrderByOccurredAtAsc();
            Instant since = oldest.map(CrawlerVisit::getOccurredAt).orElse(null);
            Instant threshold = now.minus(STALE_AFTER);
            if (since == null || since.isAfter(threshold)) {
                return new Watch(true, since, List.of());
            }

            List<Stale> stale = new ArrayList<>();
            for (String bot : WATCHED) {
                Instant last = visitRepository
                        .findFirstByBotAndVerificationNotOrderByOccurredAtDesc(bot, CrawlerVerification.UNVERIFIED)
                        .map(CrawlerVisit::getOccurredAt)
                        .orElse(null);
                if (last == null || last.isBefore(threshold)) {
                    stale.add(new Stale(bot, last));
                }
            }
            return new Watch(false, since, stale);
        } catch (DataAccessException e) {
            // No warning rather than a false one: a database that cannot be read is not evidence
            // that Googlebot stopped coming.
            log.error("Could not check the watched crawlers: {}", e.getMessage());
            return Watch.unavailable();
        }
    }

    /** Where the log-backed cards start reading for this range. */
    public static Window window(MonitoringRange range, LocalDateTime now) {
        Instant from = range.from(now).atZone(ZoneId.systemDefault()).toInstant();
        Instant earliest = now.atZone(ZoneId.systemDefault()).toInstant().minus(RETENTION);
        return from.isBefore(earliest) ? new Window(earliest, true) : new Window(from, false);
    }

    /** The most-fetched paths since {@code from}, every crawler, with the errors among them. */
    public List<PathRow> topPaths(Instant from) {
        List<PathRow> rows = new ArrayList<>();
        try {
            Aggregation aggregation = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where(CrawlerVisit.FIELD_OCCURRED_AT).gte(Date.from(from))),
                    Aggregation.group(CrawlerVisit.FIELD_PATH)
                            .count().as("hits")
                            .sum(ConditionalOperators.when(Criteria.where(CrawlerVisit.FIELD_STATUS).gte(400))
                                    .then(1).otherwise(0)).as("errors"),
                    Aggregation.sort(Sort.by(Sort.Order.desc("hits"), Sort.Order.asc("_id"))),
                    Aggregation.limit(TOP_PATHS));
            for (Document row : mongoTemplate.aggregate(aggregation, CrawlerVisit.COLLECTION, Document.class)) {
                Object path = row.get("_id");
                rows.add(new PathRow(path == null ? "" : path.toString(), longOf(row.get("hits")),
                        longOf(row.get("errors"))));
            }
        } catch (DataAccessException e) {
            log.error("Could not read the crawlers' top paths: {}", e.getMessage());
            return List.of();
        }
        return rows;
    }

    /**
     * One page of the log since {@code from}, newest first, narrowed by the filters. Fetches one
     * row more than it shows to know whether there is a next page, without a count -- a
     * {@code countDocuments} over a filtered month of rows is the expensive half.
     */
    public LogPage log(LogFilter filter, Instant from, int page) {
        int safePage = Math.max(0, page);
        Criteria criteria = Criteria.where(CrawlerVisit.FIELD_OCCURRED_AT).gte(Date.from(from));
        if (filter.bot() != null) {
            criteria = criteria.and(CrawlerVisit.FIELD_BOT).is(filter.bot());
        }
        if (filter.category() != null) {
            criteria = criteria.and(CrawlerVisit.FIELD_CATEGORY).is(filter.category().name());
        }
        if (filter.verification() != null) {
            criteria = criteria.and(CrawlerVisit.FIELD_VERIFICATION).is(filter.verification().name());
        }
        if (filter.errorsOnly()) {
            criteria = criteria.and(CrawlerVisit.FIELD_STATUS).gte(400);
        }
        if (filter.q() != null) {
            // Quoted: the term comes off a query string, and an unescaped "(" or "*" is a malformed
            // pattern rather than a search for that character -- WebArticleService.escapeForRegex's
            // reason, and the image library's derived query's.
            criteria = criteria.and(CrawlerVisit.FIELD_PATH)
                    .regex(Pattern.compile(Pattern.quote(filter.q()), Pattern.CASE_INSENSITIVE));
        }

        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.desc(CrawlerVisit.FIELD_OCCURRED_AT)))
                .skip((long) safePage * PAGE_SIZE)
                .limit(PAGE_SIZE + 1);
        try {
            List<CrawlerVisit> found = mongoTemplate.find(query, CrawlerVisit.class);
            boolean hasNext = found.size() > PAGE_SIZE;
            return new LogPage(hasNext ? found.subList(0, PAGE_SIZE) : found, safePage, hasNext);
        } catch (DataAccessException e) {
            log.error("Could not read the crawler log: {}", e.getMessage());
            return new LogPage(List.of(), safePage, false);
        }
    }

    /* ------------------------------------------------------------------ internals */

    private static Double share(long part, long whole) {
        return whole == 0 ? null : part * 100.0 / whole;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }
}
