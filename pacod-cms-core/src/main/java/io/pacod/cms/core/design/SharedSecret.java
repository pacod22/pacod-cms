package io.pacod.cms.core.design;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The key the studio and the public site share ({@code pacod.preview.secret},
 * {@code PACOD_PREVIEW_SECRET}), and the one way a token is signed with it: HMAC-SHA256 over a
 * purpose line and the payload, so a token minted for one purpose is never valid for another.
 * Blank means "off"; shorter than {@value #MIN} characters fails the start.
 */
final class SharedSecret {

    static final int MIN = 32;

    private final byte[] key;

    SharedSecret(String secret) {
        String value = secret == null ? "" : secret.strip();
        if (!value.isEmpty() && value.length() < MIN) {
            throw new IllegalStateException("pacod.preview.secret (PACOD_PREVIEW_SECRET) must be at least "
                    + MIN + " characters");
        }
        this.key = value.isEmpty() ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    boolean present() {
        return key != null;
    }

    String mac(String purpose, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal((purpose + "\n" + payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Whether {@code given} is the MAC of {@code payload}, compared in constant time. */
    boolean matches(String purpose, String payload, String given) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII),
                mac(purpose, payload).getBytes(StandardCharsets.US_ASCII));
    }
}
