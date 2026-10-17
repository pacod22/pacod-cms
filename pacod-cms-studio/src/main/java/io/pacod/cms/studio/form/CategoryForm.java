package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * The editable half of a {@link Category}.
 *
 * <p>Categories are the one document whose id an operator picks by hand: a layout's article-list
 * sections and the menus reference categories by number, so
 * creating "category 27" has to be deliberate rather than whatever the next free id happens to be.
 * The controller rejects an id that is already taken.
 *
 * <p>{@code names} (the per-language list) is absent and therefore preserved on save.
 */
@Data
public class CategoryForm {

    @NotNull(message = "{articles.form.number.required}")
    private Integer id;

    @NotBlank(message = "{articles.form.name.required}")
    private String name;

    private String lang;

    public static CategoryForm of(Category category) {
        CategoryForm form = new CategoryForm();
        form.setId(category.getId());
        form.setName(category.getName());
        form.setLang(category.getLang());
        return form;
    }
}
