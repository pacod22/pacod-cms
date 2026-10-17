package io.pacod.cms.studio.demo;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.repository.BannerLayoutRepository;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.seed.SiteSeedStep;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.BannerCreativeService;
import io.pacod.cms.studio.MgmtBannerLayoutService;
import io.pacod.cms.studio.MgmtBannerService;
import io.pacod.cms.studio.form.BannerForm;
import io.pacod.cms.studio.form.BannerLayoutForm;
import io.pacod.cms.studio.site.StudioSites;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The studio's half of the demo: the website is bound for the content, and the two promotions go
 * through the advertising services with absolute addresses on the website's own origin.
 */
class StudioDemoTest {

    private static final String ORIGIN = "http://gamma.localhost:8000";

    private DemoContent content;
    private StudioSites sites;
    private MgmtBannerService bannerService;
    private MgmtBannerLayoutService layoutService;
    private BannerRepository banners;
    private BannerLayoutRepository layouts;
    private StudioDemo demo;
    private Website website;

    private final List<BannerForm> bookings = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        DemoContent.Seed seed = new DemoContent(null, null, null, null, null, null, null, null,
                JsonMapper.builder().build()).seed();
        content = mock(DemoContent.class);
        when(content.seed()).thenReturn(seed);
        when(content.install(any())).thenReturn(new DemoContent.Result(20, 24, 6, 4, 12, true, true));
        when(content.languages()).thenReturn(java.util.Set.of("en"));

        sites = mock(StudioSites.class);
        when(sites.current()).thenReturn(Optional.of(new StudioSites.SiteView("gamma", "org_g", "Gamma",
                WebsiteStatus.ACTIVE, "en", List.of("gamma.localhost"), ORIGIN)));

        bannerService = mock(MgmtBannerService.class);
        AtomicInteger ids = new AtomicInteger();
        when(bannerService.create(any())).thenAnswer(call -> {
            bookings.add(call.getArgument(0));
            return new Banner(ids.incrementAndGet());
        });

        layoutService = mock(MgmtBannerLayoutService.class);
        when(layoutService.create(anyString(), anyString(), anyString())).thenAnswer(call -> {
            BannerLayout layout = new BannerLayout(call.<String>getArgument(0));
            layout.setWidth(300);
            layout.setHeight(250);
            return layout;
        });
        when(layoutService.save(anyString(), any())).thenAnswer(call -> new BannerLayout(call.<String>getArgument(0)));
        when(layoutService.suggestedSize(any())).thenReturn(Optional.of(BannerSize.SQUARE));

        banners = mock(BannerRepository.class);
        layouts = mock(BannerLayoutRepository.class);
        demo = new StudioDemo(content, sites, bannerService, layoutService, banners, layouts);

        website = new Website("gamma", "org_g", "Gamma");
        website.setDefaultLanguage("en");
    }

    @Test
    @DisplayName("the content is written with the website bound, then an image booking and an HTML5 rotator on its own origin")
    void writesTheContentAndThePromotions() throws Exception {
        when(content.install(any())).thenAnswer(call -> {
            assertThat(TenantContext.current().siteId()).isEqualTo("gamma");
            assertThat(call.<SiteSeedStep.Website>getArgument(0).defaultLanguage()).isEqualTo("en");
            return new DemoContent.Result(20, 24, 6, 4, 12, true, true);
        });

        StudioDemo.Outcome outcome = demo.install(website);

        assertThat(outcome.promotions()).isEqualTo(2);
        assertThat(outcome.promotionsSkipped()).isNull();
        assertThat(bookings).hasSize(2);

        BannerForm image = bookings.get(0);
        assertThat(image.getType()).isEqualTo(BannerType.IMAGE);
        assertThat(image.getTargetUrl()).isEqualTo(ORIGIN + "/latest");
        assertThat(image.getPlacement()).isEqualTo(BannerPlacement.SIDEBAR);
        // The website advertises itself under its own name, not the demo's.
        assertThat(image.getName()).isEqualTo("Gamma: the weekend reading");
        assertThat(image.getAltText()).isEqualTo("The weekend reading from Gamma");
        ArgumentCaptor<MultipartFile> creative = ArgumentCaptor.forClass(MultipartFile.class);
        verify(bannerService).uploadCreative(eq(1), creative.capture());
        byte[] bytes = creative.getValue().getBytes();
        assertThat(bytes.length).isLessThanOrEqualTo((int) BannerCreativeService.MAX_BANNER_BYTES);
        BufferedImage drawn = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(drawn.getWidth()).isEqualTo(300);
        assertThat(drawn.getHeight()).isEqualTo(250);
        // Nothing an ad blocker's generic rules would hide: no size, no "ad", no "banner" in the name.
        assertThat(creative.getValue().getOriginalFilename()).doesNotContainPattern("\\d+x\\d+|(?i)\\bads?\\b|banner|house");

        verify(layoutService).create("journal-sections", "Gamma: sections", "sidebar");
        verify(layoutService, times(3)).addSlide(eq("journal-sections"), any());
        ArgumentCaptor<BannerLayoutForm> layoutForm = ArgumentCaptor.forClass(BannerLayoutForm.class);
        verify(layoutService).save(eq("journal-sections"), layoutForm.capture());
        assertThat(layoutForm.getValue().getSlides()).hasSize(3).allSatisfy(slide -> {
            assertThat(slide.getHref()).startsWith(ORIGIN + "/list/");
            assertThat(slide.getAlt()).endsWith(" in Gamma");
        });
        assertThat(layoutForm.getValue().getRotationMs()).isBetween(1000, 600000);

        BannerForm rotator = bookings.get(1);
        assertThat(rotator.getType()).isEqualTo(BannerType.HTML5);
        assertThat(rotator.getSource()).isEqualTo("/resources/banners/journal-sections/index.html");
        assertThat(rotator.getSize()).isEqualTo(BannerSize.SQUARE);
    }

    @Test
    @DisplayName("a website with bookings, or with no address to link to, gets the content and no promotions")
    void promotionsOnlyWhereTheyFit() {
        when(banners.count()).thenReturn(1L);
        assertThat(demo.install(website).promotions()).isZero();

        when(banners.count()).thenReturn(0L);
        when(sites.current()).thenReturn(Optional.of(new StudioSites.SiteView("gamma", "org_g", "Gamma",
                WebsiteStatus.ACTIVE, "en", List.of(), null)));
        StudioDemo.Outcome outcome = demo.install(website);
        assertThat(outcome.promotions()).isZero();
        assertThat(outcome.promotionsSkipped()).contains("no address");

        verify(content, times(2)).install(any());
        verify(bannerService, never()).create(any());
        verify(layoutService, never()).create(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a failing promotion costs the promotions only; the content stands and the outcome says why")
    void aFailingPromotionIsContained() throws Exception {
        when(layoutService.addSlide(anyString(), any()))
                .thenThrow(new io.pacod.cms.studio.MgmtOperationException("banners.error.writeFailed"));

        StudioDemo.Outcome outcome = demo.install(website);

        assertThat(outcome.content().articles()).isEqualTo(20);
        assertThat(outcome.promotionsSkipped()).isNotNull();
    }

    @Test
    @DisplayName("the languages it is written in are named in the language asked for")
    void namesItsLanguages() throws Exception {
        assertThat(demo.languageNames(Locale.ENGLISH)).isEqualTo("English");
        assertThat(demo.languageNames(Locale.GERMAN)).isEqualTo("Englisch");
        verify(bannerService, never()).uploadCreative(anyInt(), any());
    }
}
