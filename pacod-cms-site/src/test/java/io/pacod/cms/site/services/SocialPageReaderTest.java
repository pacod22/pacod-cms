package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.site.data.LinkTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The page snapshot {@code /social} renders from, article covers included. Caching is the proxy's. */
class SocialPageReaderTest {

    private SocialPageRepository pages;
    private ArticleRepository articles;
    private PageRepository pages_;
    private CategoryRepository categories;
    private SocialPageReader reader;
    private SocialPage page;

    @BeforeEach
    void setUp() {
        pages = mock(SocialPageRepository.class);
        articles = mock(ArticleRepository.class);
        pages_ = mock(PageRepository.class);
        categories = mock(CategoryRepository.class);
        SiteChromeReader chrome = mock(SiteChromeReader.class);
        io.pacod.cms.core.data.SiteSettings settings = new io.pacod.cms.core.data.SiteSettings();
        settings.setBaseUrl("https://www.example.org");
        when(chrome.settings()).thenReturn(settings);
        reader = new SocialPageReader(pages, mock(ImageMediaRepository.class), articles, pages_, categories, chrome);

        page = new SocialPage();
        page.getLinks().add(link("article1", "/article/3150"));
        page.getLinks().add(link("article2", "https://www.example.org/de/article/17"));
        page.getLinks().add(link("article3", "/article/404"));
        page.getLinks().add(link("external", "https://example.com/article/3150"));
        when(pages.findById(SocialPage.ID)).thenReturn(Optional.of(page));
    }

    private static SocialLink link(String id, String url) {
        SocialLink link = new SocialLink(id);
        link.setUrl(url);
        return link;
    }

    private static Article article(int id, String filename) {
        Article article = new Article(id);
        if (filename != null) {
            ImageMedia image = new ImageMedia(id);
            image.setFilename(filename);
            article.setImages(List.of(image));
        }
        return article;
    }

    @Test
    @DisplayName("every article link's cover and titles are read with the page, in one query")
    void linkedArticlesAreReadWithThePage() {
        Article festival = article(3150, "festival.jpg");
        festival.setTitle("<b>Есенният</b> фестивал");
        festival.setTitles(List.of(
                TextItem.builder().lang("en").text("The autumn&nbsp;festival").build()));
        Article bare = article(17, null);
        bare.setTitle("Без снимка");
        when(articles.findPublishedCoversByIdIn(any())).thenReturn(List.of(festival, bare));

        SocialPageReader.Snapshot snapshot = reader.page().orElseThrow();

        assertThat(snapshot.targets()).containsOnlyKeys("article1", "article2");
        LinkTarget linked = snapshot.targets().get("article1");
        assertThat(linked.cover()).isEqualTo("/assets/img/articles/festival.jpg");
        // Plain text in every language the article is written in -- and only those.
        assertThat(linked.titles()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("bg", "Есенният фестивал", "en", "The autumn festival"));
        assertThat(snapshot.targets().get("article2").cover()).isNull();
        // 404 no longer exists: no entry. Another host's /article/ path is never looked up.
        verify(articles).findPublishedCoversByIdIn(Set.of(3150, 17, 404));
    }

    @Test
    @DisplayName("a page with no links to site content costs no query of any kind")
    void noSiteLinksNoQuery() {
        page.getLinks().clear();
        page.getLinks().add(link("external", "https://example.com/issue.pdf"));

        assertThat(reader.page().orElseThrow().targets()).isEmpty();
        verify(articles, never()).findPublishedCoversByIdIn(any());
        verify(pages_, never()).findByPublishedTrueOrderBySortOrderAscSlugAsc();
        verify(categories, never()).findAllById(any());
    }

    @Test
    @DisplayName("standing pages are matched by slug, categories by id, with their titles")
    void pagesAndCategoriesAreTargetsToo() {
        page.getLinks().clear();
        page.getLinks().add(link("subscr01", "/p/subscription"));
        page.getLinks().add(link("pageabou", "/p/about"));
        page.getLinks().add(link("listing1", "/list/5"));
        page.getLinks().add(link("nopage01", "/p/never-written"));

        Page subscription = new Page("subscription");
        subscription.putTitle("bg", "Абонамент");
        subscription.putTitle("en", "Subscription");
        Page about = new Page("about");
        about.putTitle("bg", "За нас");
        about.putTitle("de", "Über uns");
        when(pages_.findByPublishedTrueOrderBySortOrderAscSlugAsc()).thenReturn(List.of(subscription, about));

        io.pacod.cms.core.data.Category club = new io.pacod.cms.core.data.Category(5);
        club.setName("Градски клуб");
        club.setNames(List.of(TextItem.builder().lang("en").text("Town Club").build()));
        when(categories.findAllById(any())).thenReturn(List.of(club));

        java.util.Map<String, LinkTarget> targets = reader.page().orElseThrow().targets();

        assertThat(targets.get("subscr01").titles()).isEqualTo(java.util.Map.of("bg", "Абонамент", "en", "Subscription"));
        assertThat(targets.get("pageabou").titles()).isEqualTo(java.util.Map.of("bg", "За нас", "de", "Über uns"));
        // The category's Bulgarian name is the scalar field -- what /list/5 heads itself with.
        assertThat(targets.get("listing1").titles())
                .isEqualTo(java.util.Map.of("bg", "Градски клуб", "en", "Town Club"));
        assertThat(targets.get("subscr01").cover()).isNull();
        assertThat(targets).doesNotContainKey("nopage01");
        verify(articles, never()).findPublishedCoversByIdIn(any());
    }

    @Test
    @DisplayName("one kind failing to read leaves the others translated, and the page renders")
    void oneKindFailingLeavesTheOthers() {
        page.getLinks().add(link("subscr01", "/p/subscription"));
        when(articles.findPublishedCoversByIdIn(any())).thenReturn(List.of(article(3150, "festival.jpg")));
        when(pages_.findByPublishedTrueOrderBySortOrderAscSlugAsc())
                .thenThrow(new DataAccessResourceFailureException("down"));

        java.util.Map<String, LinkTarget> targets = reader.page().orElseThrow().targets();

        assertThat(targets).containsKey("article1").doesNotContainKey("subscr01");
    }

    @Test
    @DisplayName("covers that cannot be read leave the page rendering without them, never a 404")
    void coverFailureIsContained() {
        when(articles.findPublishedCoversByIdIn(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(reader.page()).isPresent().get()
                .satisfies(snapshot -> assertThat(snapshot.targets()).isEmpty());
    }
}
