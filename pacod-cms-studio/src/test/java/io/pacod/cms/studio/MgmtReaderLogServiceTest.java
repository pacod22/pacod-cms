package io.pacod.cms.studio;

import io.pacod.cms.core.data.ReaderBrowser;
import io.pacod.cms.core.data.ReaderDevice;
import io.pacod.cms.core.data.ReaderOs;
import io.pacod.cms.core.data.ReaderVisit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reader log's reads: the filter as the query string gives it, the query it becomes, and the
 * aggregations' answers as MongoDB sends them.
 */
class MgmtReaderLogServiceTest {

    private static final Instant FROM = Instant.parse("2026-10-27T00:00:00Z");

    private MongoTemplate mongoTemplate;
    private MgmtReaderLogService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        service = new MgmtReaderLogService(mongoTemplate);
    }

    @Test
    @DisplayName("a filter is read leniently: an enum by name in any case, a code, an address spelled one way")
    void filtersAreNormalised() {
        MgmtReaderLogService.Filter filter = MgmtReaderLogService.Filter.of(Map.of(
                "type", "article", "served", "DE", "preferred", "und", "browser", " chrome ",
                "os", "ios", "device", "Mobile", "ip", "2001:DB8::1", "q", "  /article/ "));

        assertThat(filter.get(ReaderDimension.PAGE)).isEqualTo("ARTICLE");
        assertThat(filter.get(ReaderDimension.LANGUAGE)).isEqualTo("de");
        assertThat(filter.get(ReaderDimension.BROWSER_LANGUAGE)).isEqualTo("und");
        assertThat(filter.get(ReaderDimension.BROWSER)).isEqualTo("CHROME");
        assertThat(filter.get(ReaderDimension.OS)).isEqualTo("IOS");
        assertThat(filter.get(ReaderDimension.DEVICE)).isEqualTo("MOBILE");
        assertThat(filter.ip()).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(filter.q()).isEqualTo("/article/");
        assertThat(filter.isActive()).isTrue();
    }

    @Test
    @DisplayName("a value that names nothing is no filter, never an exception or an echo")
    void junkIsNoFilter() {
        MgmtReaderLogService.Filter filter = MgmtReaderLogService.Filter.of(Map.of(
                "type", "HOMEPAGE", "served", "v1-x", "browser", "netscape", "os", "$where",
                "ip", "example.com", "ua", "   ", "q", ""));

        assertThat(filter.isActive()).isFalse();
        assertThat(filter.params()).isEmpty();
    }

    @Test
    @DisplayName("every link carries every filter, each value encoded whole -- & and = included")
    void queriesCarryEveryFilter() {
        Map<String, String> params = new HashMap<>();
        params.put("browser", "CHROME");
        params.put("served", "de");
        params.put("ua", "Agent/1.0 (a=b&c=d)");
        params.put("q", "/p/a b");
        MgmtReaderLogService.Filter filter = MgmtReaderLogService.Filter.of(params);

        assertThat(filter.query(MonitoringRange.D7))
                .isEqualTo("range=7d&served=de&browser=CHROME&ua=Agent%2F1.0+%28a%3Db%26c%3Dd%29&q=%2Fp%2Fa+b");
        assertThat(filter.query(MonitoringRange.D7, 2)).endsWith("&page=2");
        assertThat(filter.without(ReaderDimension.BROWSER).with(ReaderDimension.OS, "ANDROID").query(MonitoringRange.H24))
                .isEqualTo("range=24h&served=de&os=ANDROID&ua=Agent%2F1.0+%28a%3Db%26c%3Dd%29&q=%2Fp%2Fa+b");
        assertThat(filter.withUserAgent(null).withQ(null).withIp("198.51.100.7").query(MonitoringRange.H24))
                .isEqualTo("range=24h&served=de&browser=CHROME&ip=198.51.100.7");
        assertThat(filter.paramsExcept(List.of("browser", "q")))
                .extracting(Map.Entry::getKey).containsExactly("served", "ua");
        assertThat(filter.value("browser")).isEqualTo("CHROME");
        assertThat(filter.value("nonsense")).isNull();
    }

    @Test
    @DisplayName("every filter reaches the query: an address and a User-Agent exactly, the path quoted")
    void filtersReachTheQuery() {
        when(mongoTemplate.find(any(Query.class), eq(ReaderVisit.class))).thenReturn(List.of());

        service.log(MgmtReaderLogService.Filter.of(Map.of("browser", "SAFARI", "served", "bg",
                "ip", "198.51.100.7", "ua", "Mozilla.*", "q", "/article/(1")), FROM, 0);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(ReaderVisit.class));
        String json = query.getValue().getQueryObject().toJson();
        assertThat(json).contains("\"browser\": \"SAFARI\"", "\"servedLanguage\": \"bg\"",
                "\"ip\": \"198.51.100.7\"", "\"userAgent\": \"Mozilla.*\"", "\\\\Q/article/(1\\\\E");
        assertThat(json).doesNotContain("\"ip\": {\"$regex\"");
    }

    @Test
    @DisplayName("the log pages by fetching one row more than it shows, and fails to an empty page")
    void logPages() {
        List<ReaderVisit> rows = new java.util.ArrayList<>();
        for (int i = 0; i < MgmtReaderLogService.PAGE_SIZE + 1; i++) {
            rows.add(new ReaderVisit());
        }
        when(mongoTemplate.find(any(Query.class), eq(ReaderVisit.class))).thenReturn(rows);

        MgmtReaderLogService.LogPage page = service.log(MgmtReaderLogService.Filter.none(), FROM, 2);

        assertThat(page.rows()).hasSize(MgmtReaderLogService.PAGE_SIZE);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.from()).isEqualTo(101);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(ReaderVisit.class));
        assertThat(query.getValue().getSkip()).isEqualTo(100);
        assertThat(query.getValue().getLimit()).isEqualTo(MgmtReaderLogService.PAGE_SIZE + 1);

        when(mongoTemplate.find(any(Query.class), eq(ReaderVisit.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(service.log(MgmtReaderLogService.Filter.none(), FROM, 0).rows()).isEmpty();
    }

    @Test
    @DisplayName("the splits come from one $facet, readers counted by grouping twice, never by collecting them")
    void reportReadsTheFacets() {
        Document facets = new Document()
                .append("views", List.of(new Document("n", 40)))
                .append("readers", List.of(new Document("n", 15)))
                .append("addresses", List.of(new Document("n", 9)))
                .append("BROWSER", List.of(new Document("_id", "CHROME").append("views", 30).append("readers", 11),
                        new Document("_id", "SAFARI").append("views", 10L).append("readers", 4)))
                .append("LANGUAGE", List.of(new Document("_id", "de").append("views", 25).append("readers", 9),
                        new Document("_id", "und").append("views", 15).append("readers", 6)))
                .append("DEVICE", List.of());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(ReaderVisit.COLLECTION), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(facets), new Document()));

        MgmtReaderLogService.Report report = service.report(
                MgmtReaderLogService.Filter.of(Map.of("os", "ANDROID")), FROM, Locale.GERMAN);

        assertThat(report.totals()).isEqualTo(new MgmtReaderLogService.Totals(40, 15, 9));
        assertThat(report.tables()).extracting(MgmtReaderLogService.SplitTable::dimension)
                .containsExactly(ReaderDimension.values());
        MgmtReaderLogService.SplitTable browsers = table(report, ReaderDimension.BROWSER);
        assertThat(browsers.rows()).first().satisfies(row -> {
            assertThat(row.value()).isEqualTo(ReaderBrowser.CHROME);
            assertThat(row.views()).isEqualTo(30);
            assertThat(row.readers()).isEqualTo(11);
            assertThat(row.share()).isEqualTo(75d);
        });
        MgmtReaderLogService.SplitTable languages = table(report, ReaderDimension.LANGUAGE);
        assertThat(languages.rows()).extracting(MgmtReaderLogService.Split::name).containsExactly("Deutsch", "Unknown");
        assertThat(table(report, ReaderDimension.DEVICE).rows()).isEmpty();

        ArgumentCaptor<Aggregation> aggregation = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(aggregation.capture(), eq(ReaderVisit.COLLECTION), eq(Document.class));
        String pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
        assertThat(pipeline).contains("$facet", "os=ANDROID", "_id.visitor").doesNotContain("$addToSet");
    }

    @Test
    @DisplayName("an address row carries its newest view's families; a failed read is an empty table")
    void topAddressesReadTheNewest() {
        Document row = new Document("_id", "198.51.100.7").append("views", 12).append("readers", 2)
                .append("last", Date.from(Instant.parse("2026-11-03T08:15:30Z")))
                .append("latest", new Document("browser", "FIREFOX").append("os", "LINUX").append("device", "DESKTOP"));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(ReaderVisit.COLLECTION), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(row), new Document()));

        List<MgmtReaderLogService.AddressRow> rows = service.topAddresses(
                MgmtReaderLogService.Filter.of(Map.of("ip", "198.51.100.7")), FROM);

        assertThat(rows).singleElement().isEqualTo(new MgmtReaderLogService.AddressRow("198.51.100.7", 12, 2,
                Instant.parse("2026-11-03T08:15:30Z"), ReaderBrowser.FIREFOX, ReaderOs.LINUX, ReaderDevice.DESKTOP));
        // Filtered by one address, the "has an address" condition is not added beside it -- the
        // query would refuse a second condition on the field.
        ArgumentCaptor<Aggregation> aggregation = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(aggregation.capture(), eq(ReaderVisit.COLLECTION), eq(Document.class));
        assertThat(aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString())
                .contains("$top", "ip=198.51.100.7").doesNotContain("$addToSet");

        when(mongoTemplate.aggregate(any(Aggregation.class), eq(ReaderVisit.COLLECTION), eq(Document.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(service.topAddresses(MgmtReaderLogService.Filter.none(), FROM)).isEmpty();
        assertThat(service.topAgents(MgmtReaderLogService.Filter.none(), FROM)).isEmpty();
        assertThat(service.report(MgmtReaderLogService.Filter.none(), FROM, Locale.ENGLISH).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a range longer than the log is cut at its 31 days, and says so")
    void windowIsClampedToRetention() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 12, 0);

        assertThat(MgmtReaderLogService.window(MonitoringRange.M12, now).clamped()).isTrue();
        assertThat(MgmtReaderLogService.window(MonitoringRange.D30, now).clamped()).isFalse();
        assertThat(MgmtReaderLogService.window(MonitoringRange.H24, now).clamped()).isFalse();
    }

    private static MgmtReaderLogService.SplitTable table(MgmtReaderLogService.Report report, ReaderDimension dimension) {
        return report.tables().stream().filter(t -> t.dimension() == dimension).findFirst().orElseThrow();
    }
}
