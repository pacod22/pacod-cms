package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The editable half of an {@link Article}.
 *
 * <p>Only the fields the console actually edits live here, which is the point: saving applies
 * this form onto the stored document rather than replacing it, so {@code titles}, {@code texts},
 * {@code descriptions}, {@code lang}, {@code titleClasses}, {@code suppressArticleInfo},
 * {@code counter} and {@code createdOn} survive an edit untouched.
 *
 * <p>{@code images} are not on the form either: they are attached and detached by their own
 * endpoints, so an upload is durable the moment it succeeds instead of waiting for a save that
 * may never come.
 */
@Data
public class ArticleForm {

    private Integer id;

    @NotBlank(message = "{articles.form.title.required}")
    @Size(max = 256, message = "{articles.form.title.size}")
    private String title;

    /** Bound to {@code <input type="datetime-local">}, so the time of day survives an edit. */
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime date;

    private String description;

    /** Raw HTML from the rich-text editor. */
    private String text;

    /** Raw embed markup (an iframe, typically), rendered as the first carousel slide. */
    private String embedded;

    /** Comma-separated; {@link Article#setTags(List)} strips and drops the blanks. */
    private String tags;

    private Integer priority;

    private String titleOverlayClasses;

    private ArticleStatus status;

    private List<Integer> authorIds = new ArrayList<>();

    private List<Integer> categoryIds = new ArrayList<>();

    public static ArticleForm of(Article article) {
        ArticleForm form = new ArticleForm();

        form.setId(article.getId());
        form.setTitle(article.getTitle());
        form.setDate(article.getDate());
        form.setDescription(article.getDescription());
        form.setText(article.getText());
        form.setEmbedded(article.getEmbedded());
        form.setPriority(article.getPriority());
        form.setTitleOverlayClasses(article.getTitleOverlayClasses());
        form.setStatus(article.getStatus());
        form.setTags(article.getTags() == null ? "" : String.join(", ", article.getTags()));
        form.setAuthorIds(ids(article.getAuthors(), Author::getId));
        form.setCategoryIds(ids(article.getCategories(), Category::getId));

        return form;
    }

    /** Splits the comma-separated input; blanks are dropped by {@code Article.setTags}. */
    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split(","));
    }

    private static <T> List<Integer> ids(List<T> items, java.util.function.Function<T, Integer> id) {
        List<Integer> result = new ArrayList<>();
        if (items != null) {
            for (T item : items) {
                if (item != null) {
                    result.add(id.apply(item));
                }
            }
        }
        return result;
    }
}
