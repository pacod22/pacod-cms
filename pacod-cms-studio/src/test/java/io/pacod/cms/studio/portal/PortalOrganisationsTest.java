package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.OrgCreation;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.PlatformSettings;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.identity.IdClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalOrganisationsTest {

    private OrganisationRepository organisations;
    private WebsiteRepository websites;
    private PortalSettings settings;
    private PortalIdentity identity;
    private StudioAccess access;
    private PortalOrganisations portal;
    private PlatformSettings platform;

    @BeforeEach
    void setUp() {
        organisations = mock(OrganisationRepository.class);
        websites = mock(WebsiteRepository.class);
        settings = mock(PortalSettings.class);
        identity = mock(PortalIdentity.class);
        access = mock(StudioAccess.class);
        platform = new PlatformSettings();
        when(settings.current()).thenReturn(platform);
        when(settings.orgCreation()).thenAnswer(i -> platform.getOrgCreation());
        when(organisations.save(any(Organisation.class))).thenAnswer(i -> i.getArgument(0));
        when(identity.enabled()).thenReturn(true);
        when(identity.canProvision()).thenReturn(true);
        when(identity.await(anyLong())).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("usr_me", null, "ROLE_USER"));
        portal = new PortalOrganisations(organisations, websites, settings, identity, access);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("creating registers the organisation with pacod-id, then makes its creator admin, then waits for the feed")
    void createInOrder() {
        when(identity.registerOrganisation(any())).thenReturn(10L);
        when(identity.makeOrgAdmin(eq("usr_me"), anyString())).thenReturn(12L);

        PortalOrganisations.Created created = portal.create("  Acme   Media ");

        Organisation org = created.organisation();
        assertThat(org.getName()).isEqualTo("Acme Media");
        assertThat(org.getId()).matches("org_[0-9a-f]{32}");
        assertThat(org.getCreatedBy()).isEqualTo("usr_me");
        assertThat(org.isAiEnabled()).isFalse();
        assertThat(created.settled()).isTrue();
        InOrder order = inOrder(organisations, identity);
        order.verify(organisations).save(any());
        order.verify(identity).registerOrganisation(org);
        order.verify(identity).makeOrgAdmin("usr_me", org.getId());
        order.verify(identity).await(12L);
    }

    @Test
    @DisplayName("a new organisation starts with AI as the platform settings say")
    void aiFollowsTheSettings() {
        platform.setAiEnabledForNewOrgs(true);
        assertThat(portal.create("Acme").organisation().isAiEnabled()).isTrue();
    }

    @Test
    @DisplayName("when pacod-id refuses, the organisation is removed again -- nobody would administer it")
    void refusalRollsBack() {
        when(identity.makeOrgAdmin(anyString(), anyString()))
                .thenThrow(newIdException(409, "scope_unknown"));

        assertThatThrownBy(() -> portal.create("Acme")).isInstanceOf(MgmtOperationException.class);

        ArgumentCaptor<Organisation> saved = ArgumentCaptor.forClass(Organisation.class);
        verify(organisations).save(saved.capture());
        verify(organisations).deleteById(saved.getValue().getId());
        verify(identity).deleteOrganisation(saved.getValue().getId());
    }

    @Test
    @DisplayName("restricted to platform admins, anyone else is refused before anything is written")
    void policyRefuses() {
        platform.setOrgCreation(OrgCreation.PLATFORM_ADMINS);
        assertThat(portal.allowance().refusal()).isEqualTo("portal.org.refused.policy");
        assertThatThrownBy(() -> portal.create("Acme")).isInstanceOf(MgmtOperationException.class);
        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a person at their limit is refused; a platform admin never is")
    void limitRefusesButNotPlatformAdmins() {
        platform.setMaxOrgsPerUser(2);
        when(organisations.countByCreatedBy("usr_me")).thenReturn(2L);
        assertThat(portal.allowance().refusal()).isEqualTo("portal.org.refused.limit");

        when(access.isPlatformAdmin()).thenReturn(true);
        platform.setOrgCreation(OrgCreation.PLATFORM_ADMINS);
        assertThat(portal.allowance().allowed()).isTrue();
    }

    @Test
    @DisplayName("without a provisioner, self-service is refused: nobody could make the creator admin")
    void noProvisionerRefuses() {
        when(identity.canProvision()).thenReturn(false);
        assertThat(portal.allowance().refusal()).isEqualTo("portal.org.refused.provisioner");
    }

    @Test
    @DisplayName("an organisation with websites cannot be deleted")
    void deleteNeedsNoWebsites() {
        Organisation org = new Organisation("org_a", "A");
        when(organisations.findById("org_a")).thenReturn(java.util.Optional.of(org));
        when(websites.countByOrganisationId("org_a")).thenReturn(1L);
        assertThatThrownBy(() -> portal.delete("org_a")).isInstanceOf(MgmtOperationException.class);
        verify(identity, never()).deleteOrganisation(any());
        verify(organisations, never()).delete(any());
    }

    @Test
    @DisplayName("a blank or overlong name is refused")
    void nameIsChecked() {
        assertThatThrownBy(() -> portal.create("   ")).isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> portal.create("x".repeat(101))).isInstanceOf(MgmtOperationException.class);
    }

    static IdClient.IdException newIdException(int status, String code) {
        try {
            Constructor<IdClient.IdException> c = IdClient.IdException.class
                    .getDeclaredConstructor(int.class, String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(status, code, "refused");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
