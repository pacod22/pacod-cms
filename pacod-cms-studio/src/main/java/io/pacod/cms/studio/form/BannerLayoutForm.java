package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerSlide;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The builder's form.
 *
 * <p>{@code id}, {@code origin}, {@code skeleton}, {@code entryFile} and the timestamps are
 * deliberately absent, the standing rule in this package: Spring Data {@code save()} writes the
 * whole document, so a field that must not be overwritten is one the form never carries. Here that
 * matters most for the id, which is the folder name -- editing it would leave every booking naming a
 * path that no longer resolves.
 */
@Data
public class BannerLayoutForm {

    @NotBlank(message = "{banners.form.titleRequired}")
    @Size(max = 128, message = "{banners.form.titleTooLong}")
    private String title;

    /**
     * Null for a fluid layout. Both dimensions are all-or-nothing -- one without the other cannot
     * be written as CSS and cannot pick a {@code BannerSize} -- which the service enforces.
     */
    @Min(value = 1, message = "{banners.form.widthPositive}")
    @Max(value = 4000, message = "{banners.form.widthTooLarge}")
    private Integer width;

    @Min(value = 1, message = "{banners.form.heightPositive}")
    @Max(value = 4000, message = "{banners.form.heightTooLarge}")
    private Integer height;

    /**
     * Milliseconds per slide. The lower bound is not arbitrary: {@code rotator.js} refuses anything
     * under a second and falls back to its own default, so a smaller number here would be accepted,
     * stored, and then silently ignored by the folder it was written into.
     */
    @NotNull(message = "{banners.form.speedRequired}")
    @Min(value = 1000, message = "{banners.form.speedMin}")
    @Max(value = 600000, message = "{banners.form.speedMax}")
    private Integer rotationMs;

    @Size(max = 64, message = "{banners.form.codeTooLong}")
    private String gtmId;

    @Valid
    private List<BannerSlideForm> slides = new ArrayList<>();

    public static BannerLayoutForm of(BannerLayout layout) {
        BannerLayoutForm form = new BannerLayoutForm();
        form.setTitle(layout.getTitle());
        form.setWidth(layout.getWidth());
        form.setHeight(layout.getHeight());
        form.setRotationMs(layout.getRotationMs());
        form.setGtmId(layout.getGtmId());

        List<BannerSlideForm> slides = new ArrayList<>();
        for (BannerSlide slide : layout.getSlides() == null ? List.<BannerSlide>of() : layout.getSlides()) {
            slides.add(BannerSlideForm.of(slide));
        }
        form.setSlides(slides);
        return form;
    }
}
