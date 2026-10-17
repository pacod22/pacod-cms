package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the banner form binds.
 *
 * <p>Three fields on the document are deliberately <b>not</b> here, and each omission is
 * load-bearing:
 *
 * <ul>
 *   <li>{@code impressions} and {@code clicks} -- a console save writes the whole document, so
 *       binding them would let a form post restore the counters to whatever was read when the
 *       page was opened. Leaving them off bounds the loss to whatever
 *       {@code BannerImpressionCounter} has not flushed yet.</li>
 *   <li>{@code source} for an {@link BannerType#IMAGE} banner -- that is set by the upload
 *       endpoint, not typed. {@link #source} below is bound because an
 *       {@link BannerType#HTML5} banner has nothing to upload and its path <em>is</em> typed;
 *       {@code MgmtBannerService} is what keeps a save from blanking an uploaded creative.</li>
 * </ul>
 */
@Data
public class BannerForm {

    private Integer id;

    @NotBlank(message = "{banners.form.nameRequired}")
    @Size(max = 128, message = "{banners.form.nameMax128}")
    private String name;

    @NotNull(message = "{banners.form.typeRequired}")
    private BannerType type = BannerType.IMAGE;

    /**
     * The iframe path for an HTML5 banner. Left blank for an image banner, whose source comes
     * from the upload endpoint -- see the class javadoc.
     */
    @Size(max = 512, message = "{banners.form.pathMax512}")
    private String source;

    @Size(max = 512, message = "{banners.form.urlMax512}")
    private String targetUrl;

    @Size(max = 256, message = "{banners.form.textMax256}")
    private String altText;

    @Size(max = 256, message = "{banners.form.textMax256}")
    private String caption;

    @NotNull(message = "{banners.form.placementRequired}")
    private BannerPlacement placement = BannerPlacement.SIDEBAR;

    @NotNull(message = "{banners.form.sizeRequired}")
    private BannerSize size = BannerSize.AUTO;

    @NotNull(message = "{banners.form.orderRequired}")
    private Integer sortOrder = 0;

    /** {@code BannerPage} names. Empty means every page -- see {@code Banner.coversPage}. */
    private List<String> pages = new ArrayList<>();

    private boolean active = true;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime activeFrom;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime activeTo;

    public static BannerForm of(Banner banner) {
        BannerForm form = new BannerForm();
        form.setId(banner.getId());
        form.setName(banner.getName());
        form.setType(banner.getType());
        form.setSource(banner.getSource());
        form.setTargetUrl(banner.getTargetUrl());
        form.setAltText(banner.getAltText());
        form.setCaption(banner.getCaption());
        form.setPlacement(banner.getPlacement());
        form.setSize(banner.getSize());
        form.setSortOrder(banner.getSortOrder());
        form.setPages(banner.getPages() == null ? new ArrayList<>() : new ArrayList<>(banner.getPages()));
        form.setActive(banner.isActive());
        form.setActiveFrom(banner.getActiveFrom());
        form.setActiveTo(banner.getActiveTo());
        return form;
    }
}
