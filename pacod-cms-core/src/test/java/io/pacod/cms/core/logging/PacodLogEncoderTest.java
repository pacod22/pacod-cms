package io.pacod.cms.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event of pacod-demo/docs/LOGGING.md, section 4, as both applications write it: the five keys
 * first and in order, the timestamp's exact shape, the levels, the fields flat and typed, the
 * renaming of a reserved name, the error fields, and the text line.
 */
class PacodLogEncoderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant WHEN = Instant.parse("2026-10-23T06:41:07Z");

    private LoggerContext context;
    private Logger logger;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        // SLF4J's own adapter, so MDC.put reaches the events of this context.
        context.setMDCAdapter(MDC.getMDCAdapter());
        logger = context.getLogger("io.pacod.cms.site.Example");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        context.stop();
    }

    private LoggingEvent event(Level level, String message, Throwable throwable) {
        LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, level, message, throwable, null);
        event.setInstant(WHEN);
        return event;
    }

    private static String json(ILoggingEvent event) {
        return LogEvents.json(event, "cms-site");
    }

    private static List<String> keys(String json) {
        List<String> keys = new ArrayList<>();
        JSON.readTree(json).propertyNames().forEach(keys::add);
        return keys;
    }

    @Test
    void theFiveKeysComeFirstAndInOrder() {
        LoggingEvent event = event(Level.INFO, "cms-site is up", null);
        event.addKeyValuePair(new KeyValuePair("port", 8443));

        String line = json(event);

        assertThat(line).isEqualTo("{\"timestamp\":\"2026-10-23T06:41:07.000Z\",\"level\":\"info\","
                + "\"service\":\"cms-site\",\"logger\":\"io.pacod.cms.site.Example\","
                + "\"message\":\"cms-site is up\",\"port\":8443}");
        assertThat(keys(line)).containsExactly("timestamp", "level", "service", "logger", "message", "port");
    }

    @Test
    void timestampIsUtcWithExactlyThreeFractionDigits() {
        LoggingEvent event = event(Level.INFO, "m", null);
        event.setInstant(Instant.parse("2026-01-02T03:04:05.678912345Z"));
        assertThat(JSON.readTree(json(event)).get("timestamp").asString()).isEqualTo("2026-01-02T03:04:05.678Z");

        event.setInstant(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(JSON.readTree(json(event)).get("timestamp").asString())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
                .isEqualTo("2026-01-02T03:04:05.000Z");
    }

    @Test
    void levelsAreLowerCase() {
        assertThat(JSON.readTree(json(event(Level.TRACE, "m", null))).get("level").asString()).isEqualTo("trace");
        assertThat(JSON.readTree(json(event(Level.DEBUG, "m", null))).get("level").asString()).isEqualTo("debug");
        assertThat(JSON.readTree(json(event(Level.INFO, "m", null))).get("level").asString()).isEqualTo("info");
        assertThat(JSON.readTree(json(event(Level.WARN, "m", null))).get("level").asString()).isEqualTo("warn");
        assertThat(JSON.readTree(json(event(Level.ERROR, "m", null))).get("level").asString()).isEqualTo("error");
    }

    @Test
    void mdcAndKeyValuePairsAreFlatAndTyped() {
        LoggingEvent event = event(Level.INFO, "flushed", null);
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("request_id", "r-1");
        mdc.put("count", "007");
        event.setMDCPropertyMap(mdc);
        event.addKeyValuePair(new KeyValuePair("site", "demo"));
        event.addKeyValuePair(new KeyValuePair("views", 12L));
        event.addKeyValuePair(new KeyValuePair("ratio", 0.5));
        event.addKeyValuePair(new KeyValuePair("ok", true));
        event.addKeyValuePair(new KeyValuePair("nothing", null));
        event.addKeyValuePair(new KeyValuePair("infinite", Double.POSITIVE_INFINITY));
        event.addKeyValuePair(new KeyValuePair("path", List.of("a", "b")));

        JsonNode node = JSON.readTree(json(event));

        assertThat(node.get("request_id").asString()).isEqualTo("r-1");
        assertThat(node.get("count").isString()).as("MDC values are strings").isTrue();
        assertThat(node.get("count").asString()).isEqualTo("007");
        assertThat(node.get("site").asString()).isEqualTo("demo");
        assertThat(node.get("views").isIntegralNumber()).isTrue();
        assertThat(node.get("views").asLong()).isEqualTo(12L);
        assertThat(node.get("ratio").isFloatingPointNumber()).isTrue();
        assertThat(node.get("ok").isBoolean()).isTrue();
        assertThat(node.get("nothing").isNull()).isTrue();
        assertThat(node.get("infinite").asString()).isEqualTo("Infinity");
        assertThat(node.get("path").asString()).isEqualTo("[a, b]");
        assertThat(node.has("fields")).isFalse();
    }

    @Test
    void theEventsOwnFieldWinsOverTheMdc() {
        LoggingEvent event = event(Level.INFO, "m", null);
        event.setMDCPropertyMap(Map.of("site", "from-mdc"));
        event.addKeyValuePair(new KeyValuePair("site", "from-event"));

        String line = json(event);

        assertThat(JSON.readTree(line).get("site").asString()).isEqualTo("from-event");
        assertThat(line.indexOf("\"site\"")).isEqualTo(line.lastIndexOf("\"site\""));
    }

    @Test
    void aReservedNameGetsATrailingUnderscore() {
        LoggingEvent event = event(Level.INFO, "the message", null);
        event.setMDCPropertyMap(Map.of("service", "spoofed"));
        event.addKeyValuePair(new KeyValuePair("message", "a field"));
        event.addKeyValuePair(new KeyValuePair("level", 3));
        event.addKeyValuePair(new KeyValuePair("timestamp", "x"));
        event.addKeyValuePair(new KeyValuePair("logger", "y"));

        String line = json(event);
        JsonNode node = JSON.readTree(line);

        assertThat(node.get("message").asString()).isEqualTo("the message");
        assertThat(node.get("message_").asString()).isEqualTo("a field");
        assertThat(node.get("service").asString()).isEqualTo("cms-site");
        assertThat(node.get("service_").asString()).isEqualTo("spoofed");
        assertThat(node.get("level_").asInt()).isEqualTo(3);
        assertThat(keys(line)).startsWith("timestamp", "level", "service", "logger", "message")
                .contains("timestamp_", "logger_").doesNotHaveDuplicates();
    }

    @Test
    void anExceptionAddsErrorAndStackTrace() {
        IllegalStateException cause = new IllegalStateException("disk full");
        RuntimeException thrown = new RuntimeException("flush failed", cause);
        LoggingEvent event = event(Level.ERROR, "could not flush", thrown);
        event.addKeyValuePair(new KeyValuePair("error", "a field called error"));

        String line = json(event);
        JsonNode node = JSON.readTree(line);

        assertThat(node.get("error").asString()).isEqualTo("java.lang.RuntimeException: flush failed");
        assertThat(node.get("error_").asString()).isEqualTo("a field called error");
        String trace = node.get("stack_trace").asString();
        assertThat(trace).startsWith("java.lang.RuntimeException: flush failed")
                .contains("\tat io.pacod.cms.core.logging.PacodLogEncoderTest")
                .contains("Caused by: java.lang.IllegalStateException: disk full")
                .doesNotEndWith("\n");
        assertThat(keys(line)).endsWith("error", "stack_trace");
        assertThat(line).doesNotContain("\n");
    }

    @Test
    void anExceptionWithoutAMessageIsItsClass() {
        LoggingEvent event = event(Level.WARN, "m", new NullPointerException());
        assertThat(JSON.readTree(json(event)).get("error").asString()).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void stringsAreEscaped() {
        LoggingEvent event = event(Level.INFO, "say \"hi\"\\ \n\tnext \u0001 ünï  ", null);
        event.addKeyValuePair(new KeyValuePair("k\"ey", "v\nv"));

        String line = json(event);

        assertThat(line).doesNotContain("\n").doesNotContain("\u0001").doesNotContain(" ");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asString()).isEqualTo("say \"hi\"\\ \n\tnext \u0001 ünï  ");
        assertThat(node.get("k\"ey").asString()).isEqualTo("v\nv");
    }

    @Test
    void anEmptyMessageIsNamedByItsLogger() {
        assertThat(JSON.readTree(json(event(Level.INFO, "", null))).get("message").asString())
                .isEqualTo("io.pacod.cms.site.Example");
    }

    @Test
    void textLineHasTheSpecShape() {
        LoggingEvent event = event(Level.INFO, "cms-site is up", null);
        event.setMDCPropertyMap(Map.of("request_id", "r-1"));
        event.addKeyValuePair(new KeyValuePair("public", "0.0.0.0:8443"));
        event.addKeyValuePair(new KeyValuePair("note", "two words"));
        event.addKeyValuePair(new KeyValuePair("blank", ""));

        assertThat(LogEvents.text(event, "cms-site")).isEqualTo("2026-10-23T06:41:07.000Z INFO  cms-site "
                + "io.pacod.cms.site.Example: cms-site is up request_id=r-1 public=0.0.0.0:8443 "
                + "note=\"two words\" blank=\"\"");
        assertThat(LogEvents.text(event(Level.ERROR, "m", null), "cms-site")).contains(" ERROR cms-site ");
        assertThat(LogEvents.text(event(Level.WARN, "m", null), "cms-site")).contains(" WARN  cms-site ");
    }

    @Test
    void textPutsTheStackTraceOnTheFollowingLines() {
        String text = LogEvents.text(event(Level.ERROR, "failed", new IllegalArgumentException("bad")), "cms-studio");

        String[] lines = text.split("\n");
        assertThat(lines[0]).endsWith("cms-studio io.pacod.cms.site.Example: failed");
        assertThat(lines[1]).isEqualTo("java.lang.IllegalArgumentException: bad");
        assertThat(lines[2]).startsWith("\tat ");
    }

    @Test
    void encoderPicksTheFormatAndWritesOneLineWithoutColour() {
        PacodLogEncoder encoder = new PacodLogEncoder();
        encoder.setService("cms-studio");
        LoggingEvent event = event(Level.WARN, "careful", null);

        encoder.setFormat("TEXT");
        String text = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("2026-10-23T06:41:07.000Z WARN  cms-studio io.pacod.cms.site.Example: careful\n");

        for (String json : new String[] {"json", "Json", "", null, "yaml"}) {
            encoder.setFormat(json);
            String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertThat(line).startsWith("{\"timestamp\"").endsWith("}\n");
            assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);
        }
        assertThat(text).doesNotContain("\u001b");
    }

    @Test
    void throughLogbackWithSlf4jsFluentApiAndTheMdc() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacodLogEncoder encoder = new PacodLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("json");
        encoder.setService("cms-site");
        encoder.start();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(out);
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAndStopAllAppenders();
        root.addAppender(appender);
        root.setLevel(Level.INFO);

        MDC.put("request_id", "abc");
        org.slf4j.Logger log = context.getLogger("io.pacod.cms.site.Flush");
        log.atInfo().addKeyValue("site", "demo").addKeyValue("rows", 3).log("flushed {} rows", 3);
        log.debug("not at info");

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertThat(lines).hasSize(1);
        JsonNode node = JSON.readTree(lines[0]);
        assertThat(keys(lines[0])).containsExactly("timestamp", "level", "service", "logger", "message",
                "request_id", "site", "rows");
        assertThat(node.get("message").asString()).isEqualTo("flushed 3 rows");
        assertThat(node.get("logger").asString()).isEqualTo("io.pacod.cms.site.Flush");
        assertThat(node.get("rows").asInt()).isEqualTo(3);
        assertThat(lines[0]).doesNotContain("thread");
    }

    @Test
    void levelSettingFollowsTheConvention() {
        assertThat(LogLevelDefiner.normalize("debug")).isEqualTo("DEBUG");
        assertThat(LogLevelDefiner.normalize("INFO")).isEqualTo("INFO");
        assertThat(LogLevelDefiner.normalize(" Warn ")).isEqualTo("WARN");
        assertThat(LogLevelDefiner.normalize("warning")).isEqualTo("WARN");
        assertThat(LogLevelDefiner.normalize("WARNING")).isEqualTo("WARN");
        assertThat(LogLevelDefiner.normalize("error")).isEqualTo("ERROR");
        assertThat(LogLevelDefiner.normalize("trace")).isEqualTo("TRACE");
        assertThat(LogLevelDefiner.normalize("verbose")).isEqualTo("INFO");
        assertThat(LogLevelDefiner.normalize("")).isEqualTo("INFO");
        assertThat(LogLevelDefiner.normalize(null)).isEqualTo("INFO");
    }

    @Test
    void logbackConfigurationReadsTheDefinerAndTheEncoder() throws Exception {
        String xml = """
                <configuration>
                  <property name="LOG_LEVEL_SETTING" value="Warning"/>
                  <define name="LOG_LEVEL" class="io.pacod.cms.core.logging.LogLevelDefiner">
                    <level>${LOG_LEVEL_SETTING}</level>
                  </define>
                  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder class="io.pacod.cms.core.logging.PacodLogEncoder">
                      <format>text</format>
                      <service>cms-studio</service>
                    </encoder>
                  </appender>
                  <root level="${LOG_LEVEL}">
                    <appender-ref ref="CONSOLE"/>
                  </root>
                </configuration>
                """;
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.getLevel()).isEqualTo(Level.WARN);
        var appender = (ch.qos.logback.core.ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
        PacodLogEncoder encoder = (PacodLogEncoder) appender.getEncoder();
        assertThat(encoder.getFormat()).isEqualTo("text");
        assertThat(encoder.getService()).isEqualTo("cms-studio");
    }
}
