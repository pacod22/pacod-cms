package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.repository.BannerSkeletonRepository;
import lombok.extern.slf4j.Slf4j;
import io.pacod.cms.core.seed.SiteSeedStep;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Seeds a new website's two built-in banner skeletons, the looks its HTML5 bookings can be built
 * from out of the box.
 *
 * <p>A {@link SiteSeedStep}: run by {@code SiteProvisioner} into the website's own database, and
 * only while it has no skeletons, so running it again never undoes what an operator has since
 * edited. It lives in the studio because only the studio reads a skeleton -- nothing on the public
 * site knows layouts exist; a layout reaches the site as a {@code Banner} like any other booking.
 *
 * <p>A failure is logged at ERROR and swallowed: the cost is that the layout builder has nothing to
 * offer, which its screen states.
 */
@Slf4j
@Component
@Order(40)
public class BannerSkeletonBootstrap implements SiteSeedStep {

    private final BannerSkeletonRepository skeletonRepository;
    private final BannerSkeletonParser parser;

    public BannerSkeletonBootstrap(BannerSkeletonRepository skeletonRepository,
                                   BannerSkeletonParser parser) {
        this.skeletonRepository = skeletonRepository;
        this.parser = parser;
    }

    @Override
    public String name() {
        return "banner skeletons";
    }

    @Override
    public void seed(Website website) {
        try {
            if (skeletonRepository.count() > 0) {
                log.info("Banner skeletons already exist; not seeding.");
                return;
            }

            skeletonRepository.save(load(BannerSkeleton.SIDEBAR, "Sidebar 300x250", 300, 250));
            skeletonRepository.save(load(BannerSkeleton.TOP, "Top banner", null, null));

            log.info("Seeded the two built-in banner skeletons.");

        } catch (DataAccessException | MgmtOperationException | IllegalStateException e) {
            log.error("Could not seed the built-in banner skeletons - /mgmt/banners/layouts/new will "
                    + "have nothing to build from: {}", e.getMessage());
        }
    }

    /**
     * Reads a vendored skeleton off the classpath and splits it exactly as an uploaded one is.
     *
     * <p>One path, not two: the seeded skeletons go through {@link BannerSkeletonParser} like every
     * other, so the contract it enforces is proved against them on every fresh database rather than
     * assumed. If a vendored file ever stopped satisfying it, this is where that is discovered.
     */
    private BannerSkeleton load(String id, String title, Integer width, Integer height) {

        BannerSkeletonParser.Split split = parser.parse(read("banner-skeletons/" + id + "/index.html"));

        BannerSkeleton skeleton = new BannerSkeleton();
        skeleton.setId(id);
        skeleton.setTitle(title);
        skeleton.setWidth(width);
        skeleton.setHeight(height);
        skeleton.setBuiltIn(true);
        skeleton.setHtmlHead(split.htmlHead());
        skeleton.setContainerTag(split.containerTag());
        skeleton.setHtmlTail(split.htmlTail());
        skeleton.setGtmId(split.gtmId());
        skeleton.setCss(read("banner-skeletons/" + id + "/banner.css"));
        return skeleton;
    }

    private String read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Packaged in the jar beside this class; absent means a broken build, not bad data.
            throw new IllegalStateException("Vendored skeleton " + resource + " is missing", e);
        }
    }
}
