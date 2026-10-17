package io.pacod.cms.studio.portal;

import io.pacod.cms.core.identity.authz.Role;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.identity.IdAdmin;
import io.pacod.cms.studio.identity.IdClient;
import io.pacod.cms.studio.identity.TokenExchange;
import io.pacod.cms.studio.identity.UserTokens;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who holds which role at an organisation or a website, and invitations: a facade over pacod-id's
 * admin API, acting <b>as the signed-in person</b>.
 *
 * <p>Their access token is exchanged for an admin token ({@link TokenExchange}), so pacod-id checks
 * every change against their own roles -- the studio cannot give anyone more than the person could
 * give themselves. The studio still checks first, locally, so the screen offers only what would
 * succeed: a role's {@code at} must include the scope's type, and the person must be allowed to
 * grant it there ({@code Catalog.canGrant}).
 *
 * <p>pacod-id's refusals keep their meaning: each {@code details.code} it may answer has a sentence
 * ({@code portal.members.error.<code>}); anything else is shown with pacod-id's own message. After
 * a change the screen waits for the feed to carry it, so the list it redirects to is current.
 */
@Slf4j
@Service
public class PortalMembers {

    /** Codes pacod-id answers on these routes that the operator can act on. */
    static final Set<String> KNOWN = Set.of("last_holder", "binding_exists", "invitation_exists", "not_allowed",
            "bad_email", "principal_unknown", "not_a_member", "scope_unknown", "unreachable", "invalid_token",
            "unknown_role", "scope_type_not_allowed");

    private final ObjectProvider<TokenExchange> exchange;
    private final ObjectProvider<IdAdmin> admin;
    private final ObjectProvider<UserTokens> tokens;
    private final PortalIdentity identity;
    private final StudioAccess access;
    private final MgmtText text;

    public PortalMembers(ObjectProvider<TokenExchange> exchange, ObjectProvider<IdAdmin> admin,
                         ObjectProvider<UserTokens> tokens, PortalIdentity identity, StudioAccess access, MgmtText text) {
        this.exchange = exchange;
        this.admin = admin;
        this.tokens = tokens;
        this.identity = identity;
        this.access = access;
        this.text = text;
    }

    /**
     * An organisation or a website, as pacod-id names it.
     *
     * @param kind  {@code organisation} or {@code cms.website}
     * @param path  its scope path, for the local checks
     * @param label what invitation e-mails call it
     */
    public record Scope(String kind, String id, List<String> path, String label) {

        public static Scope organisation(String id, String label) {
            return new Scope("organisation", id, StudioAccess.pathOfOrganisation(id), label);
        }

        public static Scope website(String organisationId, String id, String label) {
            return new Scope("cms.website", id, List.of("platform", "organisation:" + organisationId, "cms.website:" + id),
                    label);
        }

        public String key() {
            return kind + ":" + id;
        }
    }

    /** A role a person holds; {@code inherited} when it is held above this scope. */
    public record MemberRole(String binding, String role, String scope, boolean inherited) {
    }

    public record Member(String principal, String kind, String email, String name, String status, List<MemberRole> roles) {

        /** The role held at exactly this scope, or null when every role comes from above. */
        public String directRole() {
            return roles.stream().filter(r -> !r.inherited()).map(MemberRole::role).findFirst().orElse(null);
        }

        public String displayName() {
            return name != null && !name.isBlank() ? name : email != null && !email.isBlank() ? email : principal;
        }
    }

    public record Invitation(String id, String email, String role, String invitedBy, String inviterName,
                             boolean emailed, String createdAt, String expiresAt, boolean expired) {
    }

    /** A role the person may give at a scope, with its label in the UI language. */
    public record RoleOption(String role, String label) {
    }

    /** What an invitation created: the link to pass on, when it was not e-mailed. */
    public record Invited(String email, String link) {
    }

    /** Whether the facade can run at all: pacod-id enabled. */
    public boolean available() {
        return identity.enabled() && exchange.getIfAvailable() != null && admin.getIfAvailable() != null;
    }

    public boolean canManage(Scope scope) {
        return access.allowedAt("members.manage", scope.path());
    }

    /** The roles of this product that may be bound at the scope and that the person may give there. */
    public List<RoleOption> grantable(Scope scope) {
        List<RoleOption> options = new ArrayList<>();
        for (Role role : access.manifest().roles()) {
            if (role.implicit() || !role.at().contains(scope.kind())) {
                continue;
            }
            if (access.catalog().canGrant(access.subject(), role.name(), scope.path()).isEmpty()) {
                options.add(new RoleOption(role.name(), roleLabel(role.name())));
            }
        }
        return options;
    }

    public String roleLabel(String role) {
        String key = "portal.role." + role;
        String label = text.msg(key);
        return label == null || label.equals(key) ? role : label;
    }

    public List<Member> members(Scope scope) {
        JsonNode answer = call(token -> admin().get(token, "/scopes/" + scope.kind() + "/" + scope.id() + "/members", Map.of()));
        List<Member> members = new ArrayList<>();
        for (JsonNode m : answer.path("members")) {
            List<MemberRole> roles = new ArrayList<>();
            for (JsonNode r : m.path("roles")) {
                roles.add(new MemberRole(r.path("binding").asString(null), r.path("role").asString(null),
                        r.path("scope").asString(null), r.path("inherited").asBoolean(false)));
            }
            members.add(new Member(m.path("principal").asString(null), m.path("principal_kind").asString(null),
                    m.path("email").asString(null), m.path("name").asString(null), m.path("status").asString(null),
                    List.copyOf(roles)));
        }
        return List.copyOf(members);
    }

    /** Open invitations at the scope; only for someone who may manage members there. */
    public List<Invitation> invitations(Scope scope) {
        if (!canManage(scope)) {
            return List.of();
        }
        JsonNode answer = call(token -> admin().get(token, "/invitations", Map.of("scope", scope.key())));
        List<Invitation> invitations = new ArrayList<>();
        for (JsonNode i : answer.path("invitations")) {
            invitations.add(new Invitation(i.path("id").asString(null), i.path("email").asString(null),
                    i.path("role").asString(null), i.path("invited_by").asString(null),
                    i.path("inviter_name").asString(null), i.path("emailed").asBoolean(false),
                    i.path("created_at").asString(null), i.path("expires_at").asString(null),
                    i.path("expired").asBoolean(false)));
        }
        return List.copyOf(invitations);
    }

    /**
     * Invites {@code email} to {@code role} at the scope: by e-mail, or -- {@code asLink} -- as a link
     * to pass on, for an installation that sends no mail.
     */
    public Invited invite(Scope scope, String email, String role, boolean asLink) {
        requireGrantable(scope, role);
        String address = email == null ? "" : email.strip();
        if (address.isEmpty() || !address.contains("@")) {
            throw new MgmtOperationException("portal.members.error.bad_email");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", address);
        body.put("role", role);
        body.put("scope", scope.key());
        body.put("deliver", asLink ? "link" : "email");
        body.put("labels", Map.of("role", roleLabel(role), "scope", scope.label() == null ? scope.id() : scope.label()));
        JsonNode answer = call(token -> admin().post(token, "/invitations", body));
        return new Invited(address, answer.path("link").asString(null));
    }

    /** Gives the principal {@code role} at exactly this scope, replacing what they held here. */
    public void setRole(Scope scope, String principal, String role) {
        requireGrantable(scope, role);
        requireId(principal);
        JsonNode answer = call(token -> admin().put(token,
                "/scopes/" + scope.kind() + "/" + scope.id() + "/members/" + principal, Map.of("role", role)));
        identity.await(IdAdmin.seqOf(answer));
    }

    /** Takes every role the principal holds at exactly this scope. */
    public void remove(Scope scope, String principal) {
        requireId(principal);
        JsonNode answer = call(token -> admin().delete(token,
                "/scopes/" + scope.kind() + "/" + scope.id() + "/members/" + principal, Map.of("below", "false")));
        identity.await(IdAdmin.seqOf(answer));
    }

    public void revoke(Scope scope, String invitationId) {
        requireId(invitationId);
        if (invitations(scope).stream().noneMatch(i -> i.id().equals(invitationId))) {
            throw new MgmtOperationException("portal.members.error.noInvitation");
        }
        call(token -> admin().delete(token, "/invitations/" + invitationId, Map.of()));
    }

    /** pacod-id's principal and invitation ids: a prefix, an underscore, letters and digits. */
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z]{2,4}_[A-Za-z0-9]{1,64}");

    static String requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new MgmtOperationException("portal.members.error.principal_unknown");
        }
        return id;
    }

    private void requireGrantable(Scope scope, String role) {
        if (role == null || grantable(scope).stream().noneMatch(o -> o.role().equals(role))) {
            throw new MgmtOperationException("portal.members.error.role");
        }
    }

    private IdAdmin admin() {
        IdAdmin a = admin.getIfAvailable();
        if (a == null) {
            throw new MgmtOperationException("portal.members.unavailable");
        }
        return a;
    }

    private JsonNode call(java.util.function.Function<String, JsonNode> request) {
        if (!available()) {
            throw new MgmtOperationException("portal.members.unavailable");
        }
        String userToken;
        try {
            userToken = tokens.getObject().accessToken();
        } catch (UserTokens.SignInAgainException e) {
            log.info("Members call needs a fresh sign-in: {}", e.getMessage());
            throw new MgmtOperationException("portal.members.error.signInAgain");
        }
        TokenExchange ex = exchange.getObject();
        try {
            return request.apply(ex.forAdmin(userToken));
        } catch (IdClient.IdException e) {
            if (e.status() == 401) {
                ex.forget(userToken);
            }
            if (e.code() != null && KNOWN.contains(e.code())) {
                throw new MgmtOperationException("portal.members.error." + e.code());
            }
            log.warn("pacod-id refused a members call: {}", e.getMessage());
            throw new MgmtOperationException("portal.members.error.failed", e.getMessage());
        }
    }
}
