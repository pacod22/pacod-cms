package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.AiGatewaySettings;
import io.pacod.cms.core.control.repository.AiGatewaySettingsRepository;
import io.pacod.cms.core.util.HttpUrls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Which pacod-gw {@link AiGateway} calls, and with which key: the stored {@link AiGatewaySettings}
 * while there is one, else {@code pacod.ai.gateway-url}/{@code pacod.ai.api-key} -- set at runtime
 * on the platform screen or through {@code /internal/v1/ai-gateway}, no restart.
 *
 * <ul>
 *   <li><b>Address and key are one pair.</b> The stored document holds both, and wins as a whole;
 *       a new address is refused without a key in the same change, so a key is never sent to a host
 *       it was not given for. A new key alone keeps the address in effect.</li>
 *   <li><b>Read fresh</b>, as {@link AiAccess} is, behind a {@link #MEMO} so a screen's render
 *       costs no lookup per question; {@link #set} and {@link #clear} forget it (one studio JVM).</li>
 *   <li><b>An unreadable control plane falls back to the environment</b>, logged at ERROR: it must
 *       not quietly switch AI off, nor on with another key.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiGatewayConfig {

    static final Duration MEMO = Duration.ofSeconds(5);
    static final int MAX_KEY = 512;

    /** Where the address and key in effect come from. */
    public enum Source { STORED, ENVIRONMENT, NONE }

    /** The address and key in effect; blank strings where there are none. */
    public record Effective(String url, String key, Source source, Instant updatedOn, String updatedBy) {

        public boolean configured() {
            return !url.isEmpty() && !key.isEmpty();
        }

        /** The key's last characters, for a screen or an answer; never the key. */
        public String keyHint() {
            return hint(key);
        }

        /** Without the key: a model or a log may print this. */
        @Override
        public String toString() {
            return "Effective[url=" + url + ", key=" + keyHint() + ", source=" + source + "]";
        }
    }

    /** A change refused, with a code for the API and a field. */
    public static class Refused extends RuntimeException {
        private final String code;
        private final String field;

        public Refused(String code, String field) {
            super(code);
            this.code = code;
            this.field = field;
        }

        public String code() {
            return code;
        }

        public String field() {
            return field;
        }
    }

    private final String envUrl;
    private final String envKey;
    private final AiGatewaySettingsRepository repository;
    private final Clock clock;
    private volatile Effective memo;
    private volatile Instant memoUntil = Instant.MIN;

    @Autowired
    public AiGatewayConfig(@Value("${pacod.ai.gateway-url:}") String url, @Value("${pacod.ai.api-key:}") String key,
                           AiGatewaySettingsRepository repository) {
        this(url, key, repository, Clock.systemUTC());
    }

    AiGatewayConfig(String url, String key, AiGatewaySettingsRepository repository, Clock clock) {
        this.envUrl = normaliseUrl(url);
        this.envKey = key == null ? "" : key.strip();
        this.repository = repository;
        this.clock = clock;
    }

    /** The environment's pair alone, nothing stored: for tests and hand-built gateways. */
    public static AiGatewayConfig fixed(String url, String key) {
        return new AiGatewayConfig(url, key, null, Clock.systemUTC());
    }

    public Effective effective() {
        Instant now = clock.instant();
        Effective cached = memo;
        if (cached != null && now.isBefore(memoUntil)) {
            return cached;
        }
        Effective fresh = read();
        memo = fresh;
        memoUntil = now.plus(MEMO);
        return fresh;
    }

    private Effective read() {
        if (repository != null) {
            try {
                Optional<AiGatewaySettings> stored = repository.findById(AiGatewaySettings.ID);
                if (stored.isPresent() && notBlank(stored.get().getUrl()) && notBlank(stored.get().getApiKey())) {
                    AiGatewaySettings s = stored.get();
                    return new Effective(normaliseUrl(s.getUrl()), s.getApiKey().strip(), Source.STORED,
                            s.getUpdatedOn(), s.getUpdatedBy());
                }
            } catch (DataAccessException e) {
                log.error("Could not read the AI gateway settings, using the environment's: {}", e.getMessage());
            }
        }
        return environment();
    }

    private Effective environment() {
        Source source = envUrl.isEmpty() && envKey.isEmpty() ? Source.NONE : Source.ENVIRONMENT;
        return new Effective(envUrl, envKey, source, null, null);
    }

    /**
     * Stores a new pair. A blank {@code url} keeps the address in effect; a key is always needed, as
     * a blank one could only repeat what is in effect (or send it to a new address).
     *
     * @throws Refused {@code url_invalid}, {@code key_invalid}, {@code key_required_with_url},
     *                 {@code url_required}, {@code key_required}, {@code unchanged}
     */
    public Effective set(String url, String key, String by) {
        if (repository == null) {
            throw new IllegalStateException("No settings store");
        }
        Effective current = read();
        String newUrl = notBlank(url) ? checkUrl(url) : current.url();
        String newKey = notBlank(key) ? checkKey(key) : null;
        if (newKey == null) {
            // A blank key with the address in effect changes nothing: storing it would copy the
            // environment's pair and quietly outlive a later change to the environment.
            throw new Refused(newUrl.equals(current.url()) ? "unchanged" : "key_required_with_url", "apiKey");
        }
        if (newUrl.isEmpty()) {
            throw new Refused("url_required", "url");
        }
        if (newKey.isEmpty()) {
            throw new Refused("key_required", "apiKey");
        }
        AiGatewaySettings settings = new AiGatewaySettings();
        settings.setUrl(newUrl);
        settings.setApiKey(newKey);
        settings.setUpdatedOn(clock.instant());
        settings.setUpdatedBy(by);
        repository.save(settings);
        forget();
        return effective();
    }

    /** Removes the stored pair: the environment's applies again. */
    public Effective clear() {
        if (repository == null) {
            throw new IllegalStateException("No settings store");
        }
        repository.deleteById(AiGatewaySettings.ID);
        forget();
        return effective();
    }

    private void forget() {
        memo = null;
        memoUntil = Instant.MIN;
    }

    static String checkUrl(String url) {
        String u = normaliseUrl(url);
        if (u.length() > 500 || !HttpUrls.isHttpUrl(u)) {
            throw new Refused("url_invalid", "url");
        }
        try {
            URI uri = new URI(u);
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new Refused("url_invalid", "url");
            }
        } catch (URISyntaxException e) {
            throw new Refused("url_invalid", "url");
        }
        return u;
    }

    static String checkKey(String key) {
        String k = key.strip();
        if (k.length() > MAX_KEY || !k.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new Refused("key_invalid", "apiKey");
        }
        return k;
    }

    static String hint(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.length() <= 8 ? "…" : "…" + key.substring(key.length() - 4);
    }

    private static String normaliseUrl(String url) {
        return url == null ? "" : url.strip().replaceAll("/+$", "");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
