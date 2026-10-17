package io.pacod.cms.studio.access;

import io.pacod.cms.core.identity.authz.Subject;
import org.springframework.security.core.Authentication;

/** Where a signed-in person's role bindings come from. */
public interface SubjectSource {

    /** The bindings of whoever {@code authentication} names; no bindings when unknown. */
    Subject subjectOf(Authentication authentication);
}
