package io.pacod.cms.core.design;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The studio's permission for a browser to see a website's drafts: {@code ?_preview=<token>}.
 *
 * <p>Minted by the studio, checked by the site -- two JVMs, so the key is a shared secret,
 * {@code pacod.preview.secret} ({@code PACOD_PREVIEW_SECRET}) on both. <b>Blank switches preview
 * off</b> rather than inventing a key: a random one per start could never match across the two.
 * A secret shorter than {@value #MIN_SECRET} characters fails the start.
 *
 * <p>A token is {@code p1.<site>.<set or ->.<expiry>.<mac>}: readable, because nothing in it is
 * secret, and bound to one website (the site compares it with the website the Host names), to an
 * optional template set to render with, and to {@link #TTL}. The MAC is HMAC-SHA256 over the
 * payload under the purpose {@code pacod-preview} ({@link SharedSecret}), so a print token
 * ({@link PrintTokens}) under the same secret is not a preview token. Compared in constant time.
 */
@Slf4j
@Component
public class PreviewTokens {

    /** How long a preview stays open; the studio mints a new token on every render. */
    public static final Duration TTL = Duration.ofMinutes(30);
    static final int MIN_SECRET = SharedSecret.MIN;
    private static final String VERSION = "p1";
    private static final String PURPOSE = "pacod-preview";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,39}");

    /** What a valid token allows. {@code templateSet} null: the website's own. */
    public record Claims(String siteId, String templateSet, Instant expires) {
    }

    private final SharedSecret key;

    public PreviewTokens(@Value("${pacod.preview.secret:}") String secret) {
        this.key = new SharedSecret(secret);
        if (!key.present()) {
            log.warn("pacod.preview.secret (PACOD_PREVIEW_SECRET) is blank: draft previews and PDF export are off");
        }
    }

    public boolean enabled() {
        return key.present();
    }

    /** A token for {@code siteId}, valid for {@link #TTL}. */
    public String mint(String siteId, String templateSet) {
        return mint(siteId, templateSet, Instant.now().plus(TTL));
    }

    /**
     * @throws IllegalStateException with preview off
     * @throws IllegalArgumentException for an id that is not a website's or a set's shape
     */
    public String mint(String siteId, String templateSet, Instant expires) {
        if (!key.present()) {
            throw new IllegalStateException("Draft previews are off: PACOD_PREVIEW_SECRET is blank");
        }
        if (siteId == null || !ID.matcher(siteId).matches()
                || (templateSet != null && !ID.matcher(templateSet).matches())) {
            throw new IllegalArgumentException("Not a website or template set id");
        }
        String payload = String.join(".", VERSION, siteId, templateSet == null ? "-" : templateSet,
                Long.toString(expires.getEpochSecond()));
        return payload + "." + key.mac(PURPOSE, payload);
    }

    /** The token's claims if it is genuine, unexpired at {@code now}, and for {@code siteId}. */
    public Optional<Claims> verify(String token, String siteId, Instant now) {
        if (!key.present() || token == null || token.length() > 200 || siteId == null) {
            return Optional.empty();
        }
        int cut = token.lastIndexOf('.');
        if (cut < 0) {
            return Optional.empty();
        }
        String payload = token.substring(0, cut);
        if (!key.matches(PURPOSE, payload, token.substring(cut + 1))) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !parts[1].equals(siteId)) {
            return Optional.empty();
        }
        Instant expires;
        try {
            expires = Instant.ofEpochSecond(Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (!now.isBefore(expires)) {
            return Optional.empty();
        }
        return Optional.of(new Claims(parts[1], "-".equals(parts[2]) ? null : parts[2], expires));
    }
}
