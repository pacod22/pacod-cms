package io.pacod.cms.core.design;

import java.util.List;
import java.util.Optional;

/**
 * What a layout is made of: the vocabulary every template set implements some of. A set's
 * manifest names the types it renders; a section of a type the set does not implement is kept in
 * the layout and flagged, never rendered and never dropped.
 *
 * <p>The id is what is stored and what names the set's fragment ({@code sections :: articleList}).
 * Ids are never renamed: stored layouts refer to them.
 */
public enum SectionType {

    /** The page's own content -- the article, the category's list, the standing page. No settings. */
    PRIMARY("primary", "primary", List.of()),

    ARTICLE_LIST("article-list", "articleList", List.of(
            SettingField.choice("source", "latest", "category", "most-read"),
            SettingField.category("category"),
            SettingField.number("count", 3, 1, 24),
            SettingField.number("offset", 0, 0, 24),
            SettingField.choice("style", "cards", "hero", "list", "ranked", "video"),
            SettingField.text("heading", false, 120))),

    RICH_TEXT("rich-text", "richText", List.of(
            SettingField.text("heading", false, 120),
            SettingField.html("html", true, 20_000))),

    CALL_TO_ACTION("call-to-action", "callToAction", List.of(
            SettingField.text("heading", true, 120),
            SettingField.longText("text", false, 500),
            SettingField.text("label", true, 60),
            SettingField.url("url", true))),

    SEARCH_BOX("search-box", "searchBox", List.of(
            SettingField.text("heading", false, 120))),

    SOCIAL_LINKS("social-links", "socialLinks", List.of(
            SettingField.text("heading", false, 120)));

    private final String id;
    private final String fragment;
    private final List<SettingField> fields;

    SectionType(String id, String fragment, List<SettingField> fields) {
        this.id = id;
        this.fragment = fragment;
        this.fields = fields;
    }

    public String id() {
        return id;
    }

    /** The fragment a set renders it with, in its {@code sections} template. */
    public String fragment() {
        return fragment;
    }

    public List<SettingField> fields() {
        return fields;
    }

    public Optional<SettingField> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    public static Optional<SectionType> byId(String id) {
        for (SectionType type : values()) {
            if (type.id.equals(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
