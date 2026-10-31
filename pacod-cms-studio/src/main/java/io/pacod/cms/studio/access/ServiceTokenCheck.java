package io.pacod.cms.studio.access;

import java.util.Optional;

/**
 * Who a script's bearer token on {@code /internal/**} is: a pacod-id service account's
 * {@code sub}, or empty for a token that is not one. Present only with pacod-id
 * ({@code ServiceTokens}); the tests supply their own.
 */
public interface ServiceTokenCheck {

    /**
     * @throws Unavailable when the answer cannot be trusted now: pacod-id unreachable, or its feed
     *                     -- what the account may do -- not loaded or too old
     */
    Optional<String> subjectOf(String token);

    /** No answer to be had now; a 503, never a refusal the caller would take as final. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
