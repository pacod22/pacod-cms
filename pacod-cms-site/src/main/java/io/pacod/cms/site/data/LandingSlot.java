package io.pacod.cms.site.data;

import java.util.List;

/**
 * One {@code article-list} section's worth of articles.
 *
 * <p>A slot is a category id, its localized display name, and the articles already sliced to
 * the window that section renders. The slicing lives in {@code PageComposer} rather than in the
 * template, because two sections -- a {@code hero} and the list beside it, say -- can be
 * different windows (a count and an offset) onto the <em>same</em> category read, and
 * expressing that as index arithmetic in {@code th:each} would be both unreadable and
 * untestable.
 *
 * <h2>Why the explicit getters</h2>
 * Thymeleaf resolves {@code ${slot.name}} through SpEL, which looks for {@code getName()} /
 * {@code isName()}. Record component accessors ({@code name()}) are not reliably picked up
 * across Spring versions, and a miss here would surface as a template render failure rather
 * than a compile error. Records may declare extra methods, so the JavaBean accessors cost
 * nothing and remove the question entirely.
 *
 * <h2>Immutability</h2>
 * {@code items} is always built with {@link List#copyOf}, because instances of this record are
 * cached (see {@code CategoryWidgetReader}). Slicing or otherwise mutating a cached list would
 * corrupt every subsequent request; with an immutable list the attempt throws instead.
 */
public record LandingSlot(Integer categoryId, String name, List<ListItem> items) {

    /** Rendered when a category is empty or its read failed. Never null, so templates need no guard. */
    public static final LandingSlot EMPTY = new LandingSlot(null, "", List.of());

    public LandingSlot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The {@code hero} style renders a single article; null when the list is empty. */
    public ListItem getFirst() {
        return items.isEmpty() ? null : items.get(0);
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public List<ListItem> getItems() {
        return items;
    }
}
