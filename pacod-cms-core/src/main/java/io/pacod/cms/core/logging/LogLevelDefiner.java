package io.pacod.cms.core.logging;

import ch.qos.logback.core.PropertyDefinerBase;

import java.util.Locale;

/**
 * APP_LOG_LEVEL (app.log.level) as a logback level, by the pacod convention
 * (pacod-demo/docs/LOGGING.md, section 3): {@code debug}, {@code info}, {@code warn}, {@code error}
 * (and {@code trace}), any case; {@code warning} means warn; anything else means info.
 *
 * <p>Needed because logback reads an unknown level -- {@code warning} included -- as DEBUG.
 *
 * <pre>{@code
 * <define name="LOG_LEVEL" class="io.pacod.cms.core.logging.LogLevelDefiner">
 *   <level>${APP_LOG_LEVEL_RAW}</level>
 * </define>
 * }</pre>
 */
public class LogLevelDefiner extends PropertyDefinerBase {

    private String level;

    public void setLevel(String level) {
        this.level = level;
    }

    @Override
    public String getPropertyValue() {
        return normalize(level);
    }

    static String normalize(String level) {
        if (level == null) {
            return "INFO";
        }
        return switch (level.trim().toLowerCase(Locale.ROOT)) {
            case "trace" -> "TRACE";
            case "debug" -> "DEBUG";
            case "warn", "warning" -> "WARN";
            case "error" -> "ERROR";
            default -> "INFO";
        };
    }
}
