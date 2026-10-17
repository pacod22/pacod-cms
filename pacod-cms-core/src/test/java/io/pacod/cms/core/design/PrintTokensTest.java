package io.pacod.cms.core.design;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PrintTokensTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private final PrintTokens print = new PrintTokens(SECRET);
    private final PreviewTokens preview = new PreviewTokens(SECRET);

    @Test
    @DisplayName("a print token is its website's, until it expires")
    void roundTrip() {
        String token = print.mint("alpha", NOW.plusSeconds(60));
        assertThat(print.verify(token, "alpha", NOW)).isTrue();
        assertThat(print.verify(token, "beta", NOW)).isFalse();
        assertThat(print.verify(token, "alpha", NOW.plusSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("one key, two purposes: neither token opens the other's door")
    void purposesApart() {
        String printToken = print.mint("alpha", NOW.plusSeconds(60));
        String previewToken = preview.mint("alpha", null, NOW.plusSeconds(60));
        assertThat(preview.verify(printToken, "alpha", NOW)).isEmpty();
        assertThat(print.verify(previewToken, "alpha", NOW)).isFalse();
        // Even with the payload reshaped to the other's form, the purpose is in the MAC.
        String mac = printToken.substring(printToken.lastIndexOf('.') + 1);
        assertThat(preview.verify("p1.alpha.-." + NOW.plusSeconds(60).getEpochSecond() + "." + mac, "alpha", NOW)).isEmpty();
    }

    @Test
    @DisplayName("blank key: off")
    void off() {
        assertThat(new PrintTokens("").enabled()).isFalse();
        assertThat(new PrintTokens("").verify(print.mint("alpha", NOW.plusSeconds(60)), "alpha", NOW)).isFalse();
    }
}
