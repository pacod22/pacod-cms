package io.pacod.cms.site.it;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.services.TranslationWriter;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The pipeline update against a real MongoDB: one language replaced, every other entry and field kept. */
class TranslationWriterIT extends TwoWebsitesIT {

    @Autowired
    TranslationWriter writer;

    @Autowired
    MongoTemplate template;

    @Test
    @DisplayName("one language's entries are replaced in place; the others, and everything else, stay")
    void replacesOneLanguage() {
        TenantContext.runAs(alpha, () -> {
            Article article = new Article();
            article.setId(77);
            article.setTitle("Original title");
            article.setText("<p>Original</p>");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setDate(LocalDateTime.now());
            article.setCounter(5);
            article.setTitles(new ArrayList<>(List.of(new TextItem("Titel alt", "de"), new TextItem("Titre", "fr"))));
            article.setTexts(new ArrayList<>(List.of(new TextItem("<p>Alt</p>", "de"))));
            articles.save(article);

            assertThat(writer.put(77, "de", "$100 off", "", "<p>Neu</p>")).isTrue();
            Article stored = articles.findById(77).orElseThrow();
            assertThat(stored.getTitles()).extracting(TextItem::getLang, TextItem::getText)
                    .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("fr", "Titre"),
                            org.assertj.core.groups.Tuple.tuple("de", "$100 off"));
            assertThat(stored.getTexts()).extracting(TextItem::getText).containsExactly("<p>Neu</p>");
            // Blank removes, never stores "".
            assertThat(stored.getDescriptions() == null ? List.<TextItem>of() : stored.getDescriptions()).isEmpty();
            assertThat(stored.getTitle()).isEqualTo("Original title");
            assertThat(stored.getCounter()).isEqualTo(5);

            assertThat(writer.put(78, "de", "x", "x", "x")).isFalse();
        });
    }

    @Test
    @DisplayName("a page by its slug, a category by its number with no list yet, a tag by its name; any case of the code is replaced")
    void otherShapes() {
        TenantContext.runAs(alpha, () -> {
            Page page = new Page("about");
            page.getTitles().add(new TextItem("About", "en"));
            page.getTitles().add(new TextItem("Alt", "DE"));
            page.getBodies().add(new TextItem("<p>About</p>", "en"));
            page.setOfficialInDefaultLanguageOnly(true);
            template.save(page);
            assertThat(writer.putPage("about", "DE", "Über uns", null, "<p>Über</p>")).isTrue();
            Page stored = template.findById("about", Page.class);
            assertThat(stored.getTitles()).extracting(TextItem::getLang, TextItem::getText)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("en", "About"),
                            org.assertj.core.groups.Tuple.tuple("de", "Über uns"));
            assertThat(stored.rawBody("de")).isEqualTo("<p>Über</p>");
            assertThat(stored.isOfficialInDefaultLanguageOnly()).isTrue();

            Category category = new Category(9, "News", null, "en");
            template.save(category);
            assertThat(writer.putCategory(9, "de", "$ Nachrichten")).isTrue();
            assertThat(template.findById(9, Category.class).getNames()).extracting(TextItem::getText).containsExactly("$ Nachrichten");

            template.save(new Tag("spring", null));
            assertThat(writer.putTag("spring", "de", "Frühling")).isTrue();
            assertThat(template.findById("spring", Tag.class).getTranslations()).extracting(TextItem::getText).containsExactly("Frühling");
            assertThat(writer.putTag("autumn", "de", "Herbst")).isFalse();
        });
    }

    @Test
    @DisplayName("compare-and-set: written while the field holds what was read, refused once it changed")
    void compareAndSet() {
        TenantContext.runAs(alpha, () -> {
            Menu menu = new Menu();
            menu.setKey("cas");
            template.save(menu);
            template.getCollection(Menu.COLLECTION).updateOne(new Document("_id", "cas"), new Document("$set",
                    new Document("items", List.of(new Document("labels", List.of(new Document("text", "Home").append("lang", "en")))
                            .append("kind", "HOME")))));

            Document read = writer.read(Menu.class, "cas").orElseThrow();
            List<?> items = read.getList("items", Object.class);
            Document item = new Document((Document) items.get(0));
            item.put("labels", TranslationWriter.withText(item.getList("labels", Object.class), "de", "Start"));
            assertThat(writer.replaceIfUnchanged(Menu.class, "cas", Map.of("items", items), Map.of("items", List.of(item)))).isTrue();
            Menu stored = template.findById("cas", Menu.class);
            assertThat(stored.getItems().get(0).getLabels()).extracting(TextItem::getLang).containsExactly("en", "de");

            // What was read before that write no longer matches: nothing is written.
            assertThat(writer.replaceIfUnchanged(Menu.class, "cas", Map.of("items", items), Map.of("items", List.of()))).isFalse();
            assertThat(template.findById("cas", Menu.class).getItems()).hasSize(1);
        });
    }
}
