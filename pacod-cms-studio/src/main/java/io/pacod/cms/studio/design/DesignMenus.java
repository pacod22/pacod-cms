package io.pacod.cms.studio.design;

import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.MenuItem;
import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.data.TextItems;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.util.SocialTargets;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The website's two menus, {@code main} and {@code footer}: items with a label per language, what
 * they link to, and -- one level deep -- the items of a group. Saved as they are edited, with no
 * draft: the site shows a change within a minute.
 *
 * <p>An item is addressed by its position, {@code 2} or {@code 2.1} for a group's item: a
 * {@link MenuItem} has no id of its own (an embedded property called {@code id} would be stored as
 * {@code _id}). The rules for what a link may name are {@code SiteChrome}'s: a category's number,
 * a page's slug, an http(s) address or one on the website.
 */
@Service
public class DesignMenus {

    public static final List<String> KEYS = List.of(Menu.MAIN, Menu.FOOTER);
    private static final Pattern CATEGORY = Pattern.compile("\\d{1,9}");
    private static final Pattern PAGE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern POSITION = Pattern.compile("(\\d{1,3})(?:\\.(\\d{1,3}))?");
    public static final int LABEL_MAX = 80;

    private final MenuRepository menus;

    public DesignMenus(MenuRepository menus) {
        this.menus = menus;
    }

    public Menu load(String key) {
        requireKey(key);
        return menus.findById(key).orElseGet(() -> new Menu(key));
    }

    /** What the form posts for one item. */
    public record ItemForm(Map<String, String> labels, MenuLinkKind kind, String target) {
    }

    /** A new item at the end of the menu, or of a group ({@code parent} its position). */
    public void add(String key, String parent, ItemForm form) {
        Menu menu = load(key);
        MenuItem item = build(form);
        if (parent == null || parent.isBlank()) {
            menu.getItems().add(item);
        } else {
            MenuItem group = at(menu, parent);
            if (parent.contains(".")) {
                throw new MgmtOperationException("design.menu.error.depth");
            }
            group.getChildren().add(item);
        }
        menus.save(menu);
    }

    public void update(String key, String position, ItemForm form) {
        Menu menu = load(key);
        MenuItem item = at(menu, position);
        MenuItem built = build(form);
        item.setLabels(built.getLabels());
        item.setKind(built.getKind());
        item.setTarget(built.getTarget());
        menus.save(menu);
    }

    public void move(String key, String position, int delta) {
        Menu menu = load(key);
        List<MenuItem> list = listOf(menu, position);
        int i = index(position);
        int j = i + Integer.signum(delta);
        if (j >= 0 && j < list.size()) {
            list.add(j, list.remove(i));
            menus.save(menu);
        }
    }

    public void remove(String key, String position) {
        Menu menu = load(key);
        List<MenuItem> list = listOf(menu, position);
        list.remove(index(position));
        menus.save(menu);
    }

    private MenuItem build(ItemForm form) {
        List<TextItem> labels = new ArrayList<>();
        for (Map.Entry<String, String> label : form.labels().entrySet()) {
            String text = label.getValue() == null ? "" : label.getValue().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > LABEL_MAX) {
                throw new MgmtOperationException("design.menu.error.label", LABEL_MAX);
            }
            labels = TextItems.put(labels, label.getKey(), text);
        }
        if (labels.isEmpty()) {
            throw new MgmtOperationException("design.menu.error.noLabel");
        }
        MenuLinkKind kind = form.kind() == null ? MenuLinkKind.NONE : form.kind();
        String target = form.target() == null ? null : form.target().strip();
        switch (kind) {
            case CATEGORY -> require(target != null && CATEGORY.matcher(target).matches(), "design.menu.error.category");
            case PAGE -> require(target != null && PAGE.matcher(target).matches(), "design.menu.error.page");
            case URL -> require(target != null && SocialTargets.isValidTarget(target), "design.menu.error.url");
            default -> target = null;
        }
        MenuItem item = new MenuItem();
        item.setLabels(labels);
        item.setKind(kind);
        item.setTarget(target);
        return item;
    }

    private static void require(boolean ok, String code) {
        if (!ok) {
            throw new MgmtOperationException(code);
        }
    }

    private static void requireKey(String key) {
        if (!KEYS.contains(key)) {
            throw new MgmtNotFoundException("design.menu.notFound", key);
        }
    }

    private static MenuItem at(Menu menu, String position) {
        List<MenuItem> list = listOf(menu, position);
        int i = index(position);
        if (i >= list.size()) {
            throw new MgmtOperationException("design.menu.error.item");
        }
        return list.get(i);
    }

    private static List<MenuItem> listOf(Menu menu, String position) {
        var m = POSITION.matcher(position == null ? "" : position);
        if (!m.matches()) {
            throw new MgmtOperationException("design.menu.error.item");
        }
        if (m.group(2) == null) {
            return menu.getItems();
        }
        int parent = Integer.parseInt(m.group(1));
        if (parent >= menu.getItems().size()) {
            throw new MgmtOperationException("design.menu.error.item");
        }
        return menu.getItems().get(parent).getChildren();
    }

    private static int index(String position) {
        var m = POSITION.matcher(position);
        if (!m.matches()) {
            throw new MgmtOperationException("design.menu.error.item");
        }
        return Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(1));
    }
}
