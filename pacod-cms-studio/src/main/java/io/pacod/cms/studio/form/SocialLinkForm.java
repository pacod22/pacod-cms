package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.util.SocialTargets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * One {@code /social} link, edited one language at a time. The title is that language's raw entry;
 * everything else is shared by every language.
 *
 * <p><b>The id is not here and must never be.</b> It is generated, it is the key the link's
 * statistics are filed under, and it is in every tile URL a reader may still have open -- see
 * {@code SocialLink}. The controller takes it from the path.
 */
@Data
public class SocialLinkForm {

    private String lang = LanguageChain.original();

    @NotNull(message = "{pages.form.link.placement.required}")
    private SocialLinkPlacement placement = SocialLinkPlacement.BUTTON;

    private SocialIcon icon;

    @Size(max = 120, message = "{pages.form.link.title.size}")
    private String title;

    @NotBlank(message = "{pages.form.link.url.required}")
    @Size(max = SocialTargets.MAX_LENGTH, message = "{pages.form.link.url.size}")
    private String url;

    private boolean active = true;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime activeFrom;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime activeTo;

    public static SocialLinkForm of(SocialLink link, String lang) {
        String code = LanguageChain.normalise(lang) != null ? LanguageChain.normalise(lang) : LanguageChain.original();

        SocialLinkForm form = new SocialLinkForm();
        form.setLang(code);
        form.setPlacement(link.getPlacement());
        form.setIcon(link.getIcon());
        form.setTitle(link.rawTitle(code));
        form.setUrl(link.getUrl());
        form.setActive(link.isActive());
        form.setActiveFrom(link.getActiveFrom());
        form.setActiveTo(link.getActiveTo());
        return form;
    }
}
