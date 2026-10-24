package io.pacod.cms.studio.demo;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.BannerLayoutRepository;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.seed.DemoImages;
import io.pacod.cms.core.seed.DemoPhotos;
import io.pacod.cms.core.seed.SiteSeedStep;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.BannerCreativeService;
import io.pacod.cms.studio.MgmtBannerLayoutService;
import io.pacod.cms.studio.MgmtBannerService;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.BannerForm;
import io.pacod.cms.studio.form.BannerLayoutForm;
import io.pacod.cms.studio.form.BannerSlideForm;
import io.pacod.cms.studio.site.StudioSites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gives a website the demo content: {@link DemoContent}'s articles, pictures, sections and link
 * page, then the website's own promotions -- an image booking and an HTML5 rotator built on the
 * {@code sidebar} skeleton -- which only the studio can write, since it owns bookings and layouts.
 *
 * <p>Binds the website itself: the new-website form runs on a platform screen, where none is bound.
 *
 * <h2>The promotions</h2>
 * They go through the services the advertising screens use ({@link MgmtBannerService},
 * {@link MgmtBannerLayoutService}), so their files land where an upload's do and pass the same
 * checks. A booking's target must be an absolute address, so they point at the website's own
 * public origin ({@link StudioSites}); a website with no host gets none. They are written only
 * while the website has no booking and no layout of that id, and a failure costs the promotions
 * alone -- the articles are already there, and the outcome says what was skipped.
 */
@Slf4j
@Service
public class StudioDemo {

    /**
     * What was written.
     *
     * @param promotions        bookings written (0 to 2)
     * @param promotionsSkipped why there are none, for the log and the flash; null when written
     */
    public record Outcome(DemoContent.Result content, int promotions, String promotionsSkipped) {
    }

    /** The platform's own favicon: a website still showing it has not chosen one. */
    static final String PLATFORM_FAVICON = "/site-assets/favicon.svg";

    private final DemoContent content;
    private final StudioSites sites;
    private io.pacod.cms.core.repository.SiteSettingsRepository siteSettings;
    private String storageRoot;

    /**
     * Where a seed's brand files go: the website's own storage, and its settings. Optional, so the
     * hand-built instances of the tests need neither.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useBrand(io.pacod.cms.core.repository.SiteSettingsRepository siteSettings,
                         @org.springframework.beans.factory.annotation.Value("${pacod.storage.root:}") String storageRoot) {
        this.siteSettings = siteSettings;
        this.storageRoot = storageRoot;
    }
    private final MgmtBannerService bannerService;
    private final MgmtBannerLayoutService layoutService;
    private final BannerRepository banners;
    private final BannerLayoutRepository layouts;

    public StudioDemo(DemoContent content, StudioSites sites, MgmtBannerService bannerService,
                      MgmtBannerLayoutService layoutService, BannerRepository banners, BannerLayoutRepository layouts) {
        this.content = content;
        this.sites = sites;
        this.bannerService = bannerService;
        this.layoutService = layoutService;
        this.banners = banners;
        this.layouts = layouts;
    }

    public Set<String> languages() {
        return content.languages();
    }

    public Set<String> languages(DemoContent.Demo demo) {
        return content.languages(demo);
    }

    public boolean supports(String language) {
        return content.supports(language);
    }

    public boolean supports(DemoContent.Demo demo, String language) {
        return content.supports(demo, language);
    }

    /** The languages demo content is written in, named in {@code locale}: "English", «английски». */
    public String languageNames(Locale locale) {
        return languageNames(DemoContent.Demo.JOURNAL, locale);
    }

    public String languageNames(DemoContent.Demo demo, Locale locale) {
        return languages(demo).stream().sorted()
                .map(code -> Locale.forLanguageTag(code).getDisplayLanguage(locale))
                .collect(Collectors.joining(", "));
    }

    /**
     * @throws DemoContent.Refused   when the website's language or its articles rule it out; nothing
     *                               was written
     * @throws IllegalStateException when a picture could not be stored; running it again finishes
     */
    public Outcome install(Website website) {
        return install(DemoContent.Demo.JOURNAL, website);
    }

    /** {@link #install(Website)}, of the demo named. */
    public Outcome install(DemoContent.Demo demo, Website website) {
        return TenantContext.callAs(TenantDirectory.tenantOf(website), () -> {
            SiteSeedStep.Website about = new SiteSeedStep.Website(website.getId(), website.getName(),
                    website.getDefaultLanguage());
            DemoContent.Result result = content.install(demo, about);
            Outcome outcome;
            try {
                outcome = promotions(demo, result, website);
            } catch (MgmtOperationException | MediaException | DataAccessException | IllegalStateException e) {
                log.error("Demo promotions for website {} were not written: {}", website.getId(), e.getMessage());
                outcome = new Outcome(result, 0, e.getMessage());
            }
            try {
                brand(content.seed(demo));
            } catch (java.io.IOException | DataAccessException | IllegalStateException e) {
                // The articles and the bookings stand without a favicon of their own.
                log.error("Demo brand files for website {} were not written: {}", website.getId(), e.getMessage());
            }
            log.info("Demo content for website {}: {} articles, {} promotions{}", website.getId(),
                    result.articles(), outcome.promotions(),
                    outcome.promotionsSkipped() == null ? "" : " (" + outcome.promotionsSkipped() + ")");
            return outcome;
        });
    }

    private Outcome promotions(DemoContent.Demo demo, DemoContent.Result result, Website website) throws MediaException {
        DemoContent.Seed full = content.seed(demo);
        DemoContent.Promotions seed = full.getPromotions();
        if (seed == null && full.getPartners().isEmpty()) {
            return new Outcome(result, 0, "the seed has none");
        }
        if (banners.count() > 0) {
            return new Outcome(result, 0, "the website already has bookings");
        }
        sites.evict();
        String origin = sites.current().map(StudioSites.SiteView::publicUrl).orElse(null);
        if (origin == null) {
            return new Outcome(result, 0, "the website has no address to link to");
        }
        // The bound website's content language: what DemoContent wrote the articles in.
        String lang = LanguageChain.original();
        int written = 0;

        if (seed == null) {
            return new Outcome(result, partners(full, origin), null);
        }
        DemoContent.PromotionSeed image = seed.getImage();
        if (image != null) {
            BannerForm form = new BannerForm();
            form.setName(named(image.getName(), website));
            form.setType(BannerType.IMAGE);
            form.setTargetUrl(origin + image.getPath());
            form.setAltText(named(image.getAlts().get(lang), website));
            form.setPlacement(BannerPlacement.SIDEBAR);
            form.setSize(BannerSize.AUTO);
            form.setSortOrder(0);
            form.setActive(true);
            Integer id = bannerService.create(form).getId();
            bannerService.uploadCreative(id, new GeneratedFile(image.getKey() + ".jpg", "image/jpeg",
                    picture(image.getKey(), image.getMotif(), image.getColours(), image.getWidth(), image.getHeight())));
            written++;
        }

        DemoContent.RotatorSeed rotator = seed.getRotator();
        if (rotator != null && !layouts.existsById(rotator.getId())) {
            String title = named(rotator.getTitle(), website);
            BannerLayout layout = layoutService.create(rotator.getId(), title, rotator.getSkeleton());
            int width = layout.getWidth() == null ? 728 : layout.getWidth();
            int height = layout.getHeight() == null ? 90 : layout.getHeight();
            List<BannerSlideForm> slides = new ArrayList<>();
            for (DemoContent.SlideSeed slide : rotator.getSlides()) {
                layoutService.addSlide(layout.getId(), new GeneratedFile(slide.getKey() + ".jpg", "image/jpeg",
                        picture(slide.getKey(), slide.getMotif(), slide.getColours(), width, height)));
                BannerSlideForm slideForm = new BannerSlideForm();
                slideForm.setHref(origin + slide.getPath());
                slideForm.setTitle(named(slide.getTitles().get(lang), website));
                slideForm.setAlt(named(slide.getAlts().get(lang), website));
                slides.add(slideForm);
            }
            BannerLayoutForm layoutForm = new BannerLayoutForm();
            layoutForm.setTitle(title);
            layoutForm.setWidth(layout.getWidth());
            layoutForm.setHeight(layout.getHeight());
            layoutForm.setRotationMs(rotator.getRotationMs());
            layoutForm.setSlides(slides);
            layout = layoutService.save(layout.getId(), layoutForm);

            BannerForm form = new BannerForm();
            form.setName(title);
            form.setType(BannerType.HTML5);
            form.setSource(layout.publicPath());
            form.setPlacement(BannerPlacement.SIDEBAR);
            form.setSize(layoutService.suggestedSize(layout).orElse(BannerSize.SQUARE));
            form.setSortOrder(1);
            form.setActive(true);
            bannerService.create(form);
            written++;
        }
        return new Outcome(result, written, null);
    }

    /**
     * The advertisers: each one's folder of creatives imported as a banner layout of its id
     * (IMPORTED: the studio serves it as it is and builds nothing over it), then its bookings, each
     * at its size, placement and pages as the seed gives them -- {@code suggestedSize} would say TOP
     * for every imported layout, which has no dimensions of its own. An advertiser whose layout id
     * is already taken is left out whole.
     *
     * <p>A creative links the advertiser's page on the website itself ({@code /p/<slug>}); an image
     * booking's target is that page's absolute address, as a target must be.
     */
    private int partners(DemoContent.Seed seed, String origin) throws MediaException {
        int written = 0;
        for (DemoContent.PartnerSeed partner : seed.getPartners()) {
            boolean html5 = partner.getBookings().stream().anyMatch(b -> BannerType.HTML5.name().equals(b.getType()));
            if (html5 && layouts.existsById(partner.getId())) {
                log.info("A banner layout '{}' exists; the demo's advertiser {} is left out", partner.getId(), partner.getName());
                continue;
            }
            if (html5) {
                layoutService.importArchive(partner.getId(), partner.getName(),
                        new GeneratedFile(partner.getId() + ".zip", "application/zip", archive(seed, partner)));
            }
            for (DemoContent.BookingSeed booking : partner.getBookings()) {
                BannerForm form = new BannerForm();
                form.setName(booking.getName());
                form.setType(BannerType.valueOf(booking.getType()));
                form.setPlacement(BannerPlacement.valueOf(booking.getPlacement()));
                form.setSize(BannerSize.valueOf(booking.getSize()));
                form.setSortOrder(booking.getSortOrder());
                form.setPages(new ArrayList<>(booking.getPages()));
                form.setActive(true);
                if (form.getType() == BannerType.HTML5) {
                    form.setSource("/resources/banners/" + partner.getId() + "/" + booking.getFile());
                    bannerService.create(form);
                } else {
                    form.setTargetUrl(origin + "/p/" + partner.getPage());
                    form.setAltText(booking.getAlt());
                    Integer id = bannerService.create(form).getId();
                    String name = booking.getImage().substring(booking.getImage().lastIndexOf('/') + 1);
                    bannerService.uploadCreative(id, new GeneratedFile(name, "image/jpeg", DemoPhotos.read(booking.getImage())));
                }
                written++;
            }
        }
        return written;
    }

    /**
     * An advertiser's folder as a ZIP, built in memory: its files from the classpath, and the demo
     * photographs it names, cut to 1200x800 -- the creatives are at most a leaderboard's width.
     */
    static byte[] archive(DemoContent.Seed seed, DemoContent.PartnerSeed partner) {
        String base = partner.getFolder().endsWith("/") ? partner.getFolder() : partner.getFolder() + "/";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (org.springframework.core.io.Resource resource : new org.springframework.core.io.support
                    .PathMatchingResourcePatternResolver().getResources("classpath*:" + base + "**")) {
                String url = resource.getURL().toString();
                int at = url.lastIndexOf(base);
                String name = at < 0 ? "" : url.substring(at + base.length());
                if (name.isEmpty() || name.endsWith("/") || !resource.isReadable()) {
                    continue;
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                try (java.io.InputStream in = resource.getInputStream()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
            for (java.util.Map.Entry<String, String> photo : partner.getPhotos().entrySet()) {
                DemoContent.ImageSeed image = seed.getImages().stream().filter(i -> i.getKey().equals(photo.getValue()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("No demo picture " + photo.getValue()));
                zip.putNextEntry(new java.util.zip.ZipEntry(photo.getKey()));
                zip.write(DemoImages.jpeg(DemoPhotos.frame(DemoPhotos.read(image.getPhoto()), 1200, 800,
                        image.getFocusX(), image.getFocusY()), 300 * 1024));
                zip.closeEntry();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not pack the creatives of " + partner.getId() + ": " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /**
     * The website's favicon and share picture, into its own storage ({@code /assets/brand/}, which
     * the site serves): the favicon only while the website still shows the platform's, the share
     * picture only while it has none. A website's settings have no studio screen, so these are the
     * demo's to fill.
     */
    void brand(DemoContent.Seed seed) throws java.io.IOException {
        DemoContent.BrandSeed brand = seed.getBrand();
        if (brand == null || siteSettings == null || storageRoot == null || storageRoot.isBlank()) {
            return;
        }
        java.nio.file.Path folder = io.pacod.cms.core.tenant.SiteStorage.siteRoot(storageRoot).resolve("assets/brand");
        io.pacod.cms.core.data.SiteSettings settings = siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID)
                .orElseGet(io.pacod.cms.core.data.SiteSettings::new);
        boolean changed = false;
        if (brand.getFavicon() != null && (settings.getFavicon() == null || PLATFORM_FAVICON.equals(settings.getFavicon()))) {
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.write(folder.resolve("favicon.svg"), DemoPhotos.read(brand.getFavicon()));
            settings.setFavicon("/assets/brand/favicon.svg");
            changed = true;
        }
        if (brand.getShare() != null && (settings.getOgImage() == null || settings.getOgImage().isBlank())) {
            DemoContent.ImageSeed image = seed.getImages().stream().filter(i -> i.getKey().equals(brand.getShare()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No demo picture " + brand.getShare()));
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.write(folder.resolve("share.jpg"), DemoImages.jpeg(DemoPhotos.frame(
                    DemoPhotos.read(image.getPhoto()), 1200, 630, image.getFocusX(), image.getFocusY()), 400 * 1024));
            settings.setOgImage("/assets/brand/share.jpg");
            changed = true;
        }
        if (changed) {
            settings.setLastModifiedOn(java.time.LocalDateTime.now());
            siteSettings.save(settings);
        }
    }

    /** The seed's text with the website's name where it says {@value DemoContent#NAME}. */
    private static String named(String text, Website website) {
        return text == null ? null : text.replace(DemoContent.NAME, website.getName());
    }

    /** A creative, at most the cap a banner upload has. */
    private static byte[] picture(String key, String motif, List<String> colours, int width, int height) {
        return DemoImages.jpeg(DemoImages.draw("promotion-" + key, DemoImages.Motif.of(motif), colours, width, height),
                BannerCreativeService.MAX_BANNER_BYTES);
    }
}
