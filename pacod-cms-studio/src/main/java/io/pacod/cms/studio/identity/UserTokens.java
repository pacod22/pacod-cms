package io.pacod.cms.studio.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.pacod.cms.studio.config.MgmtSecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * The signed-in person's access token from pacod-id, refreshed when it is about to expire.
 *
 * <p>Each studio session keeps its own tokens ({@code HttpSessionOAuth2AuthorizedClientRepository},
 * see {@code MgmtSecurityConfig}), bound to that session's pacod-id session. <b>Refreshing is
 * serialised per session</b>: pacod-id rotates refresh tokens and treats a reused one as stolen --
 * it ends every session of the account. Two requests of one session refreshing at once would sign
 * the person out everywhere.
 */
public class UserTokens {

    private final OAuth2AuthorizedClientManager manager;
    private final Cache<String, Object> locks = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterAccess(Duration.ofHours(12)).build();

    public UserTokens(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    /** Raised when the session holds no usable token any more: the person has to sign in again. */
    public static final class SignInAgainException extends RuntimeException {
        SignInAgainException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The current request's person's access token. */
    public String accessToken() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        Object lock = session == null ? new Object() : locks.get(session.getId(), id -> new Object());
        synchronized (lock) {
            OAuth2AuthorizedClient client;
            try {
                client = manager.authorize(OAuth2AuthorizeRequest
                        .withClientRegistrationId(MgmtSecurityConfig.REGISTRATION)
                        .principal(authentication)
                        .attribute(HttpServletRequest.class.getName(), request)
                        .attribute(HttpServletResponse.class.getName(), response)
                        .build());
            } catch (OAuth2AuthorizationException e) {
                throw new SignInAgainException("pacod-id refused to renew the sign-in: " + e.getMessage(), e);
            }
            if (client == null || client.getAccessToken() == null) {
                throw new SignInAgainException("This session holds no pacod-id token", null);
            }
            return client.getAccessToken().getTokenValue();
        }
    }
}
