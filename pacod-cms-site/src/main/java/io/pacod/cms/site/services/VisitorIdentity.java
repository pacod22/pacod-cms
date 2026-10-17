package io.pacod.cms.site.services;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Turns a request into a stable, opaque reader key -- without a cookie and without a session.
 *
 * <h2>Why not a cookie, and why not a session</h2>
 * A session is ruled out twice over: {@code WebSecurityConfig} declares
 * {@code SessionCreationPolicy.STATELESS}, and {@code LocaleConfig} resolves the reader's
 * language from a cookie rather than a session precisely so an anonymous read-only site creates
 * no server-side state per reader and pins nobody to one instance. A first-party visitor cookie
 * would be more accurate per device, but it is a tracking
 * cookie and therefore a consent question this site does not currently have.
 *
 * <p>So the key is derived from what the request already carries: its client address and its
 * User-Agent, salted and truncated. Nothing is set on the reader's machine and nothing is stored
 * server-side except the digest -- see {@code SiteVisitor}, which holds no address and no
 * User-Agent at all.
 *
 * <h2>What this deliberately gets wrong</h2>
 * Readers sharing one address collapse into one reader: an office, a school, a mobile carrier's
 * NAT. The monitoring screen therefore <b>undercounts</b>, and says so beside the number rather
 * than presenting a figure whose bias is invisible. The alternative was a cookie; the undercount
 * was chosen over it.
 *
 * <h2>The salt</h2>
 * Without one, a digest of an address is reversible by anyone who can guess the address -- there
 * are only so many candidates, and hashing does not help against an enumerable input. The salt is
 * read from {@code app.metrics.visitor-salt}; <b>when it is blank a random one is generated at
 * startup</b>, so a deployment that never sets the variable is still not enumerable. The cost of
 * an ephemeral salt is that a restart re-identifies every current reader once -- one active window
 * of double counting, on a number that is explicitly an estimate. That is cheaper than the failure
 * mode of shipping no salt at all.
 */
@Slf4j
@Component
public class VisitorIdentity {

    /**
     * Half a SHA-256, in hex.
     *
     * <p>Enough that two readers colliding is not a practical concern at this site's scale, short
     * enough that the stored value is not a full-fidelity fingerprint of the digest input.
     */
    private static final int HASH_LENGTH = 16;

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String USER_AGENT = "User-Agent";

    private final String salt;

    public VisitorIdentity(@Value("${app.metrics.visitor-salt:}") String configuredSalt) {
        if (StringUtils.hasText(configuredSalt)) {
            this.salt = configuredSalt;
        } else {
            this.salt = randomSalt();
            log.info("No app.metrics.visitor-salt configured; using an ephemeral one. Visitor "
                    + "identity will not survive a restart, which double-counts current readers "
                    + "once. Set METRICS_VISITOR_SALT to make it stable.");
        }
    }

    /**
     * The reader key for this request, or {@code null} if it cannot be derived.
     *
     * <p>Null rather than a constant fallback: one shared key for every unidentifiable request
     * would read as a single very busy reader, which is worse than a gap.
     */
    public String hashOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String address = clientAddress(request);
        if (!StringUtils.hasText(address)) {
            return null;
        }

        // The website is part of the input, so one reader of two sites hosted here is two
        // unrelated keys: nothing stored in one site's database can be joined with another's.
        String site = TenantContext.find().map(Tenant::siteId).orElse("");
        String agent = request.getHeader(USER_AGENT);
        return digest(salt + '|' + site + '|' + address + '|' + (agent == null ? "" : agent));
    }

    /**
     * The reader's address as Caddy saw it.
     *
     * <p>{@code getRemoteAddr()} alone is the proxy container's address on the compose network,
     * which would make the entire site one reader. {@code X-Forwarded-For} accumulates
     * left-to-right, so the first entry is the client; the rest are proxies. Caddy replaces a value
     * the client wrote with the address it saw, so a reader cannot choose its own key through it. Both applications
     * already run with {@code SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK}, so the header is
     * trusted here for the same reason redirects already trust it.
     */
    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);

        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }

        return request.getRemoteAddr();
    }

    private String digest(String input) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hashed = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; if it is absent, counting readers is not the
            // problem worth failing a page render over.
            log.warn("SHA-256 unavailable, visitor counting disabled for this request: {}", e.getMessage());
            return null;
        }
    }

    private static String randomSalt() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
