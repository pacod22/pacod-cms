package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Locale;

/**
 * A host name a website answers at. The host is the id, so one host can belong to one website
 * only -- a second claim is a duplicate key, never a silent takeover.
 */
@Data
@Document(collection = "domain")
public class Domain {

    @Id
    private String host;

    @Indexed(name = "domain_websiteId")
    private String websiteId;

    /** The address absolute URLs and redirects name; one per website. */
    private boolean primary;

    private Instant createdOn;

    public Domain() {
    }

    public Domain(String host, String websiteId, boolean primary) {
        this.host = normalise(host);
        this.websiteId = websiteId;
        this.primary = primary;
        this.createdOn = Instant.now();
    }

    /** Lowercase, no port, no trailing dot: the form a Host header is compared in. */
    public static String normalise(String host) {
        if (host == null) {
            return null;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }
}
