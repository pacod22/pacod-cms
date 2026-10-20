package io.pacod.cms.core.design;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The studio's permission for its PDF renderer to read a website's print view without counting:
 * the {@code X-Pacod-Print} header Gotenberg's Chromium sends with every request of a render.
 *
 * <p>The same shared key as {@link PreviewTokens} under another purpose ({@code pacod-print}), so
 * neither token opens the other's door. {@code r1.<site>.<expiry>.<mac>}, bound to one website and
 * {@link #TTL}: long enough for one render and its images. Caddy removes the header on the way in
 * ({@code platform/caddy/cms.caddy}); the site's port is published directly, which is why it is
 * signed at all.
 */
@Component
public class PrintTokens {

    public static final String HEADER = "X-Pacod-Print";
    public static final Duration TTL = Duration.ofMinutes(5);
    private static final String VERSION = "r1";
    private static final String PURPOSE = "pacod-print";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,39}");

    private final SharedSecret key;

    public PrintTokens(@Value("${pacod.preview.secret:}") String secret) {
        this.key = new SharedSecret(secret);
    }

    public boolean enabled() {
        return key.present();
    }

    public String mint(String siteId) {
        return mint(siteId, Instant.now().plus(TTL));
    }

    public String mint(String siteId, Instant expires) {
        if (!key.present()) {
            throw new IllegalStateException("PDF export is off: PACOD_PREVIEW_SECRET is blank");
        }
        if (siteId == null || !ID.matcher(siteId).matches()) {
            throw new IllegalArgumentException("Not a website id");
        }
        String payload = VERSION + "." + siteId + "." + expires.getEpochSecond();
        return payload + "." + key.mac(PURPOSE, payload);
    }

    /** Whether the token is genuine, unexpired at {@code now}, and for {@code siteId}. */
    public boolean verify(String token, String siteId, Instant now) {
        if (!key.present() || token == null || token.length() > 200 || siteId == null) {
            return false;
        }
        int cut = token.lastIndexOf('.');
        if (cut < 0) {
            return false;
        }
        String payload = token.substring(0, cut);
        if (!key.matches(PURPOSE, payload, token.substring(cut + 1))) {
            return false;
        }
        String[] parts = payload.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || !parts[1].equals(siteId)) {
            return false;
        }
        try {
            return now.isBefore(Instant.ofEpochSecond(Long.parseLong(parts[2])));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
