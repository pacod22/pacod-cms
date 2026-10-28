package io.pacod.cms.site.data;

import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.*;
import io.pacod.cms.site.utils.DateFormatter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

    /**
     * Minutes to read the body at 230 words a minute, never under one: the magazine set's byline.
     * A method, not a getter, so the article's JSON on /web/v1 does not change.
     */
    public int readingMinutes() {
        String text = getText();
        if (text == null || text.isBlank()) {
            return 1;
        }
        String plain = text.replaceAll("<[^>]*>", " ").strip();
        int words = plain.isEmpty() ? 0 : plain.split("\\s+").length;
        return Math.max(1, Math.round(words / 230f));
    }

    /** An author's job title, first name, last name or bio in this article's language. */
    public String authorText(List<TextItem> items, String defaultText) {
        return getLocalizedText(items, defaultText, this.getLang());
    }

    /** One author's name in this article's language: first and last, as the magazine set prints it. */
    public String authorName(Author author) {
        if (author == null) {
            return "";
        }
        return java.util.stream.Stream.of(authorText(author.getFirstnames(), author.getFirstname()),
                        authorText(author.getLastnames(), author.getLastname()))
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /** The authors' names alone, joined: the byline under the magazine set's headline. */
    public String authorNames() {
        if (getAuthors() == null) {
            return "";
        }
        return getAuthors().stream().map(this::authorName).filter(n -> !n.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** An author's initials, for the avatar beside the bio: "LV". */
    public String authorInitials(Author author) {
        StringBuilder initials = new StringBuilder(2);
        for (String part : authorName(author).split("\\s+")) {
            if (!part.isEmpty() && initials.length() < 2) {
                initials.appendCodePoint(part.codePointAt(0));
            }
        }
        return initials.toString().toUpperCase(java.util.Locale.ROOT);
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

    /**
     * The languages this article has an address in, as bare codes: its original first, then every
     * language it is translated into, sorted.
     *
     * <p>A translation is a body of its own ({@link Article#translatedIn()}, the console's chips),
     * not merely a title: a title-only translation renders the original's body under it, and a
     * page of that is a duplicate of the original's, not an alternate of it. {@link HeadMeta}'s
     * canonical and {@code hreflang} links and the feed's language filter all read this one
     * list, so they cannot disagree. Not a getter, for {@link Article#visibleImages()}'s reason.
     */
    public List<String> languages() {
        List<String> codes = new ArrayList<>();
        codes.add(LanguageChain.original());
        for (String code : translatedIn()) {
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * The language of the body {@link #getText()} renders for this article's {@code lang}: that
     * language when the article is translated into it, else English when it is translated into
     * English (the fallback {@link #getLocalizedText} tries next), else the original. What the
     * page's canonical address names: {@code /fr/article/5} on a Bulgarian website, with only an
     * English translation, shows the English text, and is a copy of {@code /en/article/5}.
     */
    public String servedLanguage() {
        String code = LanguageChain.normalise(getLang());
        String original = LanguageChain.original();
        if (code == null || code.equals(original)) {
            return original;
        }
        List<String> translated = translatedIn();
        if (translated.contains(code)) {
            return code;
        }
        if (!LanguageChain.FALLBACK.equals(code) && translated.contains(LanguageChain.FALLBACK)) {
            return LanguageChain.FALLBACK;
        }
        return original;
    }

    /**
     * When the article was published, in {@code zone}: its {@code date} -- the editor's wall clock,
     * typed in the website's zone -- else its {@code createdOn}; null when it has neither. To the
     * second, for {@code article:published_time}, the structured data and the feed.
     */
    public OffsetDateTime publishedAt(ZoneId zone) {
        LocalDateTime when = getDate() != null ? getDate() : getCreatedOn();
        return when != null ? when.atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS) : null;
    }

    /**
     * When the article was last changed, in {@code zone}, never before {@link #publishedAt}.
     *
     * <p>{@code lastModifiedOn} is not the editor's clock: every setter stamps it with
     * {@code LocalDateTime.now()}, the JVM's zone, which is {@code stampZone} here. A seed that
     * copies the date into it, or a back-dated article, would otherwise read as changed before
     * it appeared. Falls back to the publication time.
     */
    public OffsetDateTime modifiedAt(ZoneId zone, ZoneId stampZone) {
        OffsetDateTime published = publishedAt(zone);
        LocalDateTime stamped = getLastModifiedOn();
        if (stamped == null) {
            return published;
        }
        OffsetDateTime modified = stamped.atZone(stampZone).withZoneSameInstant(zone).toOffsetDateTime()
                .truncatedTo(ChronoUnit.SECONDS);
        return published != null && modified.isBefore(published) ? published : modified;
    }

    /**
     * The first category's name in this article's language, or null: {@code article:section}. The
     * scalar name is the original's, as an article's title is; the list holds the translations.
     */
    public String sectionName() {
        if (getCategories() == null) {
            return null;
        }
        for (Category category : getCategories()) {
            if (category != null) {
                String code = LanguageChain.normalise(getLang());
                String name = code == null || code.equals(LanguageChain.original())
                        ? category.getName()
                        : getLocalizedText(category.getNames(), category.getName(), code);
                return name != null && !name.isBlank() ? name.strip() : null;
            }
        }
        return null;
    }

    /** The tags as a reader sees them, translated where the tag collection has a translation. */
    public List<String> tagNames() {
        List<String> names = new ArrayList<>();
        List<String> source = new ArrayList<>();
        if (!getTranslatedTags().isEmpty()) {
            getTranslatedTags().forEach(tag -> source.add(tag.translate(getLang())));
        } else {
            source.addAll(getTags());
        }
        for (String name : source) {
            if (name != null && !name.isBlank() && !names.contains(name.strip())) {
                names.add(name.strip());
            }
        }
        return names;
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
