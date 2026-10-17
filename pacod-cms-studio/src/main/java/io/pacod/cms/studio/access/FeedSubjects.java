package io.pacod.cms.studio.access;

import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.studio.identity.AuthzFeed;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A signed-in person's bindings, from pacod-id's feed: the account is the token's {@code sub}.
 * Without a feed ({@code pacod.id.enabled=false}) nobody holds anything.
 */
@Component
public class FeedSubjects implements SubjectSource {

    private final ObjectProvider<AuthzFeed> feed;

    public FeedSubjects(ObjectProvider<AuthzFeed> feed) {
        this.feed = feed;
    }

    @Override
    public Subject subjectOf(Authentication authentication) {
        AuthzFeed f = feed.getIfAvailable();
        if (f == null) {
            return Subject.user(List.of());
        }
        String sub = authentication.getPrincipal() instanceof OidcUser user ? user.getSubject() : authentication.getName();
        return f.subjectOf(sub);
    }
}
