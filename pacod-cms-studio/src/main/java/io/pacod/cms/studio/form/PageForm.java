package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.LanguageChain;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One language's worth of a {@link Page}, plus the settings that are not per-language.
 *
 * <p><b>The form carries exactly one language.</b> {@code lang} says which, and
 * {@code MgmtPageService.save} writes only that one -- every other language survives untouched,
 * the same apply-do-not-replace rule {@code ArticleForm} relies on. Binding every body at once
 * would mean one TinyMCE instance per language on one screen, and a language whose editor failed
 * to initialise would post empty and silently delete a translation.
 *
 * <p>{@code slug} is present but is <b>not</b> editable on the edit screen: it is the document id,
 * so changing it would create a second page rather than rename one. It rides along as a hidden
 * field purely so the POST knows which page it is saving.
 *
 * <p>{@code createdOn} and {@code lastModifiedOn} are deliberately absent, for the reason
 * {@code BannerForm} keeps {@code impressions} out of itself: Spring Data {@code save()} writes
 * the whole document, so a bound timestamp would be written back from whatever the page held when
 * the form was opened.
 */
@Data
public class PageForm {

    private String slug;

    /** Which language this submission is editing. */
    private String lang = LanguageChain.original();

    /**
     * Required, because a page with a body and no title renders an empty {@code <h1>} and an
     * empty {@code <title>} -- and the {@code og:title} that goes with it.
     */
    @NotBlank(message = "{pages.form.title.required}")
    private String title;

    private String body;

    private String description;

    private boolean published = true;

    private boolean officialInDefaultLanguageOnly;

    private Integer sortOrder = 0;

    /** Loads the form with one language's text. Absent text binds as null, not "". */
    public static PageForm of(Page page, String lang) {
        String code = LanguageChain.normalise(lang) != null
                ? LanguageChain.normalise(lang)
                : LanguageChain.original();

        PageForm form = new PageForm();
        form.setSlug(page.getSlug());
        form.setLang(code);
        // The RAW entry for this language, never page.title(chain) -- that one falls back, so
        // opening the empty German tab would show the English or original text and saving it
        // would store a "translation" that is just the original. Two differently-named methods on
        // Page rather than an overload, and this form is the reason: the wrong one of an
        // overloaded pair is a silent data loss, not an error.
        form.setTitle(page.rawTitle(code));
        form.setBody(page.rawBody(code));
        form.setDescription(page.rawDescription(code));
        form.setPublished(page.isPublished());
        form.setOfficialInDefaultLanguageOnly(page.isOfficialInDefaultLanguageOnly());
        form.setSortOrder(page.getSortOrder() == null ? 0 : page.getSortOrder());
        return form;
    }

}
