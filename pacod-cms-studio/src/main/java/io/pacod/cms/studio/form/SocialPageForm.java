package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * {@code /mgmt/social}'s profile card, for <b>one language</b> -- {@code PageForm}'s arrangement.
 *
 * <p>The three texts are that language's raw entries, never resolved through a chain: the empty
 * German tab must show empty fields, or saving it would store the original's text as a German
 * "translation". The rest -- published, avatar, the latest-articles block -- is the same in every
 * language, and every tab carries it.
 *
 * <p>The links are not here. Each has its own screen and its own form, so a profile save can never
 * reorder, drop or overwrite one.
 */
@Data
public class SocialPageForm {

    private String lang = LanguageChain.original();

    @Size(max = 80, message = "{pages.form.social.name.size}")
    private String name;

    @Size(max = 300, message = "{pages.form.social.bio.size}")
    private String bio;

    @Size(max = 80, message = "{pages.form.social.latestHeading.size}")
    private String latestHeading;

    private boolean published;

    /** An image library id, or blank for the website's logo. */
    @Min(value = 1, message = "{pages.form.social.avatar.min}")
    private Integer avatarImageId;

    private boolean showLatestArticles;

    @Min(value = SocialPage.MIN_LATEST, message = "{pages.form.social.latest.min}")
    @Max(value = SocialPage.MAX_LATEST, message = "{pages.form.social.latest.max}")
    private int latestArticlesCount = 5;

    public static SocialPageForm of(SocialPage page, String lang) {
        String code = LanguageChain.normalise(lang) != null ? LanguageChain.normalise(lang) : LanguageChain.original();

        SocialPageForm form = new SocialPageForm();
        form.setLang(code);
        form.setName(page.rawName(code));
        form.setBio(page.rawBio(code));
        form.setLatestHeading(page.rawLatestHeading(code));
        form.setPublished(page.isPublished());
        form.setAvatarImageId(page.getAvatarImageId());
        form.setShowLatestArticles(page.isShowLatestArticles());
        form.setLatestArticlesCount(page.latestCount());
        return form;
    }
}
