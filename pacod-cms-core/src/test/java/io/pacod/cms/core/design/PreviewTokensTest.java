package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreviewTokensTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private final PreviewTokens tokens = new PreviewTokens(SECRET);

    @Test
    @DisplayName("a token opens its website's preview until it expires")
    void roundTrip() {
        String token = tokens.mint("alpha", null, NOW.plus(Duration.ofMinutes(30)));
        assertThat(tokens.verify(token, "alpha", NOW)).hasValueSatisfying(c -> {
            assertThat(c.siteId()).isEqualTo("alpha");
            assertThat(c.templateSet()).isNull();
        });
        assertThat(tokens.verify(token, "alpha", NOW.plus(Duration.ofMinutes(30)))).isEmpty();
    }

    @Test
    @DisplayName("a token carries the template set to preview with")
    void templateSet() {
        String token = tokens.mint("alpha", "minimal", NOW.plusSeconds(60));
        assertThat(tokens.verify(token, "alpha", NOW)).hasValueSatisfying(c -> assertThat(c.templateSet()).isEqualTo("minimal"));
    }

    @Test
    @DisplayName("one website's token opens no other website")
    void siteBound() {
        String token = tokens.mint("alpha", null, NOW.plusSeconds(60));
        assertThat(tokens.verify(token, "beta", NOW)).isEmpty();
        // Rewriting the website in the payload breaks the MAC.
        assertThat(tokens.verify(token.replace(".alpha.", ".beta."), "beta", NOW)).isEmpty();
    }

    @Test
    @DisplayName("a longer life, another set or another key is refused")
    void tampering() {
        String token = tokens.mint("alpha", null, NOW.plusSeconds(60));
        long expiry = NOW.plusSeconds(60).getEpochSecond();
        assertThat(tokens.verify(token.replace("." + expiry + ".", "." + (expiry + 86_400) + "."), "alpha", NOW)).isEmpty();
        assertThat(tokens.verify(token.replace(".-.", ".minimal."), "alpha", NOW)).isEmpty();
        assertThat(new PreviewTokens(SECRET + "x").verify(token, "alpha", NOW)).isEmpty();
        assertThat(tokens.verify("p1.alpha.-.9999999999", "alpha", NOW)).isEmpty();
        assertThat(tokens.verify("", "alpha", NOW)).isEmpty();
        assertThat(tokens.verify("x".repeat(500), "alpha", NOW)).isEmpty();
    }

    @Test
    @DisplayName("a blank secret switches preview off instead of inventing a key the other JVM cannot know")
    void blankSecretIsOff() {
        PreviewTokens off = new PreviewTokens("  ");
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.mint("alpha", null)).isInstanceOf(IllegalStateException.class);
        String genuine = tokens.mint("alpha", null, NOW.plusSeconds(60));
        assertThat(off.verify(genuine, "alpha", NOW)).isEmpty();
    }

    @Test
    @DisplayName("a short secret fails the start")
    void shortSecret() {
        assertThatThrownBy(() -> new PreviewTokens("short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("only ids of the right shape are minted into a token")
    void ids() {
        assertThatThrownBy(() -> tokens.mint("Alpha.x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.mint("alpha", "a.b")).isInstanceOf(IllegalArgumentException.class);
    }
}
