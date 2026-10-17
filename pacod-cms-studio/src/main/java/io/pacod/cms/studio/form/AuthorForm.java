package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Author;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * The editable half of an {@link Author}.
 *
 * <p>Like {@link ArticleForm}, the multi-language {@code firstnames} / {@code lastnames} /
 * {@code titles} / {@code descriptions} lists are absent and therefore preserved: saving applies
 * these fields onto the stored document.
 */
@Data
public class AuthorForm {

    private Integer id;

    /** Academic or professional title -- "Проф.", "д-р". Optional. */
    private String title;

    @NotBlank(message = "{articles.form.name.required}")
    private String firstname;

    @NotBlank(message = "{articles.form.lastname.required}")
    private String lastname;

    private String description;

    public static AuthorForm of(Author author) {
        AuthorForm form = new AuthorForm();
        form.setId(author.getId());
        form.setTitle(author.getTitle());
        form.setFirstname(author.getFirstname());
        form.setLastname(author.getLastname());
        form.setDescription(author.getDescription());
        return form;
    }
}
