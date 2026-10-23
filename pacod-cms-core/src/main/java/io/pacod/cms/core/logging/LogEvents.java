package io.pacod.cms.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.slf4j.event.KeyValuePair;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pacod log event (pacod-demo/docs/LOGGING.md, section 4), as one JSON object or one text line.
 * Both apps' logback-spring.xml reach it through {@link PacodLogEncoder}.
 *
 * <p>JSON: {@code timestamp}, {@code level}, {@code service}, {@code logger}, {@code message}, in that
 * order; then the event's fields flat at the top level -- the MDC first, then the key-value pairs of
 * SLF4J's fluent API ({@code log.atInfo().addKeyValue("site", id).log("...")}), which win over an MDC
 * entry of the same name; then {@code error} and {@code stack_trace} when a throwable is attached.
 * Nothing else: no thread, no host, no colour codes.
 *
 * <p>Text: {@code <timestamp> <LEVEL> <service> <logger>: <message> key=value ...}, the stack trace
 * on the following lines. Never coloured: it goes to a container log or a file.
 *
 * <p>Built by hand rather than with a JSON library: a logging encoder must not depend on the
 * application's mapper, nor fail when it is misconfigured.
 */
public final class LogEvents {

    /** The five keys every event starts with; a field of the same name gets a trailing underscore. */
    static final Set<String> RESERVED = Set.of("timestamp", "level", "service", "logger", "message");

    /** RFC 3339, UTC, always exactly three fraction digits (Instant.toString() drops ".000"). */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private LogEvents() {
    }

    /** The event as one JSON object, without the trailing newline. */
    public static String json(ILoggingEvent event, String service) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"timestamp\":");
        appendString(out, timestamp(event));
        out.append(",\"level\":");
        appendString(out, level(event));
        out.append(",\"service\":");
        appendString(out, service);
        out.append(",\"logger\":");
        appendString(out, logger(event));
        out.append(",\"message\":");
        appendString(out, message(event));
        IThrowableProxy throwable = event.getThrowableProxy();
        for (Map.Entry<String, Object> field : fields(event, throwable != null).entrySet()) {
            out.append(',');
            appendString(out, field.getKey());
            out.append(':');
            appendValue(out, field.getValue());
        }
        if (throwable != null) {
            out.append(",\"error\":");
            appendString(out, error(throwable));
            out.append(",\"stack_trace\":");
            appendString(out, stackTrace(throwable));
        }
        return out.append('}').toString();
    }

    /** The event as one text line, plus the stack trace on the following lines; no trailing newline. */
    public static String text(ILoggingEvent event, String service) {
        StringBuilder out = new StringBuilder(160);
        out.append(timestamp(event)).append(' ');
        String level = level(event).toUpperCase(Locale.ROOT);
        out.append(level);
        out.repeat(' ', Math.max(0, 5 - level.length()));
        out.append(' ').append(service).append(' ').append(logger(event)).append(": ").append(message(event));
        IThrowableProxy throwable = event.getThrowableProxy();
        for (Map.Entry<String, Object> field : fields(event, throwable != null).entrySet()) {
            out.append(' ').append(field.getKey()).append('=');
            appendTextValue(out, field.getValue());
        }
        if (throwable != null) {
            out.append('\n').append(stackTrace(throwable));
        }
        return out.toString();
    }

    static String timestamp(ILoggingEvent event) {
        Instant instant = event.getInstant();
        return TIMESTAMP.format(instant != null ? instant : Instant.ofEpochMilli(event.getTimeStamp()));
    }

    /** Lower case; logback has no FATAL, but a bridged one is an error. */
    static String level(ILoggingEvent event) {
        String level = event.getLevel() == null ? "info" : event.getLevel().toString().toLowerCase(Locale.ROOT);
        return switch (level) {
            case "fatal" -> "error";
            case "warning" -> "warn";
            default -> level;
        };
    }

    private static String logger(ILoggingEvent event) {
        String logger = event.getLoggerName();
        return logger == null || logger.isEmpty() ? "root" : logger;
    }

    /** Never empty: an event without a message is named by its logger. */
    private static String message(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        return message == null || message.isEmpty() ? logger(event) : message;
    }

    /**
     * The MDC, then the event's own key-value pairs, which replace an MDC entry of the same name.
     * Reserved names -- and {@code error}/{@code stack_trace} when a throwable will add those --
     * get a trailing underscore, so no key is ever written twice.
     */
    static Map<String, Object> fields(ILoggingEvent event, boolean hasThrowable) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, String> mdc;
        try {
            mdc = event.getMDCPropertyMap();
        } catch (RuntimeException noMdcAdapter) {
            mdc = null; // a logger context without an MDC adapter: an event without context fields
        }
        if (mdc != null) {
            mdc.forEach((key, value) -> fields.put(fieldName(key, hasThrowable), value));
        }
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                if (pair != null && pair.key != null) {
                    String name = fieldName(pair.key, hasThrowable);
                    fields.remove(name); // the event's own field takes the MDC entry's place, at the end
                    fields.put(name, pair.value);
                }
            }
        }
        return fields;
    }

    private static String fieldName(String key, boolean hasThrowable) {
        if (RESERVED.contains(key) || (hasThrowable && (key.equals("error") || key.equals("stack_trace")))) {
            return key + "_";
        }
        return key;
    }

    /** "ExceptionClass: message", or the class alone when the throwable has no message. */
    static String error(IThrowableProxy throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClassName() : throwable.getClassName() + ": " + message;
    }

    /** The full trace -- causes and suppressed included -- as one string, without a trailing newline. */
    static String stackTrace(IThrowableProxy throwable) {
        String trace = ThrowableProxyUtil.asString(throwable);
        int end = trace.length();
        while (end > 0 && (trace.charAt(end - 1) == '\n' || trace.charAt(end - 1) == '\r')) {
            end--;
        }
        return trace.substring(0, end);
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger || value instanceof BigDecimal) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) {
                out.append(value);
            } else {
                appendString(out, value.toString());
            }
        } else {
            appendString(out, String.valueOf(value));
        }
    }

    /** A JSON string: quotes, backslashes and every control character escaped. */
    static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Below 0x20, DEL, and the two separators JavaScript reads as line ends.
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A text field value: bare unless it is empty or holds a space, a quote or an equals sign. */
    private static void appendTextValue(StringBuilder out, Object value) {
        String text = String.valueOf(value);
        boolean quote = text.isEmpty();
        for (int i = 0; i < text.length() && !quote; i++) {
            char c = text.charAt(i);
            quote = Character.isWhitespace(c) || c == '"' || c == '=';
        }
        if (!quote) {
            out.append(text);
            return;
        }
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
    }
}
