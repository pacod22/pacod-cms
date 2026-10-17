package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Language;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One row of {@code /mgmt/pages/languages}.
 *
 * <p>{@code code} is present but is <b>not</b> editable once the language exists: it is the
 * document id and the key every {@code TextItem} is stored under, so changing it would orphan
 * every translation already written rather than rename anything. The edit screen renders it as
 * text; only the create screen binds it.
 *
 * <p>{@code nativeName} is required, not merely nice to have. It is what the public switcher shows
 * and what the fallback notice substitutes into "…and is shown in {0}" -- a language without one
 * would produce a notice naming a language by its code, to the reader least able to guess.
 */
@Data
public class LanguageForm {

    private String code;

    @NotBlank(message = "{pages.form.adminName.required}")
    private String adminName;

    @NotBlank(message = "{pages.form.nativeName.required}")
    private String nativeName;

    private Integer sortOrder = 0;

    /** Must contain {@code {0}} -- see {@code Language.fallbackNotice}. Blank falls back to English. */
    private String fallbackNotice;

    private String legalNotice;

    public static LanguageForm of(Language language) {
        LanguageForm form = new LanguageForm();
        form.setCode(language.getCode());
        form.setAdminName(language.getAdminName());
        form.setNativeName(language.getNativeName());
        form.setSortOrder(language.getSortOrder() == null ? 0 : language.getSortOrder());
        form.setFallbackNotice(language.getFallbackNotice());
        form.setLegalNotice(language.getLegalNotice());
        return form;
    }
}
