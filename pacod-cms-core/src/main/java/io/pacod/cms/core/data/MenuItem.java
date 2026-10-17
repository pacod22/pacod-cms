package io.pacod.cms.core.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of a {@link Menu}.
 *
 * <p>An item with {@link #children} is a group: it renders as a heading that opens its children,
 * and its own {@link #kind} is normally {@link MenuLinkKind#NONE}. Groups nest one level; the
 * public templates render no deeper.
 *
 * <p>Deliberately no field called {@code id}: Spring Data maps any property of that name to
 * {@code _id}, embedded documents included, and an item needs no identity of its own.
 */
@Data
public class MenuItem {

    /** The label, per language. */
    private List<TextItem> labels = new ArrayList<>();

    private MenuLinkKind kind = MenuLinkKind.NONE;

    /**
     * What the link points at: a category id for {@link MenuLinkKind#CATEGORY}, a page slug for
     * {@link MenuLinkKind#PAGE}, an address for {@link MenuLinkKind#URL}; unused otherwise.
     */
    private String target;

    /** How many columns a group's panel lays its children out in; null lets the template decide. */
    private Integer columns;

    private List<MenuItem> children = new ArrayList<>();

    public String label(List<String> chain) {
        return TextItems.resolve(labels, chain);
    }

    public boolean isGroup() {
        return children != null && !children.isEmpty();
    }
}
