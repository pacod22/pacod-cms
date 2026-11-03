package io.pacod.cms.studio;

import io.pacod.cms.core.data.ReaderBrowser;
import io.pacod.cms.core.data.ReaderDevice;
import io.pacod.cms.core.data.ReaderOs;
import io.pacod.cms.core.data.ReaderVisit;
import io.pacod.cms.core.util.IpLiterals;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything the reader log reads ({@code /mgmt/monitoring/readers}): who read the website, from
 * which address and with which browser, over the {@code readerVisit} rows the site writes.
 *
 * <h2>Rows, not buckets</h2>
 * The monitoring screen's reader cards sum the buckets, which are kept for every range but hold
 * only views per key, one split at a time. Everything here is read off the rows instead, which is
 * what lets it filter one split by another ("Chrome on Android, reading German") and count
 * distinct readers -- and what limits it to {@link #RETENTION}, the rows' TTL. A longer range is
 * cut there, and the screen says so ({@link Window#clamped()}).
 *
 * <h2>Distinct readers never through {@code $addToSet}</h2>
 * A reader is a {@code visitor} hash. Each count groups twice -- by value and reader, then by value
 * -- so no group ever holds a set of every reader of, say, Chrome in a month.
 *
 * <p>Every read degrades to empty and an ERROR log, card by card -- the monitoring screen's rule.
 * The addresses are personal data: this screen shows them to whoever holds {@code stats.read}, and
 * the rows go away on their own after {@link #RETENTION}.
 */
@Slf4j
@Service
public class MgmtReaderLogService {

    /** How long {@code readerVisit} keeps a row -- the TTL on {@link ReaderVisit#getOccurredAt()}. */
    public static final Duration RETENTION = Duration.ofDays(ReaderVisit.RETENTION_DAYS);

    public static final int PAGE_SIZE = 50;

    /** Rows in each split, and in the addresses and user-agents tables. */
    static final int TOP = 20;

    private static final int MAX_QUERY = 200;

    private final MongoTemplate mongoTemplate;
    private MgmtText text;

    public MgmtReaderLogService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Words the unknown language in the operator's language; without it, English. */
    @Autowired(required = false)
    public void setText(MgmtText text) {
        this.text = text;
    }

    private String msg(String key, Object... args) {
        return text == null ? MgmtText.base(key, args) : text.msg(key, args);
    }

    /* ------------------------------------------------------------------ the filter */

    /**
     * The log's filters, normalised: a stored key per {@link ReaderDimension}, an exact address, an
     * exact User-Agent and a path fragment. Everything comes off a query string and nothing is
     * echoed as typed -- an unknown browser or a non-address is no filter.
     */
    public record Filter(Map<ReaderDimension, String> values, String ip, String userAgent, String q) {

        public static final String PARAM_IP = "ip";
        public static final String PARAM_USER_AGENT = "ua";
        public static final String PARAM_QUERY = "q";

        public Filter {
            values = values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
        }

        public static Filter none() {
            return new Filter(Map.of(), null, null, null);
        }

        /** Reads every filter off the request's parameters; the rest of them are ignored. */
        public static Filter of(Map<String, String> params) {
            Map<ReaderDimension, String> values = new EnumMap<>(ReaderDimension.class);
            for (ReaderDimension dimension : ReaderDimension.values()) {
                String key = dimension.normalise(params.get(dimension.getParam()));
                if (key != null) {
                    values.put(dimension, key);
                }
            }
            return new Filter(values, IpLiterals.normalise(params.get(PARAM_IP)),
                    capped(params.get(PARAM_USER_AGENT), ReaderVisit.MAX_USER_AGENT),
                    capped(params.get(PARAM_QUERY), MAX_QUERY));
        }

        private static String capped(String value, int max) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String trimmed = value.strip();
            return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
        }

        public String get(ReaderDimension dimension) {
            return values.get(dimension);
        }

        /** The key filtered by under a dimension's parameter name -- what a template can spell. */
        public String value(String param) {
            for (ReaderDimension dimension : ReaderDimension.values()) {
                if (dimension.getParam().equals(param)) {
                    return values.get(dimension);
                }
            }
            return null;
        }

        /** {@link #params()} but the named ones: the hidden fields of a form that has its own controls for those. */
        public List<Map.Entry<String, String>> paramsExcept(List<String> names) {
            return params().stream().filter(param -> !names.contains(param.getKey())).toList();
        }

        public boolean isActive() {
            return !values.isEmpty() || ip != null || userAgent != null || q != null;
        }

        public Filter with(ReaderDimension dimension, String key) {
            Map<ReaderDimension, String> next = new EnumMap<>(ReaderDimension.class);
            next.putAll(values);
            next.put(dimension, key);
            return new Filter(next, ip, userAgent, q);
        }

        public Filter without(ReaderDimension dimension) {
            Map<ReaderDimension, String> next = new EnumMap<>(ReaderDimension.class);
            next.putAll(values);
            next.remove(dimension);
            return new Filter(next, ip, userAgent, q);
        }

        public Filter withIp(String address) {
            return new Filter(values, address, userAgent, q);
        }

        public Filter withUserAgent(String agent) {
            return new Filter(values, ip, agent, q);
        }

        public Filter withQ(String term) {
            return new Filter(values, ip, userAgent, term);
        }

        /**
         * Every filter as {@code name=value} pairs, in a fixed order: the hidden fields of a form
         * that must not drop them. A GET form replaces the whole query string.
         */
        public List<Map.Entry<String, String>> params() {
            List<Map.Entry<String, String>> params = new ArrayList<>();
            for (ReaderDimension dimension : ReaderDimension.values()) {
                if (values.containsKey(dimension)) {
                    params.add(Map.entry(dimension.getParam(), values.get(dimension)));
                }
            }
            if (ip != null) {
                params.add(Map.entry(PARAM_IP, ip));
            }
            if (userAgent != null) {
                params.add(Map.entry(PARAM_USER_AGENT, userAgent));
            }
            if (q != null) {
                params.add(Map.entry(PARAM_QUERY, q));
            }
            return params;
        }

        /** The query string of this filter on {@code range}'s log: every link on the screen is one. */
        public String query(MonitoringRange range) {
            return query(range, 0);
        }

        /**
         * The same, on page {@code page} of the log; page 0 is left out. Every value is encoded
         * whole, {@code &}, {@code =} and {@code +} included: a path or a User-Agent may hold any
         * of them, and a URI builder leaves them as they are in a query.
         */
        public String query(MonitoringRange range, int page) {
            StringBuilder query = new StringBuilder("range=").append(encoded(range.getKey()));
            for (Map.Entry<String, String> param : params()) {
                query.append('&').append(param.getKey()).append('=').append(encoded(param.getValue()));
            }
            if (page > 0) {
                query.append("&page=").append(page);
            }
            return query.toString();
        }

        private static String encoded(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        /** What the rows must match, from {@code from} on. */
        Criteria criteria(Instant from) {
            Criteria criteria = Criteria.where(ReaderVisit.FIELD_OCCURRED_AT).gte(Date.from(from));
            for (ReaderDimension dimension : ReaderDimension.values()) {
                if (values.containsKey(dimension)) {
                    criteria = criteria.and(dimension.getField()).is(values.get(dimension));
                }
            }
            // Exact, never a pattern: an address and a User-Agent are matched as stored.
            if (ip != null) {
                criteria = criteria.and(ReaderVisit.FIELD_IP).is(ip);
            }
            if (userAgent != null) {
                criteria = criteria.and(ReaderVisit.FIELD_USER_AGENT).is(userAgent);
            }
            if (q != null) {
                // Quoted: the term comes off a query string -- the crawler log's reason.
                criteria = criteria.and(ReaderVisit.FIELD_PATH)
                        .regex(Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE));
            }
            return criteria;
        }
    }

    /* ------------------------------------------------------------------ view models */

    /** Where the log starts reading: the range's start, or the retention boundary. */
    public record Window(Instant from, boolean clamped) {
    }

    /** @param addresses distinct IP addresses */
    public record Totals(long views, long readers, long addresses) {

        static final Totals NONE = new Totals(0, 0, 0);
    }

    /**
     * One row of a split.
     *
     * @param value its constant, for the label; {@code null} for a language
     * @param name  a language's name in the operator's language; {@code null} otherwise
     * @param share of the filtered views
     */
    public record Split(String key, Enum<?> value, String name, long views, long readers, Double share) {
    }

    /** One split's table: the rows grouped by {@code dimension}, under the filter. */
    public record SplitTable(ReaderDimension dimension, List<Split> rows) {
    }

    public record Report(Totals totals, List<SplitTable> tables) {

        static final Report EMPTY = new Report(Totals.NONE, List.of());

        public boolean isEmpty() {
            return totals.views() == 0;
        }
    }

    /** One address, with the families of the newest view from it. */
    public record AddressRow(String ip, long views, long readers, Instant lastSeen, ReaderBrowser browser,
                             ReaderOs os, ReaderDevice device) {
    }

    /** One raw User-Agent and the families the site read off it. */
    public record AgentRow(String userAgent, ReaderBrowser browser, ReaderOs os, ReaderDevice device,
                           long views, long readers) {
    }

    public record LogPage(List<ReaderVisit> rows, int page, boolean hasNext) {

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

    /* ------------------------------------------------------------------ the reads */

    /** Where the log-backed cards start reading for this range. */
    public static Window window(MonitoringRange range, LocalDateTime now) {
        Instant from = range.from(now).atZone(ZoneId.systemDefault()).toInstant();
        Instant earliest = now.atZone(ZoneId.systemDefault()).toInstant().minus(RETENTION);
        return from.isBefore(earliest) ? new Window(earliest, true) : new Window(from, false);
    }

    /**
     * The totals and every split, under the filter, in one aggregation: a {@code $facet} with a
     * pipeline per split, so the rows are read once however many tables the screen shows.
     *
     * @param ui the operator's language, which the language names are in
     */
    public Report report(Filter filter, Instant from, Locale ui) {
        Document facets = new Document()
                .append("views", List.of(new Document("$count", "n")))
                .append("readers", distinct(ReaderVisit.FIELD_VISITOR))
                .append("addresses", distinct(ReaderVisit.FIELD_IP));
        for (ReaderDimension dimension : ReaderDimension.values()) {
            facets.append(dimension.name(), split(dimension.getField()));
        }

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(filter.criteria(from)),
                stage(new Document("$facet", facets)));
        try {
            Document result = mongoTemplate.aggregate(aggregation, ReaderVisit.COLLECTION, Document.class)
                    .getUniqueMappedResult();
            if (result == null) {
                return Report.EMPTY;
            }
            long views = countOf(result, "views");
            Totals totals = new Totals(views, countOf(result, "readers"), countOf(result, "addresses"));
            String unknown = msg("ops.mon.lang.und");

            List<SplitTable> tables = new ArrayList<>();
            for (ReaderDimension dimension : ReaderDimension.values()) {
                List<Split> rows = new ArrayList<>();
                for (Document row : documents(result.get(dimension.name()))) {
                    String key = row.get("_id") == null ? null : row.get("_id").toString();
                    if (key == null) {
                        continue;
                    }
                    long count = longOf(row.get("views"));
                    rows.add(new Split(key, dimension.constant(key),
                            dimension.isLanguage() ? ReaderDimension.languageName(key, ui, unknown) : null,
                            count, longOf(row.get("readers")), views == 0 ? null : count * 100.0 / views));
                }
                tables.add(new SplitTable(dimension, rows));
            }
            return new Report(totals, tables);
        } catch (DataAccessException e) {
            log.error("Could not read the reader log's splits: {}", e.getMessage());
            return Report.EMPTY;
        }
    }

    /** The addresses with the most views under the filter, newest view's families beside each. */
    public List<AddressRow> topAddresses(Filter filter, Instant from) {
        Criteria criteria = filter.criteria(from);
        if (filter.ip() == null) {
            criteria = criteria.and(ReaderVisit.FIELD_IP).ne(null);
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(criteria),
                // By address and reader first, then by address: the readers are counted, never collected.
                stage(new Document("$group", new Document("_id", new Document("ip", "$" + ReaderVisit.FIELD_IP)
                        .append("visitor", "$" + ReaderVisit.FIELD_VISITOR))
                        .append("views", new Document("$sum", 1))
                        .append("last", new Document("$max", "$" + ReaderVisit.FIELD_OCCURRED_AT))
                        .append("latest", newest(new Document("at", "$" + ReaderVisit.FIELD_OCCURRED_AT)
                                .append("browser", "$" + ReaderVisit.FIELD_BROWSER)
                                .append("os", "$" + ReaderVisit.FIELD_OS)
                                .append("device", "$" + ReaderVisit.FIELD_DEVICE), "$" + ReaderVisit.FIELD_OCCURRED_AT)))),
                stage(new Document("$group", new Document("_id", "$_id.ip")
                        .append("views", new Document("$sum", "$views"))
                        .append("readers", readerCount("$_id.visitor"))
                        .append("last", new Document("$max", "$last"))
                        .append("latest", newest("$latest", "$last")))),
                stage(new Document("$sort", new Document("views", -1).append("_id", 1))),
                stage(new Document("$limit", TOP)));
        List<AddressRow> rows = new ArrayList<>();
        try {
            for (Document row : mongoTemplate.aggregate(aggregation, ReaderVisit.COLLECTION, Document.class)) {
                Document latest = row.get("latest") instanceof Document document ? document : new Document();
                rows.add(new AddressRow(String.valueOf(row.get("_id")), longOf(row.get("views")),
                        longOf(row.get("readers")), instantOf(row.get("last")),
                        ReaderBrowser.of(latest.getString("browser")), ReaderOs.of(latest.getString("os")),
                        ReaderDevice.of(latest.getString("device"))));
            }
        } catch (DataAccessException e) {
            log.error("Could not read the reader log's addresses: {}", e.getMessage());
            return List.of();
        }
        return rows;
    }

    /** The User-Agents with the most views under the filter, as sent, with what the site read off them. */
    public List<AgentRow> topAgents(Filter filter, Instant from) {
        Criteria criteria = filter.criteria(from);
        if (filter.userAgent() == null) {
            criteria = criteria.and(ReaderVisit.FIELD_USER_AGENT).ne(null);
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(criteria),
                stage(new Document("$group", new Document("_id", new Document("ua", "$" + ReaderVisit.FIELD_USER_AGENT)
                        .append("visitor", "$" + ReaderVisit.FIELD_VISITOR))
                        .append("views", new Document("$sum", 1))
                        .append("browser", new Document("$first", "$" + ReaderVisit.FIELD_BROWSER))
                        .append("os", new Document("$first", "$" + ReaderVisit.FIELD_OS))
                        .append("device", new Document("$first", "$" + ReaderVisit.FIELD_DEVICE)))),
                stage(new Document("$group", new Document("_id", "$_id.ua")
                        .append("views", new Document("$sum", "$views"))
                        .append("readers", readerCount("$_id.visitor"))
                        .append("browser", new Document("$first", "$browser"))
                        .append("os", new Document("$first", "$os"))
                        .append("device", new Document("$first", "$device")))),
                stage(new Document("$sort", new Document("views", -1).append("_id", 1))),
                stage(new Document("$limit", TOP)));
        List<AgentRow> rows = new ArrayList<>();
        try {
            for (Document row : mongoTemplate.aggregate(aggregation, ReaderVisit.COLLECTION, Document.class)) {
                rows.add(new AgentRow(String.valueOf(row.get("_id")), ReaderBrowser.of(row.getString("browser")),
                        ReaderOs.of(row.getString("os")), ReaderDevice.of(row.getString("device")),
                        longOf(row.get("views")), longOf(row.get("readers"))));
            }
        } catch (DataAccessException e) {
            log.error("Could not read the reader log's user-agents: {}", e.getMessage());
            return List.of();
        }
        return rows;
    }

    /**
     * One page of the log, newest first, under the filter. One row more than it shows, to know
     * whether there is a next page without a count -- the crawler log's reason.
     */
    public LogPage log(Filter filter, Instant from, int page) {
        int safePage = Math.max(0, page);
        Query query = new Query(filter.criteria(from))
                .with(Sort.by(Sort.Order.desc(ReaderVisit.FIELD_OCCURRED_AT)))
                .skip((long) safePage * PAGE_SIZE)
                .limit(PAGE_SIZE + 1);
        try {
            List<ReaderVisit> found = mongoTemplate.find(query, ReaderVisit.class);
            boolean hasNext = found.size() > PAGE_SIZE;
            return new LogPage(hasNext ? found.subList(0, PAGE_SIZE) : found, safePage, hasNext);
        } catch (DataAccessException e) {
            log.error("Could not read the reader log: {}", e.getMessage());
            return new LogPage(List.of(), safePage, false);
        }
    }

    /* ------------------------------------------------------------------ internals */

    /** A raw stage, for what the typed builders do not spell: {@code $facet}, {@code $top}. */
    private static AggregationOperation stage(Document stage) {
        return context -> stage;
    }

    /** Counts the distinct non-null values of {@code field}: group by it, then count the groups. */
    private static List<Document> distinct(String field) {
        return List.of(
                new Document("$match", new Document(field, new Document("$ne", null))),
                new Document("$group", new Document("_id", "$" + field)),
                new Document("$count", "n"));
    }

    /** One split's pipeline: views and distinct readers per value, the largest first. */
    private static List<Document> split(String field) {
        return List.of(
                new Document("$group", new Document("_id", new Document("key", "$" + field)
                        .append("visitor", "$" + ReaderVisit.FIELD_VISITOR))
                        .append("views", new Document("$sum", 1))),
                new Document("$group", new Document("_id", "$_id.key")
                        .append("views", new Document("$sum", "$views"))
                        .append("readers", readerCount("$_id.visitor"))),
                new Document("$sort", new Document("views", -1).append("_id", 1)),
                new Document("$limit", TOP));
    }

    /**
     * One per group whose reader is known; a view without a hash is a view, not a reader. {@code $gt}
     * against null, not {@code $ne}: the mapper writes no field for a null hash, and a missing field
     * is not equal to null. {@code Arrays.asList}, because {@code List.of} refuses the null.
     */
    private static Document readerCount(String visitor) {
        return new Document("$sum", new Document("$cond",
                List.of(new Document("$gt", java.util.Arrays.asList(visitor, null)), 1, 0)));
    }

    /** {@code output} of the newest document in the group, by {@code at}. */
    private static Document newest(Object output, String at) {
        return new Document("$top", new Document("sortBy", new Document(at.substring(1), -1)).append("output", output));
    }

    private static long countOf(Document facets, String name) {
        List<Document> rows = documents(facets.get(name));
        return rows.isEmpty() ? 0 : longOf(rows.getFirst().get("n"));
    }

    private static List<Document> documents(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return documents;
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
