package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.PlatformSettings;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.SiteRetirement;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.access.StudioForbiddenException;
import io.pacod.cms.studio.identity.IdProperties;
import io.pacod.cms.studio.site.StudioSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalWebsitesTest {

    private WebsiteRepository websites;
    private DomainRepository domains;
    private SiteProvisioner provisioner;
    private SiteRetirement retirement;
    private PortalOrganisations organisations;
    private PortalIdentity identity;
    private StudioAccess access;
    private PlatformSettings platform;
    private PortalWebsites portal;

    @BeforeEach
    void setUp() {
        websites = mock(WebsiteRepository.class);
        domains = mock(DomainRepository.class);
        provisioner = mock(SiteProvisioner.class);
        retirement = mock(SiteRetirement.class);
        organisations = mock(PortalOrganisations.class);
        identity = mock(PortalIdentity.class);
        access = mock(StudioAccess.class);
        PortalSettings settings = mock(PortalSettings.class);
        platform = new PlatformSettings();
        when(settings.current()).thenReturn(platform);
        when(organisations.require("org_a")).thenReturn(new Organisation("org_a", "A"));
        when(domains.findById(anyString())).thenReturn(Optional.empty());
        when(provisioner.create(any())).thenAnswer(i -> {
            SiteProvisioner.NewWebsite request = i.getArgument(0);
            return new Website(request.siteId(), request.organisationId(), request.name());
        });
        when(identity.enabled()).thenReturn(true);
        DomainRules rules = new DomainRules(new IdProperties(true, "http://id.localhost:8000", "http://id:8071",
                "studio", "s", "service", "s", "http://studio.localhost:8000", Duration.ofSeconds(60)));
        PortalProperties properties = new PortalProperties(null, 3, 5, false, "localhost");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("usr_me", null, "ROLE_USER"));
        portal = new PortalWebsites(websites, domains, provisioner, retirement, mock(TenantDirectory.class),
                mock(StudioSites.class), organisations, settings, identity, properties, rules, access);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static PortalWebsites.NewSite request(String org, String id, List<String> hosts) {
        return new PortalWebsites.NewSite(org, id, "Acme News", "en", "Europe/Berlin", hosts);
    }

    @Test
    @DisplayName("the organisation is a posted value, so website.create is checked against it again")
    void createChecksThePostedOrganisation() {
        when(access.allowedAt(eq("website.create"), any())).thenReturn(false);
        assertThatThrownBy(() -> portal.create(request("org_a", "acme", List.of())))
                .isInstanceOf(StudioForbiddenException.class);
        verify(provisioner, never()).create(any());
    }

    @Test
    @DisplayName("demo content in a language the demo is not written in is refused before the website is created")
    void demoLanguageIsCheckedFirst() {
        when(access.allowedAt(eq("website.create"), any())).thenReturn(true);
        io.pacod.cms.studio.demo.StudioDemo demo = org.mockito.Mockito.mock(io.pacod.cms.studio.demo.StudioDemo.class);
        when(demo.supports("en")).thenReturn(true);
        when(demo.supports("de")).thenReturn(false);
        when(demo.languageNames(any())).thenReturn("English");
        portal.useDemo(demo);

        assertThatThrownBy(() -> portal.create(new PortalWebsites.NewSite("org_a", "acme", "Acme", "de", "UTC", List.of(), true)))
                .isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.site.error.demoLanguage");
        verify(provisioner, never()).create(any());

        // Without demo content the same language is fine, and with it English is.
        portal.create(new PortalWebsites.NewSite("org_a", "acme", "Acme", "de", "UTC", List.of(), false));
        portal.create(new PortalWebsites.NewSite("org_a", "acme2", "Acme", "en", "UTC", List.of(), true));
        verify(provisioner, org.mockito.Mockito.times(2)).create(any());
    }

    @Test
    @DisplayName("a template set is one the installation has, checked before the website is created, and passed on")
    void templateSetIsChecked() {
        when(access.allowedAt(eq("website.create"), any())).thenReturn(true);
        portal.useTemplateSets(new io.pacod.cms.core.design.TemplateSetCatalog());

        assertThatThrownBy(() -> portal.create(new PortalWebsites.NewSite("org_a", "acme", "Acme", "en", "UTC", List.of(),
                false, "glossy")))
                .isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.site.error.templateSet");
        verify(provisioner, never()).create(any());

        portal.create(new PortalWebsites.NewSite("org_a", "acme", "Acme", "en", "UTC", List.of(), false, "minimal"));
        ArgumentCaptor<SiteProvisioner.NewWebsite> made = ArgumentCaptor.forClass(SiteProvisioner.NewWebsite.class);
        verify(provisioner).create(made.capture());
        assertThat(made.getValue().templateSet()).isEqualTo("minimal");
        assertThat(portal.templateSets()).extracting(m -> m.id()).contains("editorial", "minimal");
    }

    @Test
    @DisplayName("an organisation at its limit gets no more websites, unless a platform admin creates them")
    void limitApplies() {
        when(access.allowedAt(eq("website.create"), any())).thenReturn(true);
        platform.setMaxSitesPerOrg(2);
        when(websites.countByOrganisationId("org_a")).thenReturn(2L);
        assertThatThrownBy(() -> portal.create(request("org_a", "acme", List.of())))
                .isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.site.refused.limit");

        when(access.isPlatformAdmin()).thenReturn(true);
        portal.create(request("org_a", "acme", List.of()));
        verify(provisioner).create(any());
    }

    @Test
    @DisplayName("a new website gets <id>.<site domain> first, then the extra hosts, and its creator")
    void createsWithHosts() {
        when(access.allowedAt(eq("website.create"), any())).thenReturn(true);
        portal.create(request("org_a", "Acme", List.of("News.Example.com", "news.example.com")));

        ArgumentCaptor<SiteProvisioner.NewWebsite> made = ArgumentCaptor.forClass(SiteProvisioner.NewWebsite.class);
        verify(provisioner).create(made.capture());
        assertThat(made.getValue().siteId()).isEqualTo("acme");
        assertThat(made.getValue().hosts()).containsExactly("acme.localhost", "news.example.com");
        assertThat(made.getValue().createdBy()).isEqualTo("usr_me");
        assertThat(made.getValue().timezone()).isEqualTo("Europe/Berlin");
        verify(identity).registerWebsite(any());
    }

    @Test
    @DisplayName("a host that belongs to the studio, or to another website, is refused")
    void refusesHosts() {
        when(access.allowedAt(eq("website.create"), any())).thenReturn(true);
        assertThatThrownBy(() -> portal.create(request("org_a", "acme", List.of("studio.localhost"))))
                .isInstanceOf(MgmtOperationException.class);
        when(domains.findById("taken.example.com")).thenReturn(Optional.of(
                new io.pacod.cms.core.control.data.Domain("taken.example.com", "other", true)));
        assertThatThrownBy(() -> portal.create(request("org_a", "acme", List.of("taken.example.com"))))
                .isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.domain.taken");
        verify(provisioner, never()).create(any());
    }

    @Test
    @DisplayName("deleting takes the website offline, removes its pacod-id scope, then erases it")
    void deleteInOrder() {
        when(websites.findById("acme")).thenReturn(Optional.of(new Website("acme", "org_a", "Acme")));
        io.pacod.cms.core.jobs.JobQueue jobs = mock(io.pacod.cms.core.jobs.JobQueue.class);
        portal.useJobs(jobs);
        portal.delete("acme", " acme ");
        InOrder order = inOrder(retirement, jobs, identity);
        order.verify(retirement).retire("acme");
        // Its jobs stop before anything is erased: one writing after the drop recreates the database.
        order.verify(jobs).cancelAll("acme");
        order.verify(identity).deleteWebsite("acme");
        order.verify(retirement).erase("acme", "usr_me");
    }

    @Test
    @DisplayName("when pacod-id cannot remove the roles, the website stays offline and nothing is erased")
    void deleteStopsWhenRolesRemain() {
        when(websites.findById("acme")).thenReturn(Optional.of(new Website("acme", "org_a", "Acme")));
        doThrow(PortalOrganisationsTest.newIdException(503, "unreachable")).when(identity).deleteWebsite("acme");
        assertThatThrownBy(() -> portal.delete("acme", "acme")).isInstanceOf(MgmtOperationException.class);
        verify(retirement).retire("acme");
        verify(retirement, never()).erase(anyString(), any());
    }

    @Test
    @DisplayName("deleting needs the website's id typed")
    void deleteNeedsConfirmation() {
        when(websites.findById("acme")).thenReturn(Optional.of(new Website("acme", "org_a", "Acme")));
        assertThatThrownBy(() -> portal.delete("acme", "Acme")).isInstanceOf(MgmtOperationException.class);
        verify(retirement, never()).retire(any());
    }

    @Test
    @DisplayName("a first address becomes primary; removing the primary promotes the next")
    void domainsKeepOnePrimary() {
        when(websites.findById("acme")).thenReturn(Optional.of(new Website("acme", "org_a", "Acme")));
        when(domains.findByWebsiteId("acme")).thenReturn(List.of());
        portal.addDomain("acme", "News.Example.com");
        ArgumentCaptor<io.pacod.cms.core.control.data.Domain> saved =
                ArgumentCaptor.forClass(io.pacod.cms.core.control.data.Domain.class);
        verify(domains).save(saved.capture());
        assertThat(saved.getValue().getHost()).isEqualTo("news.example.com");
        assertThat(saved.getValue().isPrimary()).isTrue();

        io.pacod.cms.core.control.data.Domain primary = new io.pacod.cms.core.control.data.Domain("a.example.com", "acme", true);
        io.pacod.cms.core.control.data.Domain other = new io.pacod.cms.core.control.data.Domain("b.example.com", "acme", false);
        when(domains.findByWebsiteId("acme")).thenReturn(List.of(primary, other));
        portal.removeDomain("acme", "A.example.com");
        verify(domains).delete(primary);
        assertThat(other.isPrimary()).isTrue();
    }

    @Test
    @DisplayName("making an address primary unmarks the old one; someone else's address is refused")
    void makePrimary() {
        io.pacod.cms.core.control.data.Domain primary = new io.pacod.cms.core.control.data.Domain("a.example.com", "acme", true);
        io.pacod.cms.core.control.data.Domain other = new io.pacod.cms.core.control.data.Domain("b.example.com", "acme", false);
        when(domains.findByWebsiteId("acme")).thenReturn(List.of(primary, other));
        portal.makePrimary("acme", "b.example.com");
        assertThat(other.isPrimary()).isTrue();
        assertThat(primary.isPrimary()).isFalse();
        assertThatThrownBy(() -> portal.makePrimary("acme", "elsewhere.example.com"))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> portal.removeDomain("acme", "elsewhere.example.com"))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("the studio's host is never a website's address, nor one another website holds")
    void addDomainRefuses() {
        when(websites.findById("acme")).thenReturn(Optional.of(new Website("acme", "org_a", "Acme")));
        assertThatThrownBy(() -> portal.addDomain("acme", "studio.localhost")).isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> portal.addDomain("acme", "x.id.localhost")).isInstanceOf(MgmtOperationException.class);
        when(domains.findById("taken.example.com")).thenReturn(Optional.of(
                new io.pacod.cms.core.control.data.Domain("taken.example.com", "other", true)));
        assertThatThrownBy(() -> portal.addDomain("acme", "taken.example.com")).isInstanceOf(MgmtOperationException.class);
        verify(domains, never()).save(any());
    }
}
