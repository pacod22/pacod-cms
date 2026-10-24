package io.pacod.cms.site.data;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.site.utils.DateFormatter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Data
public class ListItem {

    private Integer id;
    private String title;
    private String embedded;
    private String description;
    private List<Author> authors;
    private List<ImageMedia> images;
    private LocalDateTime date;
    private Integer counter;
    private String lang;

    private String titleClasses;
    private String titleOverlayClasses;

    /**
     * The first category, for a card's kicker in the magazine set. No bean getter on purpose
     * ({@link #kickerId()}, {@link #kickerName()}): the /web/v1 lists serialise this class, and a
     * getter would add a field to every one of them.
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private Integer kickerId;

    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private String kickerName;

    public ListItem(Article article) {
        this(article, null);
    }

    public ListItem(Article article, String lang) {
        if(article != null) {
            this.id = article.getId();
            this.lang = lang;
            this.title = getLocalizedText(article.getTitles(), article.getTitle(), lang);
            this.embedded = article.getEmbedded();
            this.description = getLocalizedText(article.getDescriptions(), article.getDescription(), lang);
            this.authors = article.getAuthors();
            // visibleImages(), not getImages(): getImageFilename() below reads element 0, and this
            // item is a card in a list or a widget -- an image the studio hid from the public site
            // must not become the thumbnail either.
            this.images = article.visibleImages();
            this.date = article.getDate();
            this.counter = article.getCounter();

            this.titleClasses = article.getTitleClasses();
            this.titleOverlayClasses = article.getTitleOverlayClasses();
            if (article.getCategories() != null) {
                article.getCategories().stream().filter(java.util.Objects::nonNull).findFirst().ifPresent(category -> {
                    this.kickerId = category.getId();
                    this.kickerName = getLocalizedText(category.getNames(), category.getName(), lang);
                });
            }
        } else {
            log.error("Cannot initialize WebArticle. The article object was null!");
        }
    }

    public String getImageFilename() {
        String imageFilename;

        if(images != null && images.size()>0 && images.get(0) != null && images.get(0).getFilename() != null) {
            imageFilename = images.get(0).getFilename();

        } else {
            imageFilename = "";
        }
        return imageFilename;
    }

    /** The first image's alt text in this item's language; its caption where it has none. */
    public String getImageAlt() {
        if (images != null && !images.isEmpty() && images.get(0) != null) {
            return images.get(0).altFor(lang);
        }
        return "";
    }

    public String getImageCaption() {
        String imageCaption;

        if(images != null && images.size()>0 && images.get(0) != null && images.get(0).getCaption() != null) {
            imageCaption = images.get(0).getCaption();

        } else {
            imageCaption = "";
        }
        return imageCaption;
    }

    public String getTitle() {
        return title;
    }

    /*
    public String getSlug() {

        if (title == null || title.isBlank()) {
            return "article";
        }
        String slug =  title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim();
        log.info("Slug: " + slug);
        return slug;
    }
    */

    /** The first category's id, or null: where a card's kicker links. */
    public Integer kickerId() {
        return kickerId;
    }

    /** The first category's name in this item's language, or null. */
    public String kickerName() {
        return kickerName;
    }

    /** The authors' names alone, without their job titles: "Liora Venn, Tomas Arkwright". */
    public String authorNames() {
        if (authors == null) {
            return "";
        }
        return authors.stream().filter(java.util.Objects::nonNull)
                .map(a -> java.util.stream.Stream.of(
                                getLocalizedText(a.getFirstnames(), a.getFirstname(), lang),
                                getLocalizedText(a.getLastnames(), a.getLastname(), lang))
                        .filter(n -> n != null && !n.isBlank())
                        .collect(java.util.stream.Collectors.joining(" ")))
                .filter(n -> !n.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** How many authors the byline names, for the template's "Author:" / "Authors:" label. */
    public int getAuthorCount() {
        return authors == null ? 0 : (int) authors.stream().filter(java.util.Objects::nonNull).count();
    }

    public String getAuthorsAsText() {
        StringBuilder sb = new StringBuilder(128);

        if(authors != null ) {
            // Names only: the "Author:" / "Authors:" label is chrome, rendered by the template
            // from the message bundles (widgets :: authors), with getAuthorCount() choosing which.
            for(Author author : authors) {
                if(author != null) {
                    String title = getLocalizedText(author.getTitles(), author.getTitle(), this.lang);
                    String firstName = getLocalizedText(author.getFirstnames(), author.getFirstname(), this.lang);
                    String lastName = getLocalizedText(author.getLastnames(), author.getLastname(), this.lang);

                    if(title != null) sb.append(title).append(" ");
                    if(firstName != null) sb.append(firstName).append(" ");
                    if(lastName != null) sb.append(lastName).append(", ");
                }
            }
            if(sb.lastIndexOf(",") >0) sb.deleteCharAt(sb.lastIndexOf(","));
        } else {
            sb.append("");
        }
        return sb.toString();
    }

    private String getLocalizedText(List<TextItem> items, String defaultText, String lang) {
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
        String dateAsString;

        if (date != null) {
            dateAsString = DateFormatter.formatter.format(date);
        } else {
            dateAsString = "";
        }


        return dateAsString;
    }

}
