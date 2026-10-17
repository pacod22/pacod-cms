package io.pacod.cms.studio.testing;

import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.studio.access.SubjectSource;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Role bindings for the tests, in place of pacod-id's feed (which is off in the test profile).
 *
 * <p>By convention a test user with {@code ROLE_MGMT} -- {@code @WithMockUser(roles = "MGMT")} --
 * holds {@code cms.platform_admin}, and any other holds nothing. A test about narrower roles sets
 * them with {@link #use} for the thread it runs on (MockMvc runs the request on the test's thread)
 * and {@link #reset}s afterwards.
 *
 * <p>Picked up by the studio's component scan, as test classes sit in the scanned package; primary,
 * so it wins over {@code FeedSubjects}.
 */
@Component
@Primary
public class TestSubjects implements SubjectSource {

    private static final ThreadLocal<Subject> OVERRIDE = new ThreadLocal<>();

    public static void use(Subject subject) {
        OVERRIDE.set(subject);
    }

    public static void reset() {
        OVERRIDE.remove();
    }

    public static Subject platformAdmin() {
        return Subject.user(List.of(new Binding("cms.platform_admin", List.of("platform"))));
    }

    @Override
    public Subject subjectOf(Authentication authentication) {
        Subject override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        boolean studio = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_MGMT"::equals);
        return studio ? platformAdmin() : Subject.user(List.of());
    }
}
