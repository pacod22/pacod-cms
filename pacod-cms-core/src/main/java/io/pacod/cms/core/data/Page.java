package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One standing page of the website -- about, contact, advertise, terms, privacy and the rest of
 * the material that is not an article and does not belong in any listing. Served at
 * {@code /p/{slug}} and edited in the console one language at a time.
 *
 * <p>This is the one mechanism for "a page". A page kept as an {@code Article} hidden from the
 * listings would give "a page" two unrelated implementations, and the reader could not tell
 * which one they were on.
 *
 * <h2>The slug is the id</h2>
 * The identifier is the thing the URL is made of, so there is nothing to allocate and no
 * {@code getMaxId} read-then-write.
 *
 * <h2>Translations are a list, not a map</h2>
 * {@link TextItem} is what {@code Article.titles} and {@code Article.texts} already use, so the
 * console's language handling and this document's are the same shape. A missing language is an
 * <b>absent entry</b>, never an empty string: {@link #body(List)} has to tell "not translated
 * yet, fall back and say so" from "deliberately blank", and the two look identical once you store
 * {@code ""}. This is the {@code article.embedded} trap, avoided by construction.
 *
 * <h2>The fallback order is a chain, and it is not this document's business</h2>
 * Resolution takes a list of codes -- see {@link LanguageChain}. Nothing here names a default
 * language, which is what lets the order change without touching the stored shape, and what
 * keeps a document that cannot query anything from having an opinion about configuration.
 */
@Data
@Document(collection = "page")
public class Page {

    public static final String COLLECTION = "page";

    /** {@code "about"}, {@code "terms"}, … -- the last segment of {@code /p/{slug}}. */
    @Id
    private String slug;

    /** Per-language title. Absent for a language means "not translated". */
    private List<TextItem> titles = new ArrayList<>();

    /** Per-language body, HTML. Absent for a language means "not translated". */
    private List<TextItem> bodies = new ArrayList<>();

    /** Optional per-language meta description; falls back like the rest. */
    private List<TextItem> descriptions = new ArrayList<>();

    /** Unpublished pages 404 on the public site but stay editable in the console. */
    private boolean published = true;

    /**
     * True for the pages whose text in the original language ({@link LanguageChain#original()})
     * is the one that legally governs -- the terms of use and the privacy policy.
     *
     * <p>It does not stop a translation being stored. It makes the page <em>say</em>, in the
     * reader's language, that the translation is informational and the original prevails. A
     * machine-translated privacy policy presented as authoritative is a liability; the same text
     * with that sentence above it is a courtesy.
     */
    private boolean officialInDefaultLanguageOnly;

    /** Ascending, for the console list and any generated index. Ties fall back to the slug. */
    private Integer sortOrder = 0;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastModifiedOn;

    public Page() {
    }

    public Page(String slug) {
        this.slug = slug;
        LocalDateTime now = LocalDateTime.now();
        this.createdOn = now;
        this.lastModifiedOn = now;
    }

    /* ------------------------------------------------------------------ resolution */

    /**
     * The title, taking the first language in {@code chain} that has one.
     *
     * <p>Deliberately not spelled {@code getTitle}. A getter here would be picked up by Jackson
     * and by Thymeleaf's property resolution as if it were a stored field, which is the mistake
     * {@code Article.visibleImages()} documents -- and, worse, it would need an argument it cannot
     * have. Callers pass a chain explicitly, which also makes it obvious at the call site that a
     * language is being chosen.
     *
     * <p><b>Resolved and raw are two differently-named methods, never one overloaded name.</b>
     * {@code title(List)} and {@code rawTitle(String)} are the same question asked with and
     * without a fallback, and SpEL picking the wrong one of an overloaded pair is precisely the
     * kind of silent wrong answer this document's whole "absent, never blank" rule exists to
     * avoid: {@code PageForm} showing the original-language text in the empty German tab would
     * store the original as a translation.
     */
    public String title(List<String> chain) {
        return resolve(titles, chain);
    }

    public String body(List<String> chain) {
        return resolve(bodies, chain);
    }

    public String description(List<String> chain) {
        return resolve(descriptions, chain);
    }

    /** The title stored for exactly this language, or {@code null}. No fallback. */
    public String rawTitle(String lang) {
        return find(titles, lang);
    }

    public String rawBody(String lang) {
        return find(bodies, lang);
    }

    public String rawDescription(String lang) {
        return find(descriptions, lang);
    }

    /** Whether {@code lang} has its own body, as opposed to being served the fallback. */
    public boolean hasBody(String lang) {
        return find(bodies, lang) != null;
    }

    /**
     * Which languages this page is actually written in, as bare codes, in stored order.
     *
     * <p>Read off the document rather than filtered from the configured language list, so it
     * stays true for a body written in a language that has since been removed from the console's
     * list -- the text does not disappear because a row did. Callers that want display names
     * intersect this with the language collection.
     */
    public List<String> writtenIn() {
        List<String> codes = new ArrayList<>();
        for (TextItem item : bodies == null ? List.<TextItem>of() : bodies) {
            if (item != null && item.getText() != null && !item.getText().isBlank()
                    && item.getLang() != null && !codes.contains(item.getLang().toLowerCase())) {
                codes.add(item.getLang().toLowerCase());
            }
        }
        return codes;
    }

    /** Whether any of the three texts is stored in {@code lang} -- the language delete guard. */
    public boolean hasAnyTextIn(String lang) {
        return find(titles, lang) != null || find(bodies, lang) != null
                || find(descriptions, lang) != null;
    }

    private String resolve(List<TextItem> items, List<String> chain) {
        if (chain == null) {
            return null;
        }
        for (String lang : chain) {
            String found = find(items, lang);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * @return the stored text, or {@code null} when this language has no entry. Blank stored text
     *         counts as absent for the reason the class javadoc gives.
     */
    private String find(List<TextItem> items, String lang) {
        if (items == null || lang == null) {
            return null;
        }
        String code = LanguageChain.normalise(lang);
        if (code == null) {
            return null;
        }

        for (TextItem item : items) {
            if (item != null && code.equalsIgnoreCase(item.getLang())
                    && item.getText() != null && !item.getText().isBlank()) {
                return item.getText();
            }
        }
        return null;
    }

    /* --------------------------------------------------------------------- writing */

    /** Upserts one language's text, removing the entry entirely when {@code text} is blank. */
    public void put(List<TextItem> items, String lang, String text) {
        items.removeIf(item -> item == null || lang.equalsIgnoreCase(item.getLang()));
        if (text != null && !text.isBlank()) {
            items.add(TextItem.builder().lang(lang).text(text).build());
        }
    }

    public void putTitle(String lang, String text) {
        put(titles, lang, text);
    }

    public void putBody(String lang, String text) {
        put(bodies, lang, text);
    }

    public void putDescription(String lang, String text) {
        put(descriptions, lang, text);
    }
}
