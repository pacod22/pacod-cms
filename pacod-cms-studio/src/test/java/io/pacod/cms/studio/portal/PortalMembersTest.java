package io.pacod.cms.studio.portal;

import io.pacod.cms.core.identity.authz.Binding;
import io.pacod.cms.core.identity.authz.Subject;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.access.SubjectSource;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.identity.IdAdmin;
import io.pacod.cms.studio.identity.TokenExchange;
import io.pacod.cms.studio.identity.UserTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalMembersTest {

    private final ObjectMapper json = new ObjectMapper();
    private IdAdmin admin;
    private PortalIdentity identity;
    private Subject subject;
    private PortalMembers members;

    private static final PortalMembers.Scope ORG = PortalMembers.Scope.organisation("org_a", "Acme");
    private static final PortalMembers.Scope SITE = PortalMembers.Scope.website("org_a", "news", "Acme News");

    @BeforeEach
    void setUp() {
        admin = mock(IdAdmin.class);
        TokenExchange exchange = mock(TokenExchange.class);
        when(exchange.forAdmin("user-token")).thenReturn("admin-token");
        UserTokens tokens = mock(UserTokens.class);
        when(tokens.accessToken()).thenReturn("user-token");
        identity = mock(PortalIdentity.class);
        when(identity.enabled()).thenReturn(true);
        MgmtText text = mock(MgmtText.class);
        when(text.msg(anyString())).thenAnswer(i -> i.getArgument(0));
        SubjectSource source = authentication -> subject;
        StudioAccess access = new StudioAccess(source);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("usr_me", null, "ROLE_USER"));
        members = new PortalMembers(provider(exchange), provider(admin), provider(tokens), identity, access, text);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        when(p.getObject()).thenReturn(value);
        return p;
    }

    private void holds(String role, String... path) {
        subject = Subject.user(List.of(new Binding(role, List.of(path))));
    }

    private List<String> roles(PortalMembers.Scope scope) {
        return members.grantable(scope).stream().map(PortalMembers.RoleOption::role).toList();
    }

    @Test
    @DisplayName("an organisation admin may give the organisation's roles there, and never the platform's")
    void orgAdminGrantsOrganisationRoles() {
        holds("cms.org_admin", "platform", "organisation:org_a");
        assertThat(roles(ORG)).contains("cms.org_admin", "cms.editor", "cms.designer", "cms.analyst")
                .doesNotContain("cms.platform_admin", "cms.provisioner", "cms.website_admin", "cms.author");
        assertThat(roles(SITE)).contains("cms.website_admin", "cms.author", "cms.editor")
                .doesNotContain("cms.org_admin");
    }

    @Test
    @DisplayName("an editor may give nothing; a website admin only at their website")
    void narrowerRolesGrantLess() {
        holds("cms.editor", "platform", "organisation:org_a");
        assertThat(roles(ORG)).isEmpty();
        holds("cms.website_admin", "platform", "organisation:org_a", "cms.website:news");
        assertThat(roles(ORG)).isEmpty();
        assertThat(roles(SITE)).contains("cms.editor", "cms.website_admin");
    }

    @Test
    @DisplayName("members are read as the person, through the exchanged token")
    void membersAsThePerson() {
        when(admin.get(eq("admin-token"), eq("/scopes/organisation/org_a/members"), anyMap())).thenReturn(json.readTree("""
                {"scope":"organisation:org_a","members":[{"principal":"usr_1","principal_kind":"user",
                 "email":"alex@example.com","name":"Alex","status":"active",
                 "roles":[{"binding":"rb_1","role":"cms.editor","scope":"organisation:org_a","inherited":false}]}]}
                """));
        List<PortalMembers.Member> list = members.members(ORG);
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().directRole()).isEqualTo("cms.editor");
        assertThat(list.getFirst().displayName()).isEqualTo("Alex");
    }

    @Test
    @DisplayName("an invitation names the scope and the role, with labels for the e-mail")
    @SuppressWarnings("unchecked")
    void inviteBody() {
        holds("cms.org_admin", "platform", "organisation:org_a");
        when(admin.post(eq("admin-token"), eq("/invitations"), any())).thenReturn(json.readTree("{\"id\":\"inv_1\"}"));

        members.invite(SITE, " sam@example.com ", "cms.editor", false);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(admin).post(eq("admin-token"), eq("/invitations"), body.capture());
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertThat(sent).containsEntry("email", "sam@example.com").containsEntry("role", "cms.editor")
                .containsEntry("scope", "cms.website:news").containsEntry("deliver", "email");
        assertThat((Map<String, String>) sent.get("labels")).containsEntry("scope", "Acme News");
    }

    @Test
    @DisplayName("a role the person may not give is refused before pacod-id is asked")
    void refusesUngrantableRole() {
        holds("cms.website_admin", "platform", "organisation:org_a", "cms.website:news");
        assertThatThrownBy(() -> members.invite(ORG, "sam@example.com", "cms.org_admin", false))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> members.setRole(SITE, "usr_2", "cms.platform_admin"))
                .isInstanceOf(MgmtOperationException.class);
        verify(admin, never()).post(anyString(), anyString(), any());
        verify(admin, never()).put(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("pacod-id's refusals keep their meaning, and a change waits for the feed")
    void codesAndSeq() {
        holds("cms.org_admin", "platform", "organisation:org_a");
        when(admin.delete(eq("admin-token"), eq("/scopes/organisation/org_a/members/usr_1"), anyMap()))
                .thenThrow(PortalOrganisationsTest.newIdException(409, "last_holder"));
        assertThatThrownBy(() -> members.remove(ORG, "usr_1"))
                .isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.members.error.last_holder");

        when(admin.put(eq("admin-token"), eq("/scopes/organisation/org_a/members/usr_2"), any()))
                .thenReturn(json.readTree("{\"seq\": 42}"));
        members.setRole(ORG, "usr_2", "cms.editor");
        verify(identity).await(42L);
    }

    @Test
    @DisplayName("a principal is an id, never a path")
    void principalIsAnId() {
        holds("cms.org_admin", "platform", "organisation:org_a");
        assertThatThrownBy(() -> members.remove(ORG, "../../bindings")).isInstanceOf(MgmtOperationException.class);
        verify(admin, never()).delete(anyString(), anyString(), anyMap());
        verify(identity, never()).await(anyLong());
    }
}
