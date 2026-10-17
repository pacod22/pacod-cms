package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.BannerSlide;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * One rotating frame, as the builder edits it.
 *
 * <p>{@code image} is deliberately absent. The filename is written by the upload endpoint and is
 * what locates the file inside the layout's folder, so binding it would let a save point a slide at
 * a file that is not there -- the same failure {@code MgmtImageService.saveCaption} avoids by
 * binding the caption and not {@code ImageMedia.filename}, and the same reason {@code BannerForm}
 * keeps an image banner's {@code source} out of itself.
 *
 * <p>{@code MgmtBannerLayoutService} therefore matches these onto the stored slides by position and
 * refuses when the counts disagree, rather than trusting the browser to describe the folder.
 */
@Data
public class BannerSlideForm {

    @Size(max = 512, message = "{banners.form.hrefTooLong}")
    private String href;

    @Size(max = 256, message = "{banners.form.captionTooLong}")
    private String title;

    @Size(max = 256, message = "{banners.form.altTooLong}")
    private String alt;

    public static BannerSlideForm of(BannerSlide slide) {
        BannerSlideForm form = new BannerSlideForm();
        form.setHref(slide.getHref());
        form.setTitle(slide.getTitle());
        form.setAlt(slide.getAlt());
        return form;
    }
}
