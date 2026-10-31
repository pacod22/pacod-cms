package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.AiGatewaySettings;
import io.pacod.cms.core.control.repository.AiGatewaySettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiGatewayConfigTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC);

    /** A repository over one in-memory document. */
    private static AiGatewaySettingsRepository store(AtomicReference<AiGatewaySettings> held) {
        AiGatewaySettingsRepository repository = mock(AiGatewaySettingsRepository.class);
        when(repository.findById(AiGatewaySettings.ID)).thenAnswer(i -> Optional.ofNullable(held.get()));
        when(repository.save(any())).thenAnswer(i -> {
            held.set(i.getArgument(0));
            return i.getArgument(0);
        });
        doAnswer(i -> {
            held.set(null);
            return null;
        }).when(repository).deleteById(AiGatewaySettings.ID);
        return repository;
    }

    @Test
    @DisplayName("the environment's pair while nothing is stored; nothing at all is NONE")
    void environment() {
        AiGatewayConfig config = new AiGatewayConfig("http://pacod-gw:8080/", " pgw_env ", store(new AtomicReference<>()), CLOCK);
        AiGatewayConfig.Effective e = config.effective();
        assertThat(e.url()).isEqualTo("http://pacod-gw:8080");
        assertThat(e.key()).isEqualTo("pgw_env");
        assertThat(e.source()).isEqualTo(AiGatewayConfig.Source.ENVIRONMENT);
        assertThat(e.configured()).isTrue();

        AiGatewayConfig none = new AiGatewayConfig("", "", store(new AtomicReference<>()), CLOCK);
        assertThat(none.effective().source()).isEqualTo(AiGatewayConfig.Source.NONE);
        assertThat(none.effective().configured()).isFalse();
    }

    @Test
    @DisplayName("a stored pair replaces the environment's, and clearing it brings the environment back")
    void storedOverrides() {
        AtomicReference<AiGatewaySettings> held = new AtomicReference<>();
        AiGatewayConfig config = new AiGatewayConfig("http://pacod-gw:8080", "pgw_env", store(held), CLOCK);

        AiGatewayConfig.Effective e = config.set("https://gw.example.com/", "pgw_new_1234567890", "usr_admin");
        assertThat(e.source()).isEqualTo(AiGatewayConfig.Source.STORED);
        assertThat(e.url()).isEqualTo("https://gw.example.com");
        assertThat(e.key()).isEqualTo("pgw_new_1234567890");
        assertThat(e.updatedBy()).isEqualTo("usr_admin");
        assertThat(e.updatedOn()).isEqualTo(CLOCK.instant());
        assertThat(held.get().getUrl()).isEqualTo("https://gw.example.com");

        AiGatewayConfig.Effective back = config.clear();
        assertThat(back.source()).isEqualTo(AiGatewayConfig.Source.ENVIRONMENT);
        assertThat(back.key()).isEqualTo("pgw_env");
        assertThat(held.get()).isNull();
    }

    @Test
    @DisplayName("a new address without its key is refused, so a key never goes to a host it was not given for")
    void urlNeedsItsKey() {
        AtomicReference<AiGatewaySettings> held = new AtomicReference<>();
        AiGatewaySettingsRepository repository = store(held);
        AiGatewayConfig config = new AiGatewayConfig("http://pacod-gw:8080", "pgw_env", repository, CLOCK);

        assertThatThrownBy(() -> config.set("https://elsewhere.example", "", "usr_admin"))
                .isInstanceOf(AiGatewayConfig.Refused.class)
                .satisfies(t -> assertThat(((AiGatewayConfig.Refused) t).code()).isEqualTo("key_required_with_url"));
        assertThatThrownBy(() -> config.set("https://elsewhere.example", null, "usr_admin"))
                .isInstanceOf(AiGatewayConfig.Refused.class);
        verify(repository, never()).save(any());

        // The address in effect with no key changes nothing, and stores nothing: a stored copy of
        // the environment's pair would outlive a later change to the environment.
        assertThat(code(() -> config.set("http://pacod-gw:8080/", "", "usr_admin"))).isEqualTo("unchanged");
        verify(repository, never()).save(any());
        assertThat(config.effective().source()).isEqualTo(AiGatewayConfig.Source.ENVIRONMENT);
    }

    @Test
    @DisplayName("a new key alone keeps the address in effect, stored or the environment's")
    void keyOnlyRotation() {
        AtomicReference<AiGatewaySettings> held = new AtomicReference<>();
        AiGatewayConfig config = new AiGatewayConfig("http://pacod-gw:8080", "pgw_env", store(held), CLOCK);
        AiGatewayConfig.Effective e = config.set(null, "pgw_rotated", "usr_admin");
        assertThat(e.url()).isEqualTo("http://pacod-gw:8080");
        assertThat(e.key()).isEqualTo("pgw_rotated");
        assertThat(e.source()).isEqualTo(AiGatewayConfig.Source.STORED);

        config.set("https://gw.example.com", "pgw_two", "usr_admin");
        assertThat(config.set("", "pgw_three", "usr_admin").url()).isEqualTo("https://gw.example.com");
    }

    @Test
    @DisplayName("what is missing or malformed is refused by name")
    void validation() {
        AiGatewayConfig empty = new AiGatewayConfig("", "", store(new AtomicReference<>()), CLOCK);
        assertThat(code(() -> empty.set(null, "pgw_k", null))).isEqualTo("url_required");
        assertThat(code(() -> empty.set("", "", null))).isEqualTo("unchanged");
        assertThat(code(() -> empty.set("ftp://gw.example", "pgw_k", null))).isEqualTo("url_invalid");
        assertThat(code(() -> empty.set("javascript:alert(1)", "pgw_k", null))).isEqualTo("url_invalid");
        assertThat(code(() -> empty.set("http://user:pw@gw.example", "pgw_k", null))).isEqualTo("url_invalid");
        assertThat(code(() -> empty.set("http://gw.example/?x=1", "pgw_k", null))).isEqualTo("url_invalid");
        assertThat(code(() -> empty.set("http://", "pgw_k", null))).isEqualTo("url_invalid");
        assertThat(code(() -> empty.set("http://gw.example", "pgw k", null))).isEqualTo("key_invalid");
        assertThat(code(() -> empty.set("http://gw.example", "pgw\nk", null))).isEqualTo("key_invalid");
        assertThat(code(() -> empty.set("http://gw.example", "k".repeat(513), null))).isEqualTo("key_invalid");
    }

    @Test
    @DisplayName("an unreadable control plane falls back to the environment, never to nothing")
    void readFailure() {
        AiGatewaySettingsRepository repository = mock(AiGatewaySettingsRepository.class);
        when(repository.findById(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        AiGatewayConfig config = new AiGatewayConfig("http://pacod-gw:8080", "pgw_env", repository, CLOCK);
        assertThat(config.effective().source()).isEqualTo(AiGatewayConfig.Source.ENVIRONMENT);
        assertThat(config.effective().configured()).isTrue();
    }

    @Test
    @DisplayName("the key is shown only by its last four characters")
    void hint() {
        assertThat(AiGatewayConfig.hint("pgw_cms_0123456789abcdWXYZ")).isEqualTo("…WXYZ");
        assertThat(AiGatewayConfig.hint("short")).isEqualTo("…");
        assertThat(AiGatewayConfig.hint("")).isEmpty();
    }

    @Test
    @DisplayName("a fixed config reads nothing and cannot be changed")
    void fixed() {
        AiGatewayConfig config = AiGatewayConfig.fixed("http://127.0.0.1:1/", "k");
        assertThat(config.effective().url()).isEqualTo("http://127.0.0.1:1");
        assertThatThrownBy(() -> config.set("http://x.example", "k", null)).isInstanceOf(IllegalStateException.class);
    }

    private static String code(Runnable change) {
        try {
            change.run();
        } catch (AiGatewayConfig.Refused e) {
            return e.code();
        }
        return null;
    }
}
