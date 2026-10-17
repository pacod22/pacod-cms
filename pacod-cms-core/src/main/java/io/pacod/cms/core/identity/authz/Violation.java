package io.pacod.cms.core.identity.authz;

/** A change that would leave {@code scope} without a holder of the protected {@code role}. */
public record Violation(String role, String scope) {
}
