package io.pacod.cms.studio.form;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.studio.ArticleTranslations;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One translation of an article: its title, description and body in a single language.
 *
 * <p>Deliberately <b>not</b> {@link ArticleForm} with a language switch. That form carries the
 * authors, categories, status and date, and {@code MgmtArticleService.save} writes every field it
 * carries -- so a translation screen built on it would either have to render the whole rail again
 * or post a form that blanks it. This one carries nothing but the three texts, and the language
 * comes from the path.
 *
 * <p>The body is required because a blank body is how a translation stops existing:
 * {@code Article.translatedIn()} reads {@code texts} and skips blanks, so saving an emptied editor
 * would silently delete the translation. Deleting is its own, confirmed action.
 */
@Data
public class TranslationForm {

    @NotBlank(message = "{articles.form.title.required}")
    private String title;

    private String description;

    @NotBlank(message = "{articles.form.text.required}")
    private String text;

    /**
     * The RAW entries for this language -- never a fallback. A fallback would show the English or
     * original text in the tab, and saving it would store that as the translation. Same rule as
     * {@code PageForm.of}.
     */
    public static TranslationForm of(Article article, String lang) {
        TranslationForm form = new TranslationForm();
        form.setTitle(ArticleTranslations.raw(article.getTitles(), lang));
        form.setDescription(ArticleTranslations.raw(article.getDescriptions(), lang));
        form.setText(ArticleTranslations.raw(article.getTexts(), lang));
        return form;
    }
}
