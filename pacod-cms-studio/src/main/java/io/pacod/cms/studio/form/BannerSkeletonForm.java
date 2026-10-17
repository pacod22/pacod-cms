package io.pacod.cms.studio.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A skeleton, as one file plus a stylesheet.
 *
 * <p>The console stores a skeleton split in three -- head, container tag, tail -- but edits it as
 * the single {@code index.html} it came from, because that is the thing an operator can compare
 * against a folder in the banner tree. {@code BannerSkeletonParser} does the split again on every
 * save, so a mistake is refused at the moment it is made.
 *
 * <p>{@code gtmId} is deliberately not here. It is read out of the markup by the parser, so a
 * separate field would be a second place to state one fact, and the two could disagree with nothing
 * saying which won.
 */
@Data
public class BannerSkeletonForm {

    /**
     * Only bound when creating. It is the document's id, and a skeleton's id is what every layout
     * built from it records -- the same reason a page slug and a language code are fixed once set.
     */
    @Size(max = 40, message = "{banners.form.nameTooLong}")
    private String id;

    @NotBlank(message = "{banners.form.titleRequired}")
    @Size(max = 128, message = "{banners.form.titleTooLong}")
    private String title;

    @Min(value = 1, message = "{banners.form.widthPositive}")
    @Max(value = 4000, message = "{banners.form.widthTooLarge}")
    private Integer width;

    @Min(value = 1, message = "{banners.form.heightPositive}")
    @Max(value = 4000, message = "{banners.form.heightTooLarge}")
    private Integer height;

    @NotBlank(message = "{banners.form.htmlRequired}")
    private String html;

    @NotBlank(message = "{banners.form.cssRequired}")
    private String css;
}
