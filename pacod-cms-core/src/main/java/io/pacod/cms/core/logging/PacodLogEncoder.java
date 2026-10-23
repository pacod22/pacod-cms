package io.pacod.cms.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The logback encoder of both apps (logback-spring.xml): one pacod log event per line
 * (pacod-demo/docs/LOGGING.md), as JSON or as text.
 *
 * <pre>{@code
 * <encoder class="io.pacod.cms.core.logging.PacodLogEncoder">
 *   <format>${LOG_FORMAT}</format>   <!-- json | text, any case; anything else: json -->
 *   <service>${SERVICE}</service>    <!-- SERVICE_NAME: cms-site, cms-studio -->
 * </encoder>
 * }</pre>
 *
 * The format is a property of the encoder rather than a choice between two appenders, so it stays
 * independent of the Spring profile and needs no Janino for a logback {@code <if>}.
 */
public class PacodLogEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] NO_BYTES = new byte[0];

    private boolean text;
    private String service = "pacod-cms";

    /** {@code text} (any case) for text; anything else, blank included, is the default: JSON. */
    public void setFormat(String format) {
        this.text = format != null && format.trim().toLowerCase(Locale.ROOT).equals("text");
    }

    public String getFormat() {
        return text ? "text" : "json";
    }

    public void setService(String service) {
        if (service != null && !service.isBlank()) {
            this.service = service.trim();
        }
    }

    public String getService() {
        return service;
    }

    @Override
    public byte[] headerBytes() {
        return NO_BYTES;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        String line = text ? LogEvents.text(event, service) : LogEvents.json(event, service);
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] footerBytes() {
        return NO_BYTES;
    }
}
