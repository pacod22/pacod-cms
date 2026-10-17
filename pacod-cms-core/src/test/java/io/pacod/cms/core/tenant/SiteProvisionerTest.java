package io.pacod.cms.core.tenant;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.seed.SiteSeedStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteProvisionerTest {

    private OrganisationRepository organisations;
    private WebsiteRepository websites;
    private DomainRepository domains;
    private SiteIndexes indexes;
    private io.pacod.cms.core.control.repository.DeletedWebsiteRepository deleted;
    private final List<String> seededUnder = new ArrayList<>();
    private SiteProvisioner provisioner;

    @BeforeEach
    void setUp() {
        organisations = mock(OrganisationRepository.class);
        websites = mock(WebsiteRepository.class);
        domains = mock(DomainRepository.class);
        indexes = mock(SiteIndexes.class);
        when(websites.save(any(Website.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(domains.findById(anyString())).thenReturn(Optional.empty());

        SiteSeedStep step = new SiteSeedStep() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public void seed(Website website) {
                seededUnder.add(TenantContext.current().dbName() + ":" + website.name());
            }
        };
        deleted = mock(io.pacod.cms.core.control.repository.DeletedWebsiteRepository.class);
        provisioner = new SiteProvisioner(organisations, websites, domains, indexes, List.of(step),
                mock(TenantDirectory.class), deleted);
    }

    private static io.pacod.cms.core.control.data.Website website(String id) {
        return new io.pacod.cms.core.control.data.Website(id, "org", "Acme News");
    }

    @Test
    @DisplayName("seed steps run with the new website bound, in its own database")
    void seedsRunInTheWebsitesDatabase() {
        when(indexes.ensureSiteIndexes()).thenReturn(new SiteIndexes.Result(15, 0));

        provisioner.provision(website("acme"));

        assertThat(seededUnder).containsExactly("site_acme:Acme News");
    }

    @Test
    @DisplayName("a website is current only once every index exists, so a failure is retried")
    void failedIndexIsRetried() {
        when(indexes.ensureSiteIndexes()).thenReturn(new SiteIndexes.Result(14, 1));
        var acme = website("acme");

        provisioner.provision(acme);

        assertThat(acme.getStatus()).isEqualTo(WebsiteStatus.ACTIVE);
        assertThat(acme.getSchemaVersion()).isZero();

        when(indexes.ensureSiteIndexes()).thenReturn(new SiteIndexes.Result(15, 0));
        when(websites.findAll()).thenReturn(List.of(acme));
        assertThat(provisioner.reconcile()).isEqualTo(1);
        assertThat(acme.getSchemaVersion()).isEqualTo(SiteProvisioner.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("creating a website claims its hosts, the first as primary, and creates its organisation")
    void createClaimsHosts() {
        when(indexes.ensureSiteIndexes()).thenReturn(new SiteIndexes.Result(15, 0));
        when(websites.existsById("acme")).thenReturn(false);

        provisioner.create(new SiteProvisioner.NewWebsite(null, "Acme Ltd", "acme", "Acme News", "de", "Europe/Berlin",
                List.of("Acme.Localhost:8800", "www.acme.localhost")));

        // A new organisation, with a generated id: pacod-id's organisation ids are one namespace.
        ArgumentCaptor<Organisation> organisation = ArgumentCaptor.forClass(Organisation.class);
        verify(organisations).save(organisation.capture());
        assertThat(organisation.getValue().getId()).matches("org_[0-9a-f]{32}");
        assertThat(organisation.getValue().getName()).isEqualTo("Acme Ltd");
        ArgumentCaptor<Domain> claimed = ArgumentCaptor.forClass(Domain.class);
        verify(domains, atLeastOnce()).save(claimed.capture());
        assertThat(claimed.getAllValues()).extracting(Domain::getHost, Domain::isPrimary)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("acme.localhost", true),
                        org.assertj.core.groups.Tuple.tuple("www.acme.localhost", false));
    }

    @Test
    @DisplayName("naming an organisation that does not exist is refused, not created under that id")
    void unknownOrganisationIsRefused() {
        when(websites.existsById("acme")).thenReturn(false);
        when(organisations.existsById("org_typed")).thenReturn(false);

        assertThatThrownBy(() -> provisioner.create(new SiteProvisioner.NewWebsite("org_typed", "Typed", "acme",
                "Acme", "en", "UTC", List.of("acme.localhost"))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(organisations, never()).save(any());
        verify(websites, never()).save(any());
    }

    @Test
    @DisplayName("a host another website holds is refused before anything is written")
    void takenHostIsRefused() {
        when(websites.existsById("acme")).thenReturn(false);
        when(domains.findById("taken.localhost")).thenReturn(Optional.of(new Domain("taken.localhost", "other", true)));

        assertThatThrownBy(() -> provisioner.create(new SiteProvisioner.NewWebsite("org", "Org", "acme", "Acme",
                "en", "UTC", List.of("taken.localhost"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("other");
        verify(websites, never()).save(any());
    }

    @Test
    @DisplayName("an id deleted a moment ago is refused until its database has been swept")
    void recentlyDeletedIdIsRefused() {
        when(deleted.existsById("acme")).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provisioner.create(new SiteProvisioner.NewWebsite(
                        "org", null, "acme", "Acme", "en", "UTC", List.of("acme.test"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleted a moment ago");
    }

    @Test
    @DisplayName("the creator is recorded on the organisation and the website")
    void creatorIsRecorded() {
        when(indexes.ensureSiteIndexes()).thenReturn(new SiteIndexes.Result(1, 0));
        org.mockito.ArgumentCaptor<io.pacod.cms.core.control.data.Website> saved =
                org.mockito.ArgumentCaptor.forClass(io.pacod.cms.core.control.data.Website.class);
        org.mockito.ArgumentCaptor<io.pacod.cms.core.control.data.Organisation> organisation =
                org.mockito.ArgumentCaptor.forClass(io.pacod.cms.core.control.data.Organisation.class);

        provisioner.create(new SiteProvisioner.NewWebsite(null, "Acme Ltd", "acme", "Acme", "en", "UTC",
                List.of("acme.test"), "usr_creator"));

        org.mockito.Mockito.verify(websites, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        org.mockito.Mockito.verify(organisations).save(organisation.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getAllValues().getFirst().getCreatedBy()).isEqualTo("usr_creator");
        org.assertj.core.api.Assertions.assertThat(organisation.getValue().getCreatedBy()).isEqualTo("usr_creator");
    }
}
