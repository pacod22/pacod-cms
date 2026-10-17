package io.pacod.cms.core.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.Tag;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one language's translation into a document without touching anything else in it, so a
 * translation job that waits seconds for the model never overwrites what an editor or another job
 * wrote meanwhile. Two ways, by the document's shape:
 *
 * <ul>
 *   <li><b>A top-level {@code TextItem} list</b> -- an article's titles, descriptions and texts; a
 *       page's titles, descriptions and bodies; a category's names; a tag's translations: one
 *       pipeline update per document ({@link #put(Class, Object, String, Map)}) whose
 *       {@code $filter} keeps every other language's entry and whose {@code $concatArrays} adds
 *       this one's. Nothing is read first, so there is nothing to be stale.</li>
 *   <li><b>Lists nested in embedded documents</b> -- a menu's items and their children, the social
 *       page's links: read raw ({@link #read}), rebuilt by the caller with {@link #withText}, and
 *       written back by {@link #replaceIfUnchanged}, a compare-and-set on the very values read. An
 *       edit made meanwhile makes it match nothing, and the caller says "run it again" rather
 *       than overwrite the edit.</li>
 * </ul>
 *
 * <p>A blank value removes that language's entry, as {@code ArticleTranslations.put} does: a
 * translation is never {@code ""}. Codes are written lowercase and replace an entry of any case,
 * as {@code Page}'s reads match them.
 */
@Service
public class TranslationWriter {

    private final MongoTemplate template;

    public TranslationWriter(MongoTemplate template) {
        this.template = template;
    }

    /** An article's title, description and body; @return whether the article was there */
    public boolean put(int articleId, String lang, String title, String description, String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("titles", title);
        fields.put("descriptions", description);
        fields.put("texts", body);
        return put(Article.class, articleId, lang, fields);
    }

    /** A page's title, description and body ({@code Page}'s id is its slug). */
    public boolean putPage(String slug, String lang, String title, String description, String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("titles", title);
        fields.put("descriptions", description);
        fields.put("bodies", body);
        return put(Page.class, slug, lang, fields);
    }

    /** A category's name. */
    public boolean putCategory(int id, String lang, String name) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("names", name);
        return put(Category.class, id, lang, fields);
    }

    /** A tag's name ({@code Tag}'s id is its original-language name). */
    public boolean putTag(String name, String lang, String translation) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("translations", translation);
        return put(Tag.class, name, lang, fields);
    }

    /** {@code fields}: list field → this language's value (null or blank removes it). */
    boolean put(Class<?> type, Object id, String lang, Map<String, String> fields) {
        String code = code(lang);
        Document set = new Document();
        fields.forEach((field, value) -> set.append(field, replace(field, code, value)));
        return template.getCollection(template.getCollectionName(type))
                .updateOne(new Document("_id", id), List.of(new Document("$set", set)))
                .getMatchedCount() > 0;
    }

    /** The document as stored, for a caller that rebuilds nested lists and writes them back. */
    public Optional<Document> read(Class<?> type, Object id) {
        return Optional.ofNullable(template.getCollection(template.getCollectionName(type))
                .find(new Document("_id", id)).first());
    }

    /**
     * Sets each field of {@code after} only while every field of {@code before} still holds
     * exactly what it held when read -- pass the raw values {@link #read} returned, never a copy
     * mapped through an entity, which would differ in some detail and match nothing.
     *
     * @return false when the document changed or went meanwhile; nothing was written
     */
    public boolean replaceIfUnchanged(Class<?> type, Object id, Map<String, Object> before, Map<String, Object> after) {
        Document filter = new Document("_id", id);
        before.forEach(filter::append);
        return template.getCollection(template.getCollectionName(type))
                .updateOne(filter, new Document("$set", new Document(after)))
                .getMatchedCount() > 0;
    }

    /**
     * A raw {@code TextItem} list with {@code lang}'s entry replaced by {@code value} (removed when
     * blank), every other entry kept as it was; {@code items} itself is not changed.
     */
    public static List<Document> withText(List<?> items, String lang, String value) {
        String code = code(lang);
        List<Document> result = new ArrayList<>();
        for (Object item : items == null ? List.of() : items) {
            if (item instanceof Document entry && !code.equalsIgnoreCase(String.valueOf(entry.get("lang")))) {
                result.add(entry);
            }
        }
        if (value != null && !value.isBlank()) {
            result.add(new Document("text", value).append("lang", code));
        }
        return result;
    }

    /** The text a raw {@code TextItem} list holds for {@code lang}, or null; blank counts as absent. */
    public static String textIn(List<?> items, String lang) {
        for (Object item : items == null ? List.of() : items) {
            if (item instanceof Document entry && lang.equalsIgnoreCase(String.valueOf(entry.get("lang")))
                    && entry.get("text") instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /** {@code field} without {@code lang}'s entry, then that entry again when {@code value} is not blank. */
    static Document replace(String field, String lang, String value) {
        Document others = new Document("$filter", new Document("input", new Document("$ifNull", List.of("$" + field, List.of())))
                .append("cond", new Document("$ne", List.of(
                        new Document("$toLower", new Document("$ifNull", List.of("$$this.lang", ""))),
                        new Document("$literal", lang)))));
        List<Object> added = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            // $literal: inside a pipeline a string starting with "$" -- "$100 off" -- is a field path.
            added.add(new Document("text", new Document("$literal", value)).append("lang", new Document("$literal", lang)));
        }
        return new Document("$concatArrays", List.of(others, added));
    }

    private static String code(String lang) {
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException("A translation needs a language");
        }
        return lang.strip().toLowerCase(Locale.ROOT);
    }
}
