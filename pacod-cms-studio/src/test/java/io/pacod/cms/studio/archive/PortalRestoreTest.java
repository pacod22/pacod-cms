package io.pacod.cms.studio.archive;

import io.pacod.cms.core.archive.ArchiveException;
import io.pacod.cms.core.archive.SiteArchives;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.PlatformSettings;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.portal.PortalOrganisations;
import io.pacod.cms.studio.portal.PortalSettings;
import io.pacod.cms.studio.portal.PortalWebsites;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalRestoreTest {

    private static final Path FILE = Path.of("archive.zip");

    private final SiteArchives archives = mock(SiteArchives.class);
    private final PortalWebsites websites = mock(PortalWebsites.class);
    private final PortalOrganisations organisations = mock(PortalOrganisations.class);
    private final PortalSettings settings = mock(PortalSettings.class);
    private final StudioAccess access = mock(StudioAccess.class);
    private final WebsiteRepository repository = mock(WebsiteRepository.class);
    private PortalRestore restore;

    @BeforeEach
    void setUp() {
        restore = new PortalRestore(archives, websites, organisations, settings, access, repository);
        restore.useTemplateSets(new TemplateSetCatalog());
        PlatformSettings platform = new PlatformSettings();
        platform.setMaxSitesPerOrg(1);
        when(settings.current()).thenReturn(platform);
        when(websites.freeId(anyString())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Website.class))).thenAnswer(i -> i.getArgument(0));
        when(websites.restore(any(), any(), eq(true))).thenAnswer(i -> {
            PortalWebsites.NewSite request = i.getArgument(0);
            return new Website(request.id(), request.organisationId(), request.name());
        });
    }

    private static SiteArchives.SiteEntry site(String id, String templateSet, boolean aiDisabled) {
        return new SiteArchives.SiteEntry("sites/" + id + "/", id, id.toUpperCase(), "de", "Europe/Berlin", templateSet,
                aiDisabled, Map.of(), 0);
    }

    @Test
    @DisplayName("an organisation with more websites than the limit is refused before the organisation exists")
    void limitFirst() {
        when(archives.open(FILE)).thenReturn(new SiteArchives.Archive(FILE, true, "Acme",
                List.of(site("aa", null, false), site("bb", null, false))));
        assertThatThrownBy(() -> restore.restoreOrganisation(FILE, "")).isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.site.refused.limit");
        verify(organisations, never()).create(anyString());
    }

    @Test
    @DisplayName("an organisation: made as the portal makes one, each website restored into it; one failing is said, not undone")
    void organisation() {
        when(access.isPlatformAdmin()).thenReturn(true);
        when(archives.open(FILE)).thenReturn(new SiteArchives.Archive(FILE, true, "Acme",
                List.of(site("aa", "nonexistent", true), site("bb", null, false))));
        Organisation made = new Organisation("org_new", "Acme");
        when(organisations.create("Acme")).thenReturn(new PortalOrganisations.Created(made, true));
        org.mockito.Mockito.doThrow(new MgmtOperationException("portal.domain.taken", "bb.localhost"))
                .when(websites).restore(argThatId("bb"), any(), eq(true));

        PortalRestore.RestoredOrganisation restored = restore.restoreOrganisation(FILE, " ");

        assertThat(restored.organisation()).isSameAs(made);
        assertThat(restored.websites()).extracting(Website::getId).containsExactly("aa");
        assertThat(restored.websites().getFirst().isAiDisabled()).isTrue();
        assertThat(restored.failures()).extracting(PortalRestore.Failure::sourceId).containsExactly("bb");
        assertThat(restored.notes()).extracting(PortalRestore.Note::key).containsExactly("portal.archive.note.templateSet");
        ArgumentCaptor<PortalWebsites.NewSite> request = ArgumentCaptor.forClass(PortalWebsites.NewSite.class);
        verify(websites, times(2)).restore(request.capture(), any(), eq(true));
        assertThat(request.getAllValues().getFirst().templateSet()).isNull();
        assertThat(request.getAllValues().getFirst().language()).isEqualTo("de");
        assertThat(request.getAllValues().getFirst().organisationId()).isEqualTo("org_new");
    }

    @Test
    @DisplayName("a website: into the organisation chosen, checked as creating is; the core's refusal becomes the studio's")
    void website() {
        when(archives.open(FILE)).thenReturn(new SiteArchives.Archive(FILE, false, null, List.of(site("aa", "minimal", false))));
        when(websites.restore(any(), any(), eq(false))).thenAnswer(i -> {
            PortalWebsites.NewSite request = i.getArgument(0);
            return new Website(request.id(), request.organisationId(), request.name());
        });
        PortalRestore.RestoredSite restored = restore.restoreSite(FILE, "org_x", "copy", "");
        assertThat(restored.website().getId()).isEqualTo("copy");
        assertThat(restored.website().getName()).isEqualTo("AA");
        assertThat(restored.notes()).extracting(PortalRestore.Note::key).containsExactly("portal.archive.note.renamed");

        when(archives.open(FILE)).thenThrow(new ArchiveException("portal.archive.error.notArchive"));
        assertThatThrownBy(() -> restore.restoreSite(FILE, "org_x", "", "")).isInstanceOf(MgmtOperationException.class)
                .extracting(e -> ((MgmtOperationException) e).code()).isEqualTo("portal.archive.error.notArchive");
    }

    @Test
    @DisplayName("restored under another name, the website says that name to its readers; under its own, nothing is rewritten")
    @SuppressWarnings("unchecked")
    void renamedForReaders() {
        var settings = mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        io.pacod.cms.core.data.SiteSettings stored = new io.pacod.cms.core.data.SiteSettings();
        stored.setNames(new java.util.ArrayList<>(List.of(new io.pacod.cms.core.data.TextItem("AA", "de"),
                new io.pacod.cms.core.data.TextItem("AA en", "en"))));
        when(settings.findById(io.pacod.cms.core.data.SiteSettings.ID)).thenReturn(java.util.Optional.of(stored));
        restore.useSiteSettings(settings);
        when(archives.open(FILE)).thenReturn(new SiteArchives.Archive(FILE, false, null, List.of(site("aa", null, false))));
        ArgumentCaptor<java.util.function.Consumer<Website>> fill = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        when(websites.restore(any(), fill.capture(), eq(false))).thenAnswer(i -> {
            PortalWebsites.NewSite request = i.getArgument(0);
            i.<java.util.function.Consumer<Website>>getArgument(1).accept(null);
            return new Website(request.id(), request.organisationId(), request.name());
        });

        restore.restoreSite(FILE, "org_x", "copy", "Fresh Name");
        verify(archives).fill(any(), any());
        assertThat(stored.getNames()).extracting(io.pacod.cms.core.data.TextItem::getText).containsExactlyInAnyOrder("Fresh Name", "AA en");

        restore.restoreSite(FILE, "org_x", "copy2", "");
        verify(settings, times(1)).save(any());
    }

    private static PortalWebsites.NewSite argThatId(String id) {
        return argThat(r -> r != null && id.equals(r.id()));
    }
}
