package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.tenant.SiteIndexes;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.SiteRetirement;
import io.pacod.cms.studio.demo.StudioDemo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fresh install's path: an empty platform gets the demo website, with the demo content unless
 * that is switched off or the website's language is one the demo is not written in.
 */
class PlatformBootstrapTest {

    private SiteIndexes indexes;
    private SiteProvisioner provisioner;
    private WebsiteRepository websites;
    private StudioDemo demo;
    private Website created;

    @BeforeEach
    void setUp() {
        indexes = mock(SiteIndexes.class);
        when(indexes.ensureControlPlaneIndexes()).thenReturn(new SiteIndexes.Result(5, 0));
        provisioner = mock(SiteProvisioner.class);
        websites = mock(WebsiteRepository.class);
        demo = mock(StudioDemo.class);
        when(demo.supports(any(), org.mockito.ArgumentMatchers.eq("en"))).thenReturn(true);
        when(demo.languages(any())).thenReturn(Set.of("en"));
        when(demo.install(any(), any())).thenReturn(new StudioDemo.Outcome(
                new DemoContent.Result(20, 24, 6, 4, 12, true, true), 2, null));
        created = new Website("demo", "org_x", "Demo Journal");
        second = new Website("minimal", "org_x", "Demo Notes");
        when(provisioner.create(any())).thenAnswer(call ->
                call.<SiteProvisioner.NewWebsite>getArgument(0).siteId().equals("demo") ? created : second);
    }

    private Website second;

    private PlatformBootstrap bootstrap(boolean content, String language) {
        return bootstrap(content, language, false);
    }

    private PlatformBootstrap bootstrap(boolean content, String language, boolean minimal) {
        return new PlatformBootstrap(indexes, provisioner, mock(SiteRetirement.class), websites, demo,
                content, true, "demo", "Demo Journal", language, "demo.localhost, localhost",
                minimal, "minimal", "Demo Notes", "minimal.localhost");
    }

    @Test
    @DisplayName("a fresh install gets two demo websites, one per template set, in one organisation, both with content")
    void twoDemoWebsites() {
        when(websites.count()).thenReturn(0L);

        bootstrap(true, "en", true).start();

        ArgumentCaptor<SiteProvisioner.NewWebsite> request = ArgumentCaptor.forClass(SiteProvisioner.NewWebsite.class);
        verify(provisioner, org.mockito.Mockito.times(2)).create(request.capture());
        SiteProvisioner.NewWebsite first = request.getAllValues().get(0);
        SiteProvisioner.NewWebsite minimal = request.getAllValues().get(1);
        assertThat(first.organisationId()).isNull();
        assertThat(first.templateSet()).isNull();
        assertThat(minimal.siteId()).isEqualTo("minimal");
        assertThat(minimal.organisationId()).isEqualTo("org_x");
        assertThat(minimal.templateSet()).isEqualTo("minimal");
        assertThat(minimal.hosts()).containsExactly("minimal.localhost");
        verify(demo).install(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL, created);
        verify(demo).install(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL, second);
    }

    @Test
    @DisplayName("with the space demo switched on, a third website gets the space travels, in the same organisation")
    void spaceDemoWebsite() {
        when(websites.count()).thenReturn(0L);
        Website space = new Website("space", "org_x", "Starward");
        org.mockito.Mockito.doAnswer(call -> switch (call.<SiteProvisioner.NewWebsite>getArgument(0).siteId()) {
            case "demo" -> created;
            case "space" -> space;
            default -> second;
        }).when(provisioner).create(any());
        PlatformBootstrap bootstrap = bootstrap(true, "en", true);
        bootstrap.useSpace(true, "space", "Starward", "space.localhost");

        bootstrap.start();

        ArgumentCaptor<SiteProvisioner.NewWebsite> request = ArgumentCaptor.forClass(SiteProvisioner.NewWebsite.class);
        verify(provisioner, org.mockito.Mockito.times(3)).create(request.capture());
        SiteProvisioner.NewWebsite third = request.getAllValues().get(2);
        assertThat(third.siteId()).isEqualTo("space");
        assertThat(third.organisationId()).isEqualTo("org_x");
        assertThat(third.hosts()).containsExactly("space.localhost");
        // On the set its layouts were designed for.
        assertThat(third.templateSet()).isEqualTo("magazine");
        verify(demo).install(io.pacod.cms.core.seed.DemoContent.Demo.SPACE, space);
    }

    @Test
    @DisplayName("the second demo website failing leaves the first as it is")
    void secondFailingIsContained() {
        when(websites.count()).thenReturn(0L);
        org.mockito.Mockito.doAnswer(call -> {
            if (call.<SiteProvisioner.NewWebsite>getArgument(0).siteId().equals("demo")) {
                return created;
            }
            throw new IllegalStateException("The host minimal.localhost already belongs to the website x");
        }).when(provisioner).create(any());

        bootstrap(true, "en", true).start();

        verify(demo).install(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL, created);
        verify(demo, never()).install(any(), org.mockito.ArgumentMatchers.eq(second));
    }

    @Test
    @DisplayName("an empty platform gets the demo website at its hosts, with the demo content")
    void emptyPlatformGetsTheDemoWithContent() {
        when(websites.count()).thenReturn(0L);

        bootstrap(true, "en").start();

        ArgumentCaptor<SiteProvisioner.NewWebsite> request = ArgumentCaptor.forClass(SiteProvisioner.NewWebsite.class);
        verify(provisioner).create(request.capture());
        assertThat(request.getValue().siteId()).isEqualTo("demo");
        assertThat(request.getValue().hosts()).containsExactly("demo.localhost", "localhost");
        assertThat(request.getValue().organisationId()).isNull();
        verify(demo).install(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL, created);
    }

    @Test
    @DisplayName("with the content switched off, or in a language the demo is not written in, the demo website starts empty")
    void contentOnlyWhereItFits() {
        when(websites.count()).thenReturn(0L);

        bootstrap(false, "en").start();
        bootstrap(true, "de").start();

        verify(provisioner, org.mockito.Mockito.times(2)).create(any());
        verify(demo, never()).install(any(), any());
    }

    @Test
    @DisplayName("a platform with any website gets no demo website, so deleting the demo does not bring it back")
    void nothingOnAPlatformWithWebsites() {
        when(websites.count()).thenReturn(1L);

        bootstrap(true, "en").start();

        verify(provisioner, never()).create(any());
        verify(demo, never()).install(any(), any());
    }

    @Test
    @DisplayName("demo content that fails leaves the demo website standing and the start going")
    void failingContentIsContained() {
        when(websites.count()).thenReturn(0L);
        when(demo.install(any(), any())).thenThrow(new IllegalStateException("disk full"));

        // start() returning at all is half the point: the studio's start must not fail on it.
        bootstrap(true, "en").start();

        verify(provisioner).create(any());
        verify(demo).install(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL, created);
    }
}
