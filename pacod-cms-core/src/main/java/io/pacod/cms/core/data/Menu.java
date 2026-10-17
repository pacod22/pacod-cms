package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * A navigation tree: the masthead's menu ({@link #MAIN}) or the links at the foot of every page
 * ({@link #FOOTER}).
 *
 * <p>Navigation is data, never written into the templates, where every category link would be a
 * hardcoded {@code /list/{id}} and adding a category would mean editing markup. The items name
 * their target by kind and reference -- a category id, a page slug -- and the public site builds
 * the address, so a link to a page always carries the reader's language and cannot drift from the
 * route that serves it.
 *
 * <p>The id is the menu's key and is never editable: templates ask for a menu by it.
 */
@Data
@Document(collection = Menu.COLLECTION)
public class Menu {

    public static final String COLLECTION = "menu";

    public static final String MAIN = "main";
    public static final String FOOTER = "footer";

    @Id
    private String key;

    private List<MenuItem> items = new ArrayList<>();

    public Menu() {
    }

    public Menu(String key) {
        this.key = key;
    }
}
