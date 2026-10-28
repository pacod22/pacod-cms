package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.services.TagService;
import io.pacod.cms.site.services.SiteChromeReader;
import io.pacod.cms.site.testing.TestSites;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Atom feeds' contract, against mocked repositories on a website written in Bulgarian: which
 * articles, in which order and how many, in which language, with every address absolute, and a
 * 503 rather than an empty feed when the database cannot be read. That the queries return only
 * published articles is pinned against a real MongoDB in {@code PublicArticlesIT}; here a draft a
 * query let through is dropped all the same.
 *
 * <p>Standalone MockMvc, for the reason {@code ArticleTemplateControllerTest} gives.
 */
class FeedControllerTest {

    private static final String ATOM = "http://www.w3.org/2005/Atom";

    private ArticleRepository articles;
    private CategoryRepository categories;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        articles = mock(ArticleRepository.class);
        categories = mock(CategoryRepository.class);
        SiteChromeReader chrome = mock(SiteChromeReader.class);
        when(chrome.settings()).thenReturn(TestSites.settings());
        TagService tags = mock(TagService.class);
        when(tags.getAllTags()).thenReturn(List.of());

        mockMvc = MockMvcBuilders.standaloneSetup(new FeedController(articles, categories, chrome, tags)).build();
    }

    /** Published, dated by its id, by Ivan Petrov, in category 9; never modified after its date. */
    private static Article article(int id) {
        Article article = new Article(id);
        article.setTitle("Заглавие " + id);
        article.setDescription("Описание " + id);
        article.setText("<p>Тяло " + id + "</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setDate(LocalDateTime.of(2026, 3, 1, 12, 0).plusDays(id));
        Author author = new Author();
        author.setFirstname("Иван");
        author.setLastname("Петров");
        article.setAuthors(List.of(author));
        article.setCategories(List.of(new Category(9, "Култура", List.of(item("de", "Kultur")), "bg")));
        // Last: every setter stamps it with the clock, in whatever zone this machine is in.
        article.setLastModifiedOn(null);
        return article;
    }

    private static Article german(int id) {
        Article article = article(id);
        article.setTitles(List.of(item("de", "Titel " + id)));
        article.setDescriptions(List.of(item("de", "Beschreibung " + id)));
        article.setTexts(List.of(item("de", "<p>Text " + id + "</p>")));
        article.setLastModifiedOn(null);
        return article;
    }

    private static TextItem item(String lang, String text) {
        return TextItem.builder().lang(lang).text(text).build();
    }

    private static org.springframework.data.domain.Page<Article> page(List<Article> content, int page, long total) {
        return new PageImpl<>(content, PageRequest.of(page, FeedController.ENTRIES), total);
    }

    private static Document xml(MvcResult result) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
    }

    private static List<Element> entries(Document feed) {
        NodeList nodes = feed.getElementsByTagNameNS(ATOM, "entry");
        return IntStream.range(0, nodes.getLength()).mapToObj(i -> (Element) nodes.item(i)).toList();
    }

    private static String child(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ATOM, name);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static String link(Element parent, String rel) {
        NodeList links = parent.getElementsByTagNameNS(ATOM, "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            if (link.getParentNode() == parent && rel.equals(link.getAttribute("rel"))) {
                return link.getAttribute("href");
            }
        }
        return null;
    }

    @Test
    @DisplayName("/feed.xml is Atom: the newest published articles, in the query's order, at most twenty")
    void siteFeed() throws Exception {
        List<Article> newest = new ArrayList<>();
        for (int id = 30; id > 5; id--) {
            newest.add(article(id));
        }
        newest.get(1).setStatus(ArticleStatus.DRAFT);
        newest.get(2).setStatus(ArticleStatus.PRIVATE);
        when(articles.findPublished(any(Pageable.class))).thenReturn(page(newest, 0, 200));

        MvcResult result = mockMvc.perform(get("/feed.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/atom+xml;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andReturn();

        // The latest list's query, first page of twenty; one page is all the original's feed reads.
        verify(articles).findPublished(PageRequest.of(0, FeedController.ENTRIES));
        verify(articles, never()).findPublished(PageRequest.of(1, FeedController.ENTRIES));

        Document feed = xml(result);
        Element root = feed.getDocumentElement();
        assertThat(root.getNamespaceURI()).isEqualTo(ATOM);
        assertThat(root.getLocalName()).isEqualTo("feed");
        assertThat(root.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang")).isEqualTo("bg");
        assertThat(root.getAttributeNS("http://www.w3.org/XML/1998/namespace", "base")).isEqualTo("https://www.example.org/");
        assertThat(child(root, "title")).isEqualTo(TestSites.NAME_BG);
        assertThat(child(root, "subtitle")).isEqualTo("Новини и ръководства");
        assertThat(child(root, "id")).isEqualTo("https://www.example.org/feed.xml");
        assertThat(link(root, "self")).isEqualTo("https://www.example.org/feed.xml");
        assertThat(link(root, "alternate")).isEqualTo("https://www.example.org/");
        assertThat(child(root, "icon")).isEqualTo("https://www.example.org/media/brand/favicon.svg");
        // The newest entry's date, as the feed's.
        assertThat(feed.getElementsByTagNameNS(ATOM, "updated").item(0).getTextContent())
                .isEqualTo("2026-03-31T12:00:00Z");

        List<Element> entries = entries(feed);
        assertThat(entries).hasSize(FeedController.ENTRIES);
        // 29 and 28 were a draft and a private article: never in a feed, whatever a query returned.
        assertThat(entries).extracting(entry -> child(entry, "id")).startsWith(
                "https://www.example.org/article/30", "https://www.example.org/article/27",
                "https://www.example.org/article/26")
                .doesNotContain("https://www.example.org/article/29", "https://www.example.org/article/28");

        Element first = entries.getFirst();
        assertThat(child(first, "title")).isEqualTo("Заглавие 30");
        assertThat(link(first, "alternate")).isEqualTo("https://www.example.org/article/30");
        assertThat(child(first, "published")).isEqualTo("2026-03-31T12:00:00Z");
        assertThat(child(first, "updated")).isEqualTo("2026-03-31T12:00:00Z");
        assertThat(child(first, "name")).isEqualTo("Иван Петров");
        assertThat(((Element) first.getElementsByTagNameNS(ATOM, "category").item(0)).getAttribute("term"))
                .isEqualTo("Култура");
        assertThat(child(first, "summary")).isEqualTo("Описание 30");
        Element content = (Element) first.getElementsByTagNameNS(ATOM, "content").item(0);
        assertThat(content.getAttribute("type")).isEqualTo("html");
        assertThat(content.getTextContent()).isEqualTo("<p>Тяло 30</p>");
    }

    @Test
    @DisplayName("?lang= lists only the articles translated into it, in their translation, reading further pages")
    void translatedFeed() throws Exception {
        // Page one has every third article in German; page two the rest of what is needed.
        List<Article> first = new ArrayList<>();
        for (int id = 100; id > 80; id--) {
            first.add(id % 3 == 0 ? german(id) : article(id));
        }
        List<Article> second = new ArrayList<>();
        for (int id = 80; id > 60; id--) {
            second.add(german(id));
        }
        when(articles.findPublished(PageRequest.of(0, FeedController.ENTRIES))).thenReturn(page(first, 0, 200));
        when(articles.findPublished(PageRequest.of(1, FeedController.ENTRIES))).thenReturn(page(second, 1, 200));

        Document feed = xml(mockMvc.perform(get("/feed.xml").param("lang", "de-DE"))
                .andExpect(status().isOk())
                .andReturn());

        Element root = feed.getDocumentElement();
        assertThat(root.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang")).isEqualTo("de");
        assertThat(child(root, "id")).isEqualTo("https://www.example.org/feed.xml?lang=de");
        assertThat(link(root, "alternate")).isEqualTo("https://www.example.org/?lang=de");
        // The website has no German name: the chain's English.
        assertThat(child(root, "title")).isEqualTo(TestSites.NAME_EN);

        List<Element> entries = entries(feed);
        assertThat(entries).hasSize(FeedController.ENTRIES);
        assertThat(entries).extracting(entry -> child(entry, "id")).startsWith(
                "https://www.example.org/de/article/99", "https://www.example.org/de/article/96")
                .noneMatch(id -> id.endsWith("/article/100") || id.endsWith("/article/98"));
        Element german = entries.getFirst();
        assertThat(child(german, "title")).isEqualTo("Titel 99");
        assertThat(child(german, "summary")).isEqualTo("Beschreibung 99");
        assertThat(child(german, "content")).isEqualTo("<p>Text 99</p>");
        assertThat(((Element) german.getElementsByTagNameNS(ATOM, "category").item(0)).getAttribute("term"))
                .isEqualTo("Kultur");
        verify(articles, never()).findPublished(PageRequest.of(2, FeedController.ENTRIES));
    }

    @Test
    @DisplayName("a translated feed reads five pages at most, and is short rather than expensive")
    void translatedFeedIsBounded() throws Exception {
        when(articles.findPublished(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable asked = invocation.getArgument(0);
            int top = 1000 - asked.getPageNumber() * FeedController.ENTRIES;
            List<Article> batch = new ArrayList<>();
            for (int id = top; id > top - FeedController.ENTRIES; id--) {
                batch.add(id == 1000 ? german(id) : article(id));
            }
            return page(batch, asked.getPageNumber(), 1000);
        });

        Document feed = xml(mockMvc.perform(get("/feed.xml").param("lang", "de")).andExpect(status().isOk()).andReturn());

        assertThat(entries(feed)).extracting(entry -> child(entry, "id"))
                .containsExactly("https://www.example.org/de/article/1000");
        verify(articles).findPublished(PageRequest.of(FeedController.SCAN_PAGES - 1, FeedController.ENTRIES));
        verify(articles, never()).findPublished(PageRequest.of(FeedController.SCAN_PAGES, FeedController.ENTRIES));
    }

    @Test
    @DisplayName("a body's root-relative addresses are made absolute; another host's and protocol-relative ones are not")
    void bodyAddressesAreAbsolute() {
        String origin = "https://www.example.org";
        assertThat(FeedController.absolute("<img src=\"/assets/img/a.jpg\" alt=\"\"> <a href='/p/about'>x</a>"
                + " <a href=\"//elsewhere.example/x\">y</a> <a href=\"https://cdn.example/z\">z</a>"
                + " <video poster=\"/assets/p.jpg\"></video> <a href=\"/\\evil.example\">w</a>"
                + " <img srcset=\"/a-1x.jpg 1x, https://cdn.example/b.jpg 2x,/c.jpg 3x\" src=\"#x\">", origin))
                .isEqualTo("<img src=\"https://www.example.org/assets/img/a.jpg\" alt=\"\">"
                        + " <a href='https://www.example.org/p/about'>x</a>"
                        + " <a href=\"//elsewhere.example/x\">y</a> <a href=\"https://cdn.example/z\">z</a>"
                        + " <video poster=\"https://www.example.org/assets/p.jpg\"></video> <a href=\"/\\evil.example\">w</a>"
                        + " <img srcset=\"https://www.example.org/a-1x.jpg 1x, https://cdn.example/b.jpg 2x,"
                        + " https://www.example.org/c.jpg 3x\" src=\"#x\">");
    }

    @Test
    @DisplayName("what XML 1.0 cannot carry is dropped, so the feed parses whatever the article holds")
    void unrepresentableCharactersAreDropped() throws Exception {
        Article hostile = article(7);
        hostile.setTitle("Нощ\u0001 & <em>ден</em> ]]> ￾");
        hostile.setText("<p>a\u0000b <img src=\"/x.jpg\"></p>");
        hostile.setAuthors(List.of());
        hostile.setLastModifiedOn(null);
        when(articles.findPublished(any(Pageable.class))).thenReturn(page(List.of(hostile), 0, 1));

        Document feed = xml(mockMvc.perform(get("/feed.xml")).andExpect(status().isOk()).andReturn());

        Element entry = entries(feed).getFirst();
        assertThat(child(entry, "title")).isEqualTo("Нощ & <em>ден</em> ]]> ");
        assertThat(child(entry, "content")).isEqualTo("<p>ab <img src=\"https://www.example.org/x.jpg\"></p>");
        // No author of its own: the feed's stands for it.
        assertThat(entry.getElementsByTagNameNS(ATOM, "author").getLength()).isZero();
        assertThat(child(feed.getDocumentElement(), "name")).isEqualTo(TestSites.NAME_BG);
    }

    @Test
    @DisplayName("an empty website's feed is valid Atom with no entries")
    void emptyFeed() throws Exception {
        when(articles.findPublished(any(Pageable.class))).thenReturn(page(List.of(), 0, 0));

        Document feed = xml(mockMvc.perform(get("/feed.xml")).andExpect(status().isOk()).andReturn());

        assertThat(entries(feed)).isEmpty();
        assertThat(child(feed.getDocumentElement(), "updated")).isNotBlank();
    }

    @Test
    @DisplayName("a database that cannot be read is a 503 with Retry-After, never an empty feed")
    void unreadableDatabase() throws Exception {
        when(articles.findPublished(any(Pageable.class))).thenThrow(new DataAccessResourceFailureException("down"));
        when(categories.findById(9)).thenThrow(new DataAccessResourceFailureException("down"));

        for (String path : List.of("/feed.xml", "/list/9/feed.xml")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "600"));
        }
    }

    @Test
    @DisplayName("/list/{id}/feed.xml is the category's, in the category list's query; anything else is a 404")
    void categoryFeed() throws Exception {
        when(categories.findById(9)).thenReturn(Optional.of(new Category(9, "Култура", List.of(item("de", "Kultur")), "bg")));
        when(articles.findArticleByCategoriesAndStatus(eq(9), eq(ArticleRepository.PUBLIC), any(Pageable.class)))
                .thenReturn(page(List.of(german(12), article(11)), 0, 2));

        Document feed = xml(mockMvc.perform(get("/list/9/feed.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/atom+xml;charset=UTF-8"))
                .andReturn());
        Element root = feed.getDocumentElement();
        assertThat(child(root, "title")).isEqualTo("Култура | " + TestSites.NAME_BG);
        assertThat(child(root, "id")).isEqualTo("https://www.example.org/list/9/feed.xml");
        assertThat(link(root, "alternate")).isEqualTo("https://www.example.org/list/9");
        assertThat(entries(feed)).extracting(entry -> child(entry, "id"))
                .containsExactly("https://www.example.org/article/12", "https://www.example.org/article/11");
        verify(articles).findArticleByCategoriesAndStatus(9, ArticleRepository.PUBLIC, PageRequest.of(0, FeedController.ENTRIES));

        Document german = xml(mockMvc.perform(get("/list/9/feed.xml").param("lang", "de")).andReturn());
        assertThat(child(german.getDocumentElement(), "title")).isEqualTo("Kultur | " + TestSites.NAME_EN);
        assertThat(link(german.getDocumentElement(), "alternate")).isEqualTo("https://www.example.org/de/list/9");
        assertThat(entries(german)).extracting(entry -> child(entry, "id"))
                .containsExactly("https://www.example.org/de/article/12");

        // No such category; the most-read list, which is none; a tag; a number too long to be an id.
        when(categories.findById(41)).thenReturn(Optional.empty());
        mockMvc.perform(get("/list/41/feed.xml")).andExpect(status().isNotFound());
        mockMvc.perform(get("/list/100001/feed.xml")).andExpect(status().isNotFound());
        mockMvc.perform(get("/list/alpha/feed.xml")).andExpect(status().isNotFound());
        mockMvc.perform(get("/list/12345678901/feed.xml")).andExpect(status().isNotFound());
        verify(categories, never()).findById(100001);
    }

    @Test
    @DisplayName("the feed is UTF-8 and declares it")
    void encoding() throws Exception {
        when(articles.findPublished(any(Pageable.class))).thenReturn(page(List.of(article(7)), 0, 1));

        String body = mockMvc.perform(get("/feed.xml")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\"")
                .contains("Заглавие 7");
    }

    /**
     * The feed's route beside the lists' own, as the application maps them: {@code /list/{id}} is
     * one segment and never takes {@code feed.xml}, and the feed's {@code {id}} is a number, so a
     * tag's list has no feed path to be confused with.
     */
    @Test
    @DisplayName("beside the list routes, /list/{id}/feed.xml is the feed and /list/{tag} is still the list")
    void routesBesideTheLists() throws Exception {
        io.pacod.cms.site.services.WebArticleService service = mock(io.pacod.cms.site.services.WebArticleService.class);
        when(service.getAllArticlesByCategoryPageable(any(), any(), any()))
                .thenReturn(io.pacod.cms.site.data.ArticleListPageable.builder().id(5).name("cat").build());
        when(service.getAllArticlesByTagPageable(any(), any(), any()))
                .thenReturn(io.pacod.cms.site.data.ArticleListPageable.builder().name("alpha").build());
        io.pacod.cms.site.render.PageComposer composer = mock(io.pacod.cms.site.render.PageComposer.class);
        when(composer.compose(any(), any())).thenReturn(io.pacod.cms.site.render.Regions.primaryOnly());
        TagService tagService = mock(TagService.class);
        when(tagService.getAllTags()).thenReturn(List.of());
        SiteChromeReader chrome = mock(SiteChromeReader.class);
        when(chrome.settings()).thenReturn(TestSites.settings());
        when(categories.findById(5)).thenReturn(Optional.of(new Category(5, "Новини", List.of(), "bg")));
        when(articles.findArticleByCategoriesAndStatus(eq(5), eq(ArticleRepository.PUBLIC), any(Pageable.class)))
                .thenReturn(page(List.of(article(7)), 0, 1));

        MockMvc both = MockMvcBuilders.standaloneSetup(
                        new FeedController(articles, categories, chrome, tagService),
                        new ArticleTemplateController(service, tagService, composer,
                                mock(io.pacod.cms.site.services.BannerService.class),
                                mock(io.pacod.cms.site.services.SiteMetricCollector.class),
                                new io.pacod.cms.site.services.SitePdfs(""),
                                new io.pacod.cms.site.services.PrintExtras(mock(io.pacod.cms.core.repository.PdfEnrichmentRepository.class))))
                .setCustomArgumentResolvers(new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
                .build();

        both.perform(get("/list/5/feed.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/atom+xml;charset=UTF-8"));
        both.perform(get("/list/alpha/feed.xml")).andExpect(status().isNotFound());
        both.perform(get("/de/list/5/feed.xml")).andExpect(status().isNotFound());
        both.perform(get("/list/alpha"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("list"));
        both.perform(get("/de/list/5"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("list"));
        // A list named feed.xml is a tag's list, as any other name is; it is not the feed.
        both.perform(get("/list/feed.xml"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("list"));
        verify(service).getAllArticlesByTagPageable(eq("feed.xml"), any(), any());
    }
}
