package io.pacod.cms.site.data;

import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.*;
import io.pacod.cms.site.utils.DateFormatter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ArticleModel extends Article {

    private final static String PATH = "/assets/img/articles/";

    private List<Tag> translatedTags;

    private String slug;

    /**
     * Memoized derived values. Each is rebuilt from immutable state, and an ArticleModel is
     * constructed per request, so caching them here is safe and confined to one thread.
     *
     * <p>This matters because the templates read these repeatedly: article.html alone
     * references {@code ${article.description}} four times, and without the memo each evaluation
     * would re-run the whole language switch plus getAuthorsAsText(), which loops every author
     * and does four localized-text scans apiece.
     */
    private String memoDescription;
    private String memoAuthorsAsText;
    private String memoDateAsText;

    /**
     * The share card's image, as a site-absolute path.
     *
     * <p>Reads {@code visibleImages()} rather than {@code getImages()}: an image the studio has
     * hidden is one the operator inserted into the body by URL, and it must not become the
     * article's picture on Facebook by virtue of having been uploaded first.
     *
     * <p>Returns {@code ""} and never null -- {@code fragments/head.html} tests with
     * {@code #strings.isEmpty} precisely because a null test would emit
     * {@code og:image} naming the bare host for an article with no images.
     */
    public String getImage() {

        String imageFilename;
        List<ImageMedia> images = super.visibleImages();

        if(images != null && images.size() > 0) {
            imageFilename = PATH + images.get(0).getFilename();
        } else {
            imageFilename = "";
        }

        return imageFilename;
    }

    /** The MIME type of {@link #getImage()}, for {@code og:image:type}. Same source, necessarily. */
    public String getContent() {
        String content;
        List<ImageMedia> images = super.visibleImages();

        if(images != null && images.size() > 0) {
            content = images.get(0).getContent();
        } else {
            content = "";
        }

        return content;
    }

    @Override
    public String getTitle() {
        String result = null;
        if (lang == null || lang.isBlank() || LanguageChain.original().equalsIgnoreCase(lang)) {
            result = super.getTitle();
        } else {
            result = getLocalizedText(super.getTitles(), super.getTitle(), this.getLang());
        }
        return result;
    }

    public String getSlug() {
        if (this.slug != null && !this.slug.isBlank()) {
            return this.slug;
        }
        String title = getTitle();
        if (title == null || title.isBlank()) {
            return "article";
        }
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim();
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    /**
     * The article's description in the reader's language, or -- for an article that has none --
     * a line built from its date, authors and title, so the description meta is never empty.
     */
    @Override
    public String getDescription() {
        if (memoDescription != null) {
            return memoDescription;
        }

        // The scalar field is the original language, as in getTitle()/getText(); the list holds
        // the translations.
        String own = lang == null || lang.isBlank() || LanguageChain.original().equalsIgnoreCase(lang)
                ? super.getDescription()
                : getLocalizedText(super.getDescriptions(), super.getDescription(), this.getLang());
        if (own != null && !own.isBlank()) {
            memoDescription = own;
        } else {
            memoDescription = getDateAsText() + "; " + getAuthorsAsText() + "; "
                    + getLocalizedText(super.getTitles(), super.getTitle(), this.getLang());
        }

        return memoDescription;
    }

    @Override
    public String getText() {
        String result = null;
        if (lang == null || lang.isBlank() || LanguageChain.original().equalsIgnoreCase(lang)) {
            result = super.getText();
        } else {
            result = getLocalizedText(super.getTexts(), super.getText(), this.getLang());
        }
        return result;
    }

    @Override
    public List<String> getTags() {
        List<String> tags = super.getTags();
        return tags != null ? tags : new ArrayList<>();
    }

    public List<Tag> getTranslatedTags() {
        log.debug("getTranslatedTags() called");
        log.debug("translatedTags: {}", this.translatedTags);
        if(this.translatedTags == null) {
            this.translatedTags = new ArrayList<>();
        }
        return this.translatedTags;
    }

    public String getAuthorsAsText() {
        if (memoAuthorsAsText != null) {
            return memoAuthorsAsText;
        }
        StringBuilder sb = new StringBuilder(256);
        if(this.getAuthors() != null && this.getAuthors().size()>0) {
            for (Author author : this.getAuthors()) {
                String title = getLocalizedText(author.getTitles(), author.getTitle(), this.getLang());
                String firstName = getLocalizedText(author.getFirstnames(), author.getFirstname(), this.getLang());
                String lastName = getLocalizedText(author.getLastnames(), author.getLastname(), this.getLang());
                String description = getLocalizedText(author.getDescriptions(), author.getDescription(), this.getLang());

                if (title != null) sb.append(title.toLowerCase()).append(" ");
                if (firstName != null) sb.append(firstName).append(" ");
                if (lastName != null) sb.append(lastName);
                if (description != null && !description.isBlank()) sb.append(", ").append(description);
                sb.append("; ");
            }
        } else {
            sb.append("");
        }
        int index = sb.lastIndexOf(";");
        if(index > 0) sb.deleteCharAt(index);

        memoAuthorsAsText = sb.toString();
        return memoAuthorsAsText;
    }

    public String getLocalizedText(List<TextItem> items, String defaultText, String lang) {
        if (items != null && lang != null) {
            for (TextItem item : items) {
                if (lang.equalsIgnoreCase(item.getLang())) {
                    return item.getText();
                }
            }
        }
        // Fallback to English if not found
        if (items != null && !"en".equalsIgnoreCase(lang)) {
             for (TextItem item : items) {
                if ("en".equalsIgnoreCase(item.getLang())) {
                    return item.getText();
                }
            }
        }
        
        return defaultText;
    }

    public String getDateAsText() {
        if (memoDateAsText == null) {
            memoDateAsText = this.getDate() != null
                    ? DateFormatter.formatter.format(this.getDate())
                    : "";
        }
        return memoDateAsText;
    }

    public String getIssue() {
        String issue;

        if (this.getDate() != null) {
            issue = this.getDate().getMonthValue() + "/" + this.getDate().getYear();
        } else {
            issue = "";
        }


        return issue;
    }

    public boolean hasTags() {
        boolean result = false;

        if (this.getTags() != null && !this.getTags().isEmpty()) {
            result = true;
        }

        return result;
    }

    public boolean hasTranslation(String lang) {
        if (lang == null || LanguageChain.original().equalsIgnoreCase(lang)) return false;

        return hasTranslationInList(super.getTitles(), lang) ||
                hasTranslationInList(super.getDescriptions(), lang) ||
                hasTranslationInList(super.getTexts(), lang);
    }

    private boolean hasTranslationInList(List<TextItem> items, String lang) {
        if (items != null) {
            for (TextItem item : items) {
                if (lang.equalsIgnoreCase(item.getLang())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void translateTags(List<Tag> allTags, String lang) {
        log.debug("Translating tags for lang: {}", lang);
        // The tag collection only supplies translations; it must not decide visibility. It is
        // rebuilt from article.tags by a nightly job, so a keyword typed into the studio has no
        // document until then; dropped here, it would leave hasTags() reading true and the
        // heading rendered over an empty block. A tag with no document renders by its own name,
        // which is what Tag.translate does for one with no translations.
        if (tags == null || tags.isEmpty()) {
            return;
        }
        Map<String, Tag> byName = new HashMap<>();
        if (allTags != null) {
            for (Tag t : allTags) {
                if (t != null && t.getName() != null) {
                    byName.putIfAbsent(t.getName(), t);
                }
            }
        }
        translatedTags = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                Tag known = byName.get(tag);
                translatedTags.add(known != null ? known : Tag.builder().name(tag).build());
            }
        }
    }

    public Boolean showArticleInfo() {
        boolean showArticleInfo = true;
        if(super.getSuppressArticleInfo() != null && super.getSuppressArticleInfo()) {
            showArticleInfo = false;
        }

        return showArticleInfo;
    }

    public ArticleModel(Article article) {
        super(article);
    }
    
    public ArticleModel(Article article, String lang) {
        super(article);
        this.setLang(lang);
    }

    public ArticleModel(Article article, String lang, List<Tag> allTags) {
        super(article);
        this.setLang(lang);
        translateTags(allTags, lang);
    }
}
