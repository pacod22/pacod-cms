package io.pacod.cms.site.data;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.site.testing.TestSites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The address each reader page is filed under and the alternates it names, on a website written in
 * Bulgarian ({@code OriginalLanguageExtension}) that offers Bulgarian, English, German and Russian.
 *
 * <p>The rule under test is one address per language: the original by the bare route, every other
 * by its prefix (or {@code ?lang=} where the route takes none), the canonical naming the language
 * the page actually shows, and an alternate only for a language both the content and the website
 * have.
 */
class HeadMetaTest {

    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");

    private static SiteChrome site(String... codes) {
        List<Language> languages = new ArrayList<>();
        for (String code : codes) {
            languages.add(new Language(code));
        }
        return SiteChrome.of(TestSites.settings(), Map.of(), "http://localhost", languages, "/", null);
    }

    private static final SiteChrome SITE = site("bg", "en", "de", "ru");

    private static TextItem item(String lang, String text) {
        return TextItem.builder().lang(lang).text(text).build();
    }

    /** Bulgarian, translated into English and German -- and French, which the website does not offer. */
    private static Article article() {
        Article article = new Article(5);
        article.setTitle("Заглавие");
        article.setText("<p>Тяло</p>");
        article.setTexts(List.of(item("en", "<p>Body</p>"), item("de", "<p>Text</p>"), item("fr", "<p>Corps</p>")));
        article.setCategories(List.of(new Category(9, "Култура", List.of(item("de", "Kultur")), "bg")));
        article.setTags(List.of("космос"));
        Author author = new Author();
        author.setFirstname("Иван");
        author.setLastname("Петров");
        author.setFirstnames(List.of(item("de", "Iwan")));
        article.setAuthors(List.of(author, new Author()));
        article.setDate(LocalDateTime.of(2026, 3, 4, 12, 0));
        article.setLastModifiedOn(LocalDateTime.of(2026, 3, 5, 8, 30));
        return article;
    }

    private static HeadMeta article(String lang, SiteChrome site) {
        return HeadMeta.article(new ArticleModel(article(), lang, List.of()), site, SOFIA, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("an article is filed under the language it shows, and names its translations the website offers")
    void articleAddresses() {
        HeadMeta original = article("bg", SITE);
        assertThat(original.canonical()).isEqualTo("/article/5");
        assertThat(original.alternates()).extracting(HeadMeta.Alternate::hreflang, HeadMeta.Alternate::path)
                .containsExactly(tuple("bg", "/article/5"), tuple("en", "/en/article/5"),
                        tuple("de", "/de/article/5"), tuple("x-default", "/article/5"));
        // Every spelling of the German page names one address, and the same alternates.
        assertThat(article("de", SITE).canonical()).isEqualTo("/de/article/5");
        assertThat(article("de-AT", SITE).canonical()).isEqualTo("/de/article/5");
        assertThat(article("de", SITE).alternates()).isEqualTo(original.alternates());
        // Russian is offered but not written: the page shows the English body, and is a copy of it.
        assertThat(article("ru", SITE).canonical()).isEqualTo("/en/article/5");
        // French is written but not offered: its own address, and no alternate anywhere names it.
        assertThat(article("fr", SITE).canonical()).isEqualTo("/fr/article/5");
        assertThat(original.alternates()).extracting(HeadMeta.Alternate::hreflang).doesNotContain("fr", "ru");
    }

    @Test
    @DisplayName("an article's kind, dates, section, tags and authors are what the head says about it")
    void articleFacts() {
        HeadMeta german = article("de", SITE);
        assertThat(german.isArticle()).isTrue();
        assertThat(german.type()).isEqualTo("article");
        assertThat(german.published()).isEqualTo("2026-03-04T12:00:00+02:00");
        // Stamped 08:30 by a UTC JVM: 10:30 in the website's zone.
        assertThat(german.modified()).isEqualTo("2026-03-05T10:30:00+02:00");
        assertThat(german.section()).isEqualTo("Kultur");
        assertThat(german.tags()).containsExactly("космос");
        // An author with no name is left out rather than listed blank.
        assertThat(german.authors()).containsExactly("Iwan Петров");
        assertThat(german.feed()).isEqualTo("/feed.xml?lang=de");
        assertThat(german.sectionFeed()).isNull();
        assertThat(article("bg", SITE).feed()).isEqualTo("/feed.xml");
    }

    @Test
    @DisplayName("an article the website shows in one language alone names no alternates")
    void noAlternatesBelowTwo() {
        assertThat(article("bg", site("bg")).alternates()).isEmpty();
        assertThat(article("bg", site()).alternates()).isEmpty();
        // No chrome at all (a standalone controller test): the canonical, and nothing to choose from.
        HeadMeta bare = article("de", null);
        assertThat(bare.canonical()).isEqualTo("/de/article/5");
        assertThat(bare.alternates()).isEmpty();
        assertThat(bare.feed()).isEqualTo("/feed.xml?lang=de");
    }

    @Test
    @DisplayName("an untranslated article is still written in its original, whatever the website offers")
    void untranslatedArticle() {
        Article plain = new Article(6);
        plain.setText("<p>Тяло</p>");
        plain.setLastModifiedOn(null);
        HeadMeta head = HeadMeta.article(new ArticleModel(plain, "de"), SITE, SOFIA, ZoneOffset.UTC);

        assertThat(head.canonical()).isEqualTo("/article/6");
        assertThat(head.alternates()).isEmpty();
        assertThat(head.published()).isNull();
        assertThat(head.modified()).isNull();
        assertThat(head.section()).isNull();
        // An empty database's /latest renders an article with no id: no address of its own.
        assertThat(HeadMeta.article(new ArticleModel(new Article(), "bg"), SITE, SOFIA, ZoneOffset.UTC).canonical())
                .isNull();
    }

    @Test
    @DisplayName("the home page and /latest take ?lang=, in every language the website offers")
    void homeAndLatest() {
        HeadMeta home = HeadMeta.home("de", SITE);
        assertThat(home.canonical()).isEqualTo("/?lang=de");
        assertThat(home.isArticle()).isFalse();
        assertThat(home.alternates()).extracting(HeadMeta.Alternate::hreflang, HeadMeta.Alternate::path)
                .containsExactly(tuple("bg", "/"), tuple("en", "/?lang=en"), tuple("de", "/?lang=de"),
                        tuple("ru", "/?lang=ru"), tuple("x-default", "/"));
        assertThat(HeadMeta.home("bg", SITE).canonical()).isEqualTo("/");
        assertThat(HeadMeta.home(null, SITE).canonical()).isEqualTo("/");

        HeadMeta latest = HeadMeta.latest("en", SITE);
        assertThat(latest.canonical()).isEqualTo("/latest?lang=en");
        assertThat(latest.type()).isEqualTo("website");
        assertThat(latest.alternates()).extracting(HeadMeta.Alternate::path)
                .containsExactly("/latest", "/latest?lang=en", "/latest?lang=de", "/latest?lang=ru", "/latest");
        // A code no route could carry is no language: the original's address.
        assertThat(HeadMeta.home("\"><x", SITE).canonical()).isEqualTo("/");
    }

    @Test
    @DisplayName("a list is filed under its language and page, with the category's own feed")
    void lists() {
        HeadMeta category = HeadMeta.list("9", 0, true, "de", SITE);
        assertThat(category.canonical()).isEqualTo("/de/list/9");
        assertThat(category.alternates()).extracting(HeadMeta.Alternate::path)
                .containsExactly("/list/9", "/en/list/9", "/de/list/9", "/ru/list/9", "/list/9");
        assertThat(category.feed()).isEqualTo("/feed.xml?lang=de");
        assertThat(category.sectionFeed()).isEqualTo("/list/9/feed.xml?lang=de");

        // Past the first page (the pager counts from 0), every language's address names the page.
        HeadMeta third = HeadMeta.list("9", 2, true, "bg", SITE);
        assertThat(third.canonical()).isEqualTo("/list/9?page=2");
        assertThat(third.alternates()).extracting(HeadMeta.Alternate::path).contains("/de/list/9?page=2");
        assertThat(third.sectionFeed()).isEqualTo("/list/9/feed.xml");

        // A tag: its own name, encoded, and no feed of its own.
        HeadMeta tag = HeadMeta.list("космос и наука", 0, false, "en", SITE);
        assertThat(tag.canonical()).isEqualTo("/en/list/%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81%20%D0%B8%20%D0%BD%D0%B0%D1%83%D0%BA%D0%B0");
        assertThat(tag.sectionFeed()).isNull();
        // A language the website does not offer reads the original's feed, not an empty one.
        assertThat(HeadMeta.list("9", 0, true, "fr", SITE).sectionFeed()).isEqualTo("/list/9/feed.xml");
    }

    @Test
    @DisplayName("a standing page is filed under the language served, with alternates for the bodies it has")
    void pages() {
        Page about = new Page("about");
        about.putTitle("bg", "За нас");
        about.putBody("bg", "<p>Текст</p>");
        about.putBody("de", "<p>Text</p>");
        // An English title over no English body: /en/p/about serves the Bulgarian text, a copy.
        about.putTitle("en", "About");

        HeadMeta german = HeadMeta.page(about, "de", SITE);
        assertThat(german.canonical()).isEqualTo("/de/p/about");
        assertThat(german.alternates()).extracting(HeadMeta.Alternate::hreflang, HeadMeta.Alternate::path)
                .containsExactly(tuple("bg", "/p/about"), tuple("de", "/de/p/about"), tuple("x-default", "/p/about"));
        // /en/p/about was served in Bulgarian, which PageService said: the original's address.
        assertThat(HeadMeta.page(about, "bg", SITE).canonical()).isEqualTo("/p/about");
    }

    @Test
    @DisplayName("search results name no canonical and no alternates, only the feed")
    void minimal() {
        HeadMeta search = HeadMeta.minimal("de", SITE);
        assertThat(search.canonical()).isNull();
        assertThat(search.alternates()).isEmpty();
        assertThat(search.isArticle()).isFalse();
        assertThat(search.feed()).isEqualTo("/feed.xml?lang=de");
        assertThat(search.posting(SITE, "de", "x", "y", null)).isNull();
    }

    @Test
    @DisplayName("an article's BlogPosting names its headline without markup, its dates, authors, page and publisher")
    void posting() {
        Article article = article();
        article.setTitle("<em>Заглавие</em> &amp; още");
        article.setLastModifiedOn(LocalDateTime.of(2026, 3, 5, 8, 30));
        HeadMeta head = HeadMeta.article(new ArticleModel(article, "bg", List.of()), SITE, SOFIA, ZoneOffset.UTC);

        Map<String, Object> json = head.posting(SITE, "bg", article.getTitle(), "Описание", "https://www.example.org/a.jpg");
        assertThat(json).containsEntry("@context", "https://schema.org").containsEntry("@type", "BlogPosting")
                .containsEntry("headline", "Заглавие & още").containsEntry("description", "Описание")
                .containsEntry("image", "https://www.example.org/a.jpg")
                .containsEntry("datePublished", "2026-03-04T12:00:00+02:00")
                .containsEntry("dateModified", "2026-03-05T10:30:00+02:00")
                .containsEntry("inLanguage", "bg")
                .containsEntry("author", List.of(Map.of("@type", "Person", "name", "Иван Петров")))
                .containsEntry("mainEntityOfPage", Map.of("@type", "WebPage", "@id", "https://www.example.org/article/5"));
        assertThat(json.get("publisher")).isEqualTo(Map.of("@type", "Organization", "name", TestSites.NAME_BG,
                "logo", Map.of("@type", "ImageObject", "url", "https://www.example.org/media/brand/logo.svg")));

        // Nothing it lacks is written as null.
        Map<String, Object> sparse = HeadMeta.article(new ArticleModel(new Article(6), "bg"), SITE, SOFIA, ZoneOffset.UTC)
                .posting(SiteChrome.empty("http://localhost"), "bg", "Заглавие", null, null);
        assertThat(sparse).doesNotContainKeys("image", "description", "datePublished", "author", "publisher")
                .doesNotContainValue(null);
    }
}
