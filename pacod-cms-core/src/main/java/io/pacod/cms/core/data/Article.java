package io.pacod.cms.core.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Indexes below back the finders in {@code ArticleRepository}. Without them every category,
 * author, tag and date-sorted query is a full collection scan.
 */
@Slf4j
@Data
@Document(collection = "article")
@CompoundIndexes({
        // findArticleByCategories(...) sorts by { priority: -1, createdOn: -1 }
        @CompoundIndex(name = "cat_priority_created",
                def = "{'categories.$id': 1, 'priority': -1, 'createdOn': -1}"),
        // findArticleByAuthors(...) sorts by { createdOn: -1 }
        @CompoundIndex(name = "author_created", def = "{'authors.$id': 1, 'createdOn': -1}"),
        // findArticleByTags(...) - tags is an embedded array, so this is a multikey index
        @CompoundIndex(name = "tags_created", def = "{'tags': 1, 'createdOn': -1}"),
        // countArticleByImages(...) is what stands between a shared image and a broken <img>, and
        // it is called once per row of the console's image library, once per delete, and once per
        // image of a deleted article. Without this it is a full collection scan every time --
        // which a small development seed cannot tell apart from an indexed lookup, so nothing
        // short of this line would surface it.
        @CompoundIndex(name = "images_idx", def = "{'images.$id': 1}")
})
public class Article {
    @Id
    private Integer id;

    private String title;
    private List<TextItem> titles;

    private String description;
    private List<TextItem> descriptions;

    private String text;
    private List<TextItem> texts;

    /**
     * The body as it stood before the last management-console save.
     *
     * <p>Written only by {@code MgmtArticleService}, and only when the body actually changed. The
     * console's editor parses HTML into a schema and re-serializes it, so opening an article can
     * rewrite markup that nobody intended to touch -- imported or hand-written HTML above all. One
     * level of history is enough to undo that; it is not a revision system.
     *
     * <p>{@code @JsonIgnore} is not cosmetic. {@code Article} is serialized directly by
     * {@code GET /web/v1/article/&#123;id&#125;} -- public and unauthenticated. Without it, every
     * edited article would ship its previous body to the public site as well as its current one:
     * double the payload, and a superseded draft made publicly readable. Jackson only; Spring
     * Data's mapping is independent, so the field still persists and still restores.
     */
    @JsonIgnore
    private String previousText;

    private String embedded;

    protected String lang;

    private Integer priority;

    private String titleClasses;
    private String titleOverlayClasses;

    private Boolean suppressArticleInfo;

    @DBRef
    protected List<Author> authors;

    @DBRef
    private List<Category> categories;

    @DBRef
    private List<ImageMedia> images;

    /**
     * Ids of attached images that must not appear on the public site.
     *
     * <p>An attached image is normally a gallery slide, and the first one is also the share card.
     * The console can insert any image into the body by URL, and an image used that way should
     * not also show up in the strip above the article -- hence a per-article exclusion rather than
     * a per-image one. It cannot live on {@link ImageMedia}: the console's image library attaches
     * one image to several articles, and each decides for itself.
     *
     * <p>Null or empty means every attached image is visible, which is what an article that never
     * set it says by omission -- so nothing migrates. Read through {@link #visibleImages()};
     * nothing outside the console should look at the raw ids.
     */
    private List<Integer> hiddenImageIds;


    // Default sort for the /list pages.
    @Indexed(name = "date_idx")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime date;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    private ArticleStatus status;

    // Backs findTop3ByIdGreaterThan...OrderByCounterDesc (the "most read" list).
    @Indexed(name = "counter_idx")
    private Integer counter;

    protected List<String> tags;

    public Article() {
    }

    public Article(Integer id) {
        this.id = id;
    }

    public Article(Article article) {
        this.id = article.id;
        this.title = article.title;
        this.titles = article.titles;
        this.description = article.description;
        this.descriptions = article.descriptions;
        this.text = article.text;
        this.texts = article.texts;
        this.previousText = article.previousText;
        this.embedded = article.embedded;
        this.lang = article.lang;
        this.priority = article.priority;
        this.authors = article.authors;
        this.categories = article.categories;
        this.images = article.images;
        this.hiddenImageIds = article.hiddenImageIds;
        this.date = article.date;
        this.createdOn = article.createdOn;
        this.lastModifiedOn = article.lastModifiedOn;
        this.status = article.status;
        this.counter = article.counter;

        this.suppressArticleInfo = article.suppressArticleInfo;

        this.titleOverlayClasses = article.titleOverlayClasses;
        this.titleClasses = article.titleClasses;
        this.tags = article.tags;

    }

    /**
     * The attached images the public site may show, in order.
     *
     * <p>Element 0 is the cover: the share card, every list and widget thumbnail, and the first
     * gallery slide. Deliberately the first <em>visible</em> image rather than {@code images[0]},
     * so hiding whatever happens to sit at the front does not leave it as the article's
     * social-media picture.
     *
     * <p>Not named {@code getVisibleImages}: {@code Article} is serialized by the public
     * {@code /web/v1} controllers, and a getter would add a second, duplicated image array to
     * every response. Same reason {@code fullname()} and {@code toLongView()} are
     * spelled the way they are. Always returns a list, never null -- an article whose images are
     * all hidden reads exactly like one with no images at all.
     */
    public List<ImageMedia> visibleImages() {

        if (images == null || images.isEmpty()) {
            return List.of();
        }

        if (hiddenImageIds == null || hiddenImageIds.isEmpty()) {
            return images;
        }

        List<ImageMedia> visible = new ArrayList<>(images.size());
        for (ImageMedia image : images) {
            if (image != null && !hiddenImageIds.contains(image.getId())) {
                visible.add(image);
            }
        }
        return visible;
    }

    /**
     * The title stored for exactly {@code lang}, or {@code null}: the {@code titles} entry for that
     * language, and for {@link LanguageChain#original()} the scalar {@code title} when there is
     * none -- the source lives there, and an article that was never translated has no
     * {@code titles} at all. No fallback to another language; the caller walks its own chain. May
     * carry markup.
     *
     * <p>Not a getter, for {@link #visibleImages()}'s reason.
     */
    public String titleIn(String lang) {
        return storedIn(titles, title, lang);
    }

    /**
     * The body stored for exactly {@code lang}, or {@code null}: {@link #titleIn}'s rule applied to
     * {@code texts} and {@code text}. What a PDF's enrichment is computed from and checked against,
     * so a fallback body is never summarised as if it were the requested language's.
     *
     * <p>Not a getter, for {@link #visibleImages()}'s reason.
     */
    public String textIn(String lang) {
        return storedIn(texts, text, lang);
    }

    /** The description stored for exactly {@code lang}, or {@code null}; {@link #titleIn}'s rule. */
    public String descriptionIn(String lang) {
        return storedIn(descriptions, description, lang);
    }

    private static String storedIn(List<TextItem> items, String scalar, String lang) {
        String code = LanguageChain.normalise(lang);
        if (code == null) {
            return null;
        }
        for (TextItem item : items == null ? List.<TextItem>of() : items) {
            if (item != null && code.equalsIgnoreCase(item.getLang())
                    && item.getText() != null && !item.getText().isBlank()) {
                return item.getText();
            }
        }
        if (LanguageChain.original().equals(code) && scalar != null && !scalar.isBlank()) {
            return scalar;
        }
        return null;
    }

    /**
     * The filename of this article's cover -- its first visible image with a filename -- or
     * {@code null} when it has none. The image the public site uses as the article's share card
     * and list thumbnail, and what {@code /social} and its console show beside a link to the
     * article: one rule, so the three cannot pick different pictures.
     *
     * <p>Not spelled {@code getCoverFilename}, for {@link #visibleImages()}'s reason: this document
     * is serialized by the public {@code /web/v1} controllers, and a getter would add a field to
     * every response.
     */
    public String coverFilename() {
        for (ImageMedia image : visibleImages()) {
            if (image != null && image.getFilename() != null && !image.getFilename().isBlank()) {
                return image.getFilename();
            }
        }
        return null;
    }

    /**
     * The languages this article's body has been <em>translated</em> into: lowercase, deduplicated
     * and sorted.
     *
     * <p>Reads {@link #texts} and nothing else, exactly as {@code Page.writtenIn()} reads its
     * bodies and for the reason {@code MgmtLanguageService.pageCounts()} states: a translated
     * title with no body behind it is not a translation anybody can read. A blank text counts as
     * absent, the same rule {@code Page.put} enforces on the way in.
     *
     * <p><b>{@link LanguageChain#original()} is excluded, and that is not tidying.</b> The article's
     * original text lives in the scalar {@link #text} field -- {@code ArticleModel.getText()}
     * serves it from there and never looks in {@code texts} -- so an entry in the original's code
     * here is not a translation of anything; an import or a tool that copied the original into
     * {@code texts} would otherwise put a chip on the article reading "translated into" the
     * language it was written in.
     *
     * <p>Sorted rather than left in stored order: the console renders one chip per language and an
     * article can carry many, in whatever order they happened to be written.
     *
     * <p>Deliberately not spelled {@code getTranslatedIn}. {@code Article} is serialized straight
     * out of the public {@code /web/v1} controllers, and a getter would add a language array to
     * every response -- the same reason {@link #visibleImages()} is spelled the way it is.
     */
    public List<String> translatedIn() {

        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<String> codes = new ArrayList<>();
        for (TextItem item : texts) {
            if (item == null || item.getText() == null || item.getText().isBlank()
                    || item.getLang() == null || item.getLang().isBlank()) {
                continue;
            }
            String code = item.getLang().trim().toLowerCase();
            if (!LanguageChain.original().equals(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }

        codes.sort(Comparator.naturalOrder());
        return codes;
    }

    public void addTag(String tag) {

        if(this.tags == null) {
            this.tags = new ArrayList<>(1);
        }
        this.tags.add(tag);

        lastModifiedOn = LocalDateTime.now();
    }

    /**
     * An empty or null argument clears the tags. Guarding the whole body with
     * {@code if (tags != null && !tags.isEmpty())} would make clearing an article's tags a silent
     * no-op -- the previous tags would stay and only {@code lastModifiedOn} would move.
     */
    public void setTags(List<String> tags) {

        if (tags == null || tags.isEmpty()) {
            this.tags = new ArrayList<>();
        } else {
            if (this.tags == null) {
                this.tags = new ArrayList<>(tags.size());
            } else {
                this.tags.clear();
            }

            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    this.tags.add(tag.strip());
                }
            }
        }

        lastModifiedOn = LocalDateTime.now();
    }

    public void setTitle(String title) {
        this.title = title;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setTitles(List<TextItem> titles) {
        this.titles = titles;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setDescription(String description) {
        this.description = description;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setDescriptions(List<TextItem> descriptions) {
        this.descriptions = descriptions;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setText(String text) {
        this.text = text;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setTexts(List<TextItem> texts) {
        this.texts = texts;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setPreviousText(String previousText) {
        this.previousText = previousText;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public void setAuthors(List<Author> authors) {
        this.authors = authors;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setCategories(List<Category> categories) {
        log.debug("Changing the categories: \nnew categories: {}\nold categories: {}", categories, this.categories);
        this.categories = categories;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setImages(List<ImageMedia> images) {
        this.images = images;

        lastModifiedOn = LocalDateTime.now();
    }

    public void setDate(LocalDateTime date) {
        this.date = date;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setCreatedOn(LocalDateTime createdOn) {
        this.createdOn = createdOn;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setStatus(ArticleStatus status) {
        this.status = status;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setEmbedded(String embedded) {
        this.embedded = embedded;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setTitleClasses(String titleClasses) {
        this.titleClasses = titleClasses;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setTitleOverlayClasses(String titleOverlayClasses) {
        this.titleOverlayClasses = titleOverlayClasses;
        lastModifiedOn = LocalDateTime.now();
    }

    public void setSuppressArticleInfo(Boolean suppressArticleInfo) {
        this.suppressArticleInfo = suppressArticleInfo;
        lastModifiedOn = LocalDateTime.now();
    }

    public Boolean getSuppressArticleInfo() {
        return suppressArticleInfo;
    }

    @Override
    public String toString() {
        return "Article{" +
                "id=" + id +
                ", authors=" + authors +
                ", categories=" + categories +
                '}';
    }

    public String toLongView() {
        return "Article{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", authors=" + authors +
                ", categories=" + categories +
                ", images=" + images +
                ", date=" + date +
                ", createdOn=" + createdOn +
                ", lastModifiedOn=" + lastModifiedOn +
                ", status=" + status +
                ", suppressArticleInfo=" + suppressArticleInfo +
                ", titleClasses=" + titleClasses +
                ", titleOverlayClasses=" + titleOverlayClasses +
                ", counter=" + counter +
                ", tags=" + tags +
                '}';
    }
}
