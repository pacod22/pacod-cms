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
        when(content.seed(org.mockito.ArgumentMatchers.eq(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL))).thenReturn(seed);
        when(content.install(org.mockito.ArgumentMatchers.eq(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL), any())).thenReturn(new DemoContent.Result(20, 24, 6, 4, 12, true, true));
        when(content.languages(org.mockito.ArgumentMatchers.eq(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL))).thenReturn(java.util.Set.of("en"));

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
        when(content.install(org.mockito.ArgumentMatchers.eq(io.pacod.cms.core.seed.DemoContent.Demo.JOURNAL), any())).thenAnswer(call -> {
            assertThat(TenantContext.current().siteId()).isEqualTo("gamma");
            assertThat(call.<SiteSeedStep.Website>getArgument(1).defaultLanguage()).isEqualTo("en");
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
    @DisplayName("the space demo: each advertiser's folder imported, its bookings at their sizes, every creative linking a page the seed writes")
    void spaceAdvertisers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path storage) throws Exception {
        DemoContent.Seed space = new DemoContent(null, null, null, null, null, null, null, null,
                JsonMapper.builder().build()).seed(DemoContent.Demo.SPACE);
        when(content.seed(eq(DemoContent.Demo.SPACE))).thenReturn(space);
        when(content.install(eq(DemoContent.Demo.SPACE), any())).thenReturn(new DemoContent.Result(24, 37, 6, 5, 20, true, true));
        java.util.Map<String, byte[]> archives = new java.util.LinkedHashMap<>();
        when(layoutService.importArchive(anyString(), anyString(), any())).thenAnswer(call -> {
            archives.put(call.getArgument(0), call.<MultipartFile>getArgument(2).getBytes());
            return new BannerLayout(call.<String>getArgument(0));
        });
        var settingsRepository = mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        io.pacod.cms.core.data.SiteSettings site = new io.pacod.cms.core.data.SiteSettings();
        site.setFavicon(StudioDemo.PLATFORM_FAVICON);
        when(settingsRepository.findById(io.pacod.cms.core.data.SiteSettings.ID)).thenReturn(Optional.of(site));
        demo.useBrand(settingsRepository, storage.toString());

        StudioDemo.Outcome outcome = demo.install(DemoContent.Demo.SPACE, website);

        assertThat(outcome.promotionsSkipped()).isNull();
        assertThat(archives).containsOnlyKeys("aurelian-lines", "lumen-assurance", "rimstone-lodge", "kestrel-vane");
        java.util.Set<String> slugs = new java.util.HashSet<>();
        space.getPages().forEach(page -> slugs.add(page.getSlug()));
        java.util.regex.Pattern address = java.util.regex.Pattern.compile("(?:href|src)=\"([^\"]+)\"");
        archives.forEach((id, zip) -> {
            java.util.Map<String, String> files = unzip(zip);
            assertThat(files).as(id).containsKey("index.html");
            // Nothing a blocker's generic rules would hide in a name the browser sees.
            assertThat(files.keySet()).as(id).allSatisfy(name ->
                    assertThat(name).doesNotContainPattern("\\d+x\\d+|(?i)\\bads?\\b|banner|house|sponsor|promo"));
            files.forEach((name, text) -> {
                if (!name.endsWith(".html") && !name.endsWith(".css")) {
                    return;
                }
                assertThat(text).as(id + "/" + name).doesNotContain("http://", "https://");
                java.util.regex.Matcher links = address.matcher(text);
                while (links.find()) {
                    String link = links.group(1);
                    if (link.startsWith("/p/")) {
                        assertThat(slugs).as(id + "/" + name).contains(link.substring(3));
                    } else if (link.startsWith("/")) {
                        assertThat(link).as(id + "/" + name).isEqualTo("/site-assets/fonts/fonts.css");
                    } else {
                        assertThat(files).as(id + "/" + name + " names " + link).containsKey(link);
                    }
                }
            });
        });

        assertThat(bookings).hasSize(6);
        assertThat(bookings).filteredOn(b -> b.getType() == BannerType.HTML5).allSatisfy(b -> {
            assertThat(b.getSize()).as(b.getName()).isNotEqualTo(BannerSize.AUTO);
            String[] path = b.getSource().split("/");
            assertThat(unzip(archives.get(path[3]))).as(b.getSource()).containsKey(path[4]);
        });
        // One leaderboard per page type: the two never land on the same page.
        List<BannerForm> tops = bookings.stream().filter(b -> b.getPlacement() == BannerPlacement.TOP).toList();
        assertThat(tops).hasSize(2);
        assertThat(tops.get(0).getPages()).doesNotContainAnyElementsOf(tops.get(1).getPages());
        BannerForm still = bookings.stream().filter(b -> b.getType() == BannerType.IMAGE).findFirst().orElseThrow();
        assertThat(still.getTargetUrl()).isEqualTo(ORIGIN + "/p/nightglass-tours");
        ArgumentCaptor<MultipartFile> creative = ArgumentCaptor.forClass(MultipartFile.class);
        verify(bannerService).uploadCreative(anyInt(), creative.capture());
        assertThat(creative.getValue().getOriginalFilename()).isEqualTo("still.jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(creative.getValue().getBytes()));
        assertThat(image.getWidth()).isEqualTo(600);
        assertThat(image.getHeight()).isEqualTo(500);

        // The brand files, in the website's own storage, which the site serves at /assets/.
        java.nio.file.Path brand = storage.resolve("sites/gamma/assets/brand");
        assertThat(brand.resolve("favicon.svg")).exists();
        BufferedImage share = ImageIO.read(brand.resolve("share.jpg").toFile());
        assertThat(share.getWidth()).isEqualTo(1200);
        assertThat(share.getHeight()).isEqualTo(630);
        assertThat(site.getFavicon()).isEqualTo("/assets/brand/favicon.svg");
        assertThat(site.getOgImage()).isEqualTo("/assets/brand/share.jpg");
    }

    @Test
    @DisplayName("a website that chose its favicon and share picture keeps them")
    void spaceKeepsAnOwnBrand(@org.junit.jupiter.api.io.TempDir java.nio.file.Path storage) throws Exception {
        DemoContent.Seed space = new DemoContent(null, null, null, null, null, null, null, null,
                JsonMapper.builder().build()).seed(DemoContent.Demo.SPACE);
        when(content.seed(eq(DemoContent.Demo.SPACE))).thenReturn(space);
        when(content.install(eq(DemoContent.Demo.SPACE), any())).thenReturn(new DemoContent.Result(24, 37, 6, 5, 20, true, true));
        var settingsRepository = mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        io.pacod.cms.core.data.SiteSettings site = new io.pacod.cms.core.data.SiteSettings();
        site.setFavicon("/assets/own.svg");
        site.setOgImage("/assets/own.jpg");
        when(settingsRepository.findById(io.pacod.cms.core.data.SiteSettings.ID)).thenReturn(Optional.of(site));
        demo.useBrand(settingsRepository, storage.toString());

        demo.install(DemoContent.Demo.SPACE, website);

        verify(settingsRepository, never()).save(any());
        assertThat(storage.resolve("sites/gamma/assets/brand")).doesNotExist();
    }

    @Test
    @DisplayName("a folder's advertiser: its creatives packed from the folder, below it, with no hidden file")
    void folderAdvertiser(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createDirectories(dir.resolve("creatives/pacod-gw/css"));
        java.nio.file.Files.writeString(folder.resolve("../index.html"), "<a href=\"/p/pacod-gw\">pacod-gw</a>");
        java.nio.file.Files.writeString(folder.resolve("creative.css"), "a{}");
        java.nio.file.Files.writeString(folder.resolve("../.DS_Store"), "finder");
        DemoContent.PartnerSeed partner = new DemoContent.PartnerSeed();
        partner.setId("pacod-gw");
        partner.setFolder("creatives/pacod-gw");

        java.util.Map<String, String> files = unzip(StudioDemo.archive(new io.pacod.cms.core.seed.DirectorySeed(dir),
                new DemoContent.Seed(), partner));

        assertThat(files).containsOnlyKeys("css/creative.css", "index.html");
        assertThat(files.get("index.html")).contains("/p/pacod-gw");
        assertThat(StudioDemo.pictureType("still.PNG")).isEqualTo("image/png");
        assertThat(StudioDemo.pictureType("still.jpg")).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("the shipped demos pass the rules every seed keeps, their creatives and brand files included")
    void shippedDemosKeepTheRules() {
        DemoContent shipped = new DemoContent(null, null, null, null, null, null, null, null, JsonMapper.builder().build());
        for (DemoContent.Demo seed : DemoContent.Demo.values()) {
            assertThat(shipped.check(seed, "en")).as(seed.id()).isEmpty();
        }
    }

    private static java.util.Map<String, String> unzip(byte[] zip) {
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                files.put(entry.getName(), new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return files;
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

        verify(content, times(2)).install(any(), any());
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
