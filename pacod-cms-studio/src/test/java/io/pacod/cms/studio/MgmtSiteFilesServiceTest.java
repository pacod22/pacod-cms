package io.pacod.cms.studio;

import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFileSetting;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.SiteFilesRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.core.services.sitefiles.SiteFileService;
import io.pacod.cms.studio.site.StudioSites;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MgmtSiteFilesService}: a save {@code $set}s one file's setting and its own options and
 * nothing else; a field the form did not carry keeps what is stored; every refusal is a key.
 */
class MgmtSiteFilesServiceTest {

    private final SiteFilesRepository repository = mock(SiteFilesRepository.class);
    private final SiteSettingsRepository settings = mock(SiteSettingsRepository.class);
    private final SiteFileService files = mock(SiteFileService.class);
    private final StudioSites sites = mock(StudioSites.class);
    private final MongoTemplate template = mock(MongoTemplate.class);
    private final MgmtSiteFilesService service = new MgmtSiteFilesService(repository, settings, files, sites, template);

    private SiteFiles stored;

    @BeforeEach
    void setUp() {
        stored = new SiteFiles();
        when(repository.findById(SiteFiles.ID)).thenAnswer(i -> Optional.of(stored));
        when(settings.findById(SiteSettings.ID)).thenReturn(Optional.empty());
        when(sites.current()).thenReturn(Optional.of(new StudioSites.SiteView("alpha", "org_a", "Alpha",
                WebsiteStatus.ACTIVE, "en", List.of("alpha.test"), "http://alpha.test")));
    }

    private Document savedSet() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(template).upsert(any(Query.class), update.capture(), eq(SiteFiles.class));
        return (Document) update.getValue().getUpdateObject().get("$set");
    }

    private static MgmtSiteFilesService.Change change(SiteFileMode mode, String additions, String custom) {
        return new MgmtSiteFilesService.Change(mode, additions, null, custom, false, false, false, null);
    }

    @Test
    @DisplayName("saving robots.txt sets robots and its options, never another file's setting")
    void saveTouchesOneFile() {
        service.save(SiteFileKind.ROBOTS, new MgmtSiteFilesService.Change(SiteFileMode.AUTO_PLUS,
                "Disallow: /drafts/", null, null, true, true, false, null));

        Document set = savedSet();
        assertThat(set.keySet()).containsExactlyInAnyOrder("robots", "blockAiTraining", "blockAiAnswering");
        SiteFileSetting robots = (SiteFileSetting) set.get("robots");
        assertThat(robots.mode()).isEqualTo(SiteFileMode.AUTO_PLUS);
        assertThat(robots.getAdditions()).contains("Disallow: /drafts/");
        assertThat(robots.getLastModifiedOn()).isNotNull();
        assertThat(set.get("blockAiTraining")).isEqualTo(true);
    }

    @Test
    @DisplayName("options are applied only when the form carried them, so a custom file's save keeps them")
    void optionsOnlyWhenPosted() {
        service.save(SiteFileKind.LLMS, change(SiteFileMode.AUTO, null, null));
        assertThat(savedSet().keySet()).containsExactly("llms");
    }

    @Test
    @DisplayName("a field the form did not carry keeps the stored value: going back to custom restores the text")
    void absentFieldsKeepWhatIsStored() {
        stored.getRobots().setCustom("User-agent: *\nDisallow:\n");
        stored.getRobots().setAdditions("Disallow: /x/");
        service.save(SiteFileKind.ROBOTS, change(SiteFileMode.CUSTOM, null, null));

        SiteFileSetting robots = (SiteFileSetting) savedSet().get("robots");
        assertThat(robots.mode()).isEqualTo(SiteFileMode.CUSTOM);
        assertThat(robots.getCustom()).isEqualTo("User-agent: *\nDisallow:\n");
        assertThat(robots.getAdditions()).isEqualTo("Disallow: /x/");
    }

    @Test
    @DisplayName("each refusal carries its key and writes nothing")
    void refusals() {
        assertCode(() -> service.save(SiteFileKind.LLMS_FULL, change(SiteFileMode.CUSTOM, null, "# text")), "siteFiles.error.mode");
        assertCode(() -> service.save(SiteFileKind.ROBOTS, change(SiteFileMode.CUSTOM, null, null)), "siteFiles.error.customEmpty");
        assertCode(() -> service.save(SiteFileKind.ROBOTS, change(SiteFileMode.AUTO_PLUS, "not a rule", null)), "siteFiles.error.robotsLine");
        assertCode(() -> service.save(SiteFileKind.LLMS, new MgmtSiteFilesService.Change(SiteFileMode.AUTO, null, null, null,
                true, false, false, SiteFiles.MAX_LLMS_ARTICLES + 1)), "siteFiles.error.articles");
        assertCode(() -> service.save(SiteFileKind.LLMS, new MgmtSiteFilesService.Change(SiteFileMode.AUTO, null, null, null,
                true, false, false, null)), "siteFiles.error.articles");
        verify(template, never()).upsert(any(Query.class), any(Update.class), eq(SiteFiles.class));
    }

    @Test
    @DisplayName("starting a custom text copies the generated file, from the website's origin, and makes it custom")
    void customiseCopiesTheGeneratedFile() {
        when(files.generate(eq(SiteFileKind.ROBOTS), any(), eq("http://alpha.test"), any())).thenReturn("User-agent: *\n");
        service.customise(SiteFileKind.ROBOTS);

        Document set = savedSet();
        assertThat(set.keySet()).containsExactly("robots");
        SiteFileSetting robots = (SiteFileSetting) set.get("robots");
        assertThat(robots.mode()).isEqualTo(SiteFileMode.CUSTOM);
        assertThat(robots.getCustom()).isEqualTo("User-agent: *\n");
    }

    @Test
    @DisplayName("starting a custom text is refused for llms-full, without an address, too large, or with the database down")
    void customiseRefusals() {
        assertCode(() -> service.customise(SiteFileKind.LLMS_FULL), "siteFiles.error.notCustomisable");

        when(files.generate(eq(SiteFileKind.ROBOTS), any(), anyString(), any()))
                .thenReturn("x".repeat(SiteFileKind.ROBOTS.customLimit() + 1));
        assertCode(() -> service.customise(SiteFileKind.ROBOTS), "siteFiles.error.tooLargeToCustomise");

        when(files.generate(eq(SiteFileKind.SITEMAP), any(), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertCode(() -> service.customise(SiteFileKind.SITEMAP), "siteFiles.error.unavailable");

        when(sites.current()).thenReturn(Optional.empty());
        assertCode(() -> service.customise(SiteFileKind.ROBOTS), "siteFiles.error.noOrigin");
        verify(template, never()).upsert(any(Query.class), any(Update.class), eq(SiteFiles.class));
    }

    @Test
    @DisplayName("the origin is the base URL first, else the website's public address -- never the studio's")
    void originPrefersTheBaseUrl() {
        assertThat(service.origin()).isEqualTo("http://alpha.test");
        SiteSettings withBase = new SiteSettings();
        withBase.setBaseUrl("https://www.alpha.example");
        when(settings.findById(SiteSettings.ID)).thenReturn(Optional.of(withBase));
        assertThat(service.origin()).isEqualTo("https://www.alpha.example");
    }

    @Test
    @DisplayName("the preview counts lines and bytes, cuts at the limit, and tells off from unavailable")
    void preview() {
        String big = "line\n".repeat(MgmtSiteFilesService.PREVIEW_LINES + 5);
        when(files.render(eq(SiteFileKind.SITEMAP), any(), anyString(), any())).thenReturn(Optional.of(big));
        MgmtSiteFilesService.Preview shown = service.preview(SiteFileKind.SITEMAP, stored);
        assertThat(shown.lines()).isEqualTo(MgmtSiteFilesService.PREVIEW_LINES + 5);
        assertThat(shown.truncated()).isTrue();
        assertThat(shown.text().split("\n")).hasSize(MgmtSiteFilesService.PREVIEW_LINES);
        assertThat(shown.bytes()).isEqualTo(big.length());

        when(files.render(eq(SiteFileKind.LLMS), any(), anyString(), any())).thenReturn(Optional.empty());
        assertThat(service.preview(SiteFileKind.LLMS, stored).off()).isTrue();

        when(files.render(eq(SiteFileKind.ROBOTS), any(), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        MgmtSiteFilesService.Preview failed = service.preview(SiteFileKind.ROBOTS, stored);
        assertThat(failed.failed()).isTrue();
        assertThat(failed.off()).isFalse();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MgmtOperationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
