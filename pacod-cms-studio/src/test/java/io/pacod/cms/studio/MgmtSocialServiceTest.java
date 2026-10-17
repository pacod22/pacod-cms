package io.pacod.cms.studio;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.repository.SocialPageRepository;
import io.pacod.cms.studio.form.SocialLinkForm;
import io.pacod.cms.studio.form.SocialPageForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MgmtSocialServiceTest {

    private SocialPageRepository repository;
    private ImageMediaRepository images;
    private ArticleRepository articles;
    private MgmtSocialService service;
    private io.pacod.cms.core.repository.SiteSettingsRepository siteSettings;
    private SocialPage stored;

    @BeforeEach
    void setUp() {
        repository = mock(SocialPageRepository.class);
        images = mock(ImageMediaRepository.class);
        articles = mock(ArticleRepository.class);
        siteSettings = mock(io.pacod.cms.core.repository.SiteSettingsRepository.class);
        service = new MgmtSocialService(repository, images, articles, siteSettings, new Random(11));
        // The website the page belongs to: its host, its name and the profiles it links from the
        // footer, which a page never saved starts out with.
        io.pacod.cms.core.data.SiteSettings site = new io.pacod.cms.core.data.SiteSettings();
        site.setBaseUrl("https://www.example.org");
        site.setNames(new java.util.ArrayList<>(io.pacod.cms.core.data.TextItems.put(List.of(), "bg", "Примерен вестник")));
        site.setTaglines(new java.util.ArrayList<>(io.pacod.cms.core.data.TextItems.put(List.of(), "bg", "Новини и ръководства")));
        site.setSocialProfiles(new java.util.ArrayList<>(List.of(
                new io.pacod.cms.core.data.SiteSettings.SocialProfile("Facebook", "https://www.facebook.com/example/"),
                new io.pacod.cms.core.data.SiteSettings.SocialProfile("YouTube", "https://www.youtube.com/@example"),
                new io.pacod.cms.core.data.SiteSettings.SocialProfile("Mastodon", "https://mastodon.example/@x"),
                new io.pacod.cms.core.data.SiteSettings.SocialProfile("Instagram", "https://www.instagram.com/example/"))));
        when(siteSettings.findById(io.pacod.cms.core.data.SiteSettings.ID)).thenReturn(Optional.of(site));

        stored = new SocialPage();
        stored.putName("bg", "Примерен вестник");
        stored.getLinks().add(link("icon0001", SocialLinkPlacement.ICON));
        stored.getLinks().add(link("button01", SocialLinkPlacement.BUTTON));
        stored.getLinks().add(link("icon0002", SocialLinkPlacement.ICON));
        stored.getLinks().add(link("button02", SocialLinkPlacement.BUTTON));
        when(repository.findById(SocialPage.ID)).thenAnswer(i -> Optional.of(stored));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static SocialLink link(String id, SocialLinkPlacement placement) {
        SocialLink link = new SocialLink(id);
        link.setPlacement(placement);
        link.setIcon(SocialIcon.WEB);
        link.putTitle("bg", "Връзка " + id);
        link.setUrl("https://example.com/" + id);
        return link;
    }

    private static SocialLinkForm form(String lang, SocialLinkPlacement placement, String title, String url) {
        SocialLinkForm form = new SocialLinkForm();
        form.setLang(lang);
        form.setPlacement(placement);
        form.setTitle(title);
        form.setUrl(url);
        return form;
    }

    @Test
    @DisplayName("a page never saved loads as an unpublished default with the website's profiles")
    void defaultIsUnpublished() {
        when(repository.findById(SocialPage.ID)).thenReturn(Optional.empty());

        SocialPage page = service.load();

        assertThat(page.isPublished()).isFalse();
        // One icon per profile whose network names an icon; a network with no icon is left out.
        assertThat(page.linksOf(SocialLinkPlacement.ICON)).extracting(SocialLink::getIcon)
                .containsExactly(SocialIcon.FACEBOOK, SocialIcon.YOUTUBE, SocialIcon.INSTAGRAM);
        assertThat(page.rawName("bg")).isEqualTo("Примерен вестник");
        // Fixed ids: the default is rebuilt on every request until the first save, and a random id
        // on the screen would be gone by the time ↑ was pressed on its row.
        assertThat(service.load().getLinks()).extracting(SocialLink::getId)
                .isEqualTo(page.getLinks().stream().map(SocialLink::getId).toList());
        assertThat(page.getLinks()).allSatisfy(l -> assertThat(SocialPage.isLinkId(l.getId())).isTrue());
    }

    @Test
    @DisplayName("a new link gets a generated id, never one from the form")
    void createdLinksGetGeneratedIds() {
        SocialLink created = service.createLink(form("bg", SocialLinkPlacement.BUTTON, "Абонамент", "/p/subscription"));

        assertThat(SocialPage.isLinkId(created.getId())).isTrue();
        assertThat(stored.getLinks()).extracting(SocialLink::getId).doesNotHaveDuplicates().contains(created.getId());
        assertThat(created.rawTitle("bg")).isEqualTo("Абонамент");
        verify(repository).save(stored);
    }

    @Test
    @DisplayName("saving one link in German writes that title and the shared fields, nothing else")
    void savingALanguageWritesOnlyThatLanguage() {
        service.saveLink("button01", form("de", SocialLinkPlacement.BUTTON, "Abonnement", "https://example.com/neu"));

        SocialLink saved = stored.link("button01").orElseThrow();
        assertThat(saved.rawTitle("de")).isEqualTo("Abonnement");
        assertThat(saved.rawTitle("bg")).isEqualTo("Връзка button01");
        assertThat(saved.getUrl()).isEqualTo("https://example.com/neu");
        assertThat(stored.link("button02").orElseThrow().getUrl()).isEqualTo("https://example.com/button02");
    }

    @Test
    @DisplayName("blank in a translation removes that translation, never stores an empty string")
    void blankTranslationIsRemoved() {
        stored.link("button01").orElseThrow().putTitle("de", "Abonnement");
        service.saveLink("button01", form("de", SocialLinkPlacement.BUTTON, "  ", "https://example.com/x"));

        assertThat(stored.link("button01").orElseThrow().getTitles()).extracting("lang").containsExactly("bg");
    }

    @Test
    @DisplayName("a button needs a Bulgarian title; an icon needs an icon")
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> service.createLink(form("bg", SocialLinkPlacement.BUTTON, " ", "https://example.com/")))
                .isInstanceOf(MgmtOperationException.class).hasMessageContaining("original language");

        SocialLinkForm icon = form("bg", SocialLinkPlacement.ICON, null, "https://example.com/");
        assertThatThrownBy(() -> service.createLink(icon))
                .isInstanceOf(MgmtOperationException.class).hasMessageContaining("needs an icon");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a German save is not refused for a Bulgarian title it never showed")
    void foreignSaveKeepsTheBulgarianTitle() {
        service.saveLink("button02", form("en", SocialLinkPlacement.BUTTON, null, "https://example.com/x"));

        assertThat(stored.link("button02").orElseThrow().rawTitle("bg")).isEqualTo("Връзка button02");
    }

    @Test
    @DisplayName("an address that could redirect off-site or run script is refused")
    void hostileTargetsAreRefused() {
        for (String url : List.of("javascript:alert(1)", "//evil.example/", "/\\evil.example", "mailto:x@y.z")) {
            assertThatThrownBy(() -> service.saveLink("button01", form("bg", SocialLinkPlacement.BUTTON, "X", url)))
                    .as(url).isInstanceOf(MgmtOperationException.class);
        }
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a schedule that ends before it starts is refused")
    void backwardsScheduleIsRefused() {
        SocialLinkForm form = form("bg", SocialLinkPlacement.BUTTON, "X", "https://example.com/");
        form.setActiveFrom(java.time.LocalDateTime.of(2026, 10, 12, 9, 0));
        form.setActiveTo(java.time.LocalDateTime.of(2026, 10, 11, 9, 0));

        assertThatThrownBy(() -> service.saveLink("button01", form)).isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("moving swaps with the nearest link of the same placement, and stops at the ends")
    void movesStayWithinThePlacement() {
        service.moveLink("button02", -1);
        assertThat(stored.getLinks()).extracting(SocialLink::getId)
                .containsExactly("icon0001", "button02", "icon0002", "button01");

        service.moveLink("icon0001", 1);
        assertThat(stored.getLinks()).extracting(SocialLink::getId)
                .containsExactly("icon0002", "button02", "icon0001", "button01");

        // Already last of its placement: a no-op, not an error.
        service.moveLink("button01", 1);
        assertThat(stored.getLinks()).extracting(SocialLink::getId)
                .containsExactly("icon0002", "button02", "icon0001", "button01");
    }

    @Test
    @DisplayName("toggle flips the switch; delete removes only that link")
    void toggleAndDelete() {
        assertThat(service.toggleLink("button01")).isFalse();
        assertThat(stored.link("button01").orElseThrow().isActive()).isFalse();

        service.deleteLink("icon0002");
        assertThat(stored.getLinks()).extracting(SocialLink::getId).containsExactly("icon0001", "button01", "button02");
    }

    @Test
    @DisplayName("an unknown link id is a 404")
    void unknownLinkIs404() {
        assertThatThrownBy(() -> service.toggleLink("nosuchid")).isInstanceOf(MgmtNotFoundException.class);
    }

    @Test
    @DisplayName("the profile save writes one language's texts and the shared settings, never a link")
    void profileSaveLeavesLinksAlone() {
        SocialPageForm form = new SocialPageForm();
        form.setLang("en");
        form.setName("Example Gazette");
        form.setBio("A magazine.");
        form.setPublished(true);
        form.setShowLatestArticles(true);
        form.setLatestArticlesCount(4);

        service.saveProfile(form);

        assertThat(stored.rawName("en")).isEqualTo("Example Gazette");
        assertThat(stored.rawName("bg")).isEqualTo("Примерен вестник");
        assertThat(stored.isPublished()).isTrue();
        assertThat(stored.latestCount()).isEqualTo(4);
        assertThat(stored.getLinks()).hasSize(4);
    }

    private static Article article(int id, String title, Integer... imageIds) {
        Article article = new Article(id);
        article.setTitle(title);
        List<ImageMedia> pictures = new java.util.ArrayList<>();
        for (Integer imageId : imageIds) {
            ImageMedia image = new ImageMedia(imageId);
            image.setFilename("image-" + imageId + ".jpg");
            pictures.add(image);
        }
        article.setImages(pictures);
        return article;
    }

    @Test
    @DisplayName("a link to an article shows that article's cover: the first visible image")
    void articleLinksGetTheirCover() {
        stored.link("button01").orElseThrow().setUrl("/article/3150");
        stored.link("button02").orElseThrow().setUrl("https://www.example.org/de/article/17?lang=de");
        stored.link("icon0001").orElseThrow().setUrl("/article/404");
        Article withHiddenFirst = article(3150, "<b>Есенният</b> фестивал", 5, 6);
        withHiddenFirst.setHiddenImageIds(List.of(5));
        when(articles.findCoversByIdIn(any())).thenReturn(List.of(withHiddenFirst, article(17, "Без снимка")));

        Map<String, MgmtSocialService.ArticleThumb> thumbs = service.articleThumbs(stored.getLinks());

        // Hidden from the public site, so not the cover here either -- ArticleModel.getImage()'s rule.
        assertThat(thumbs.get("button01")).isEqualTo(
                new MgmtSocialService.ArticleThumb(3150, "Есенният фестивал", "/assets/img/articles/image-6.jpg"));
        assertThat(thumbs.get("button02").imagePath()).isNull();
        // An article since deleted, and a link that is no article, have no thumbnail at all.
        assertThat(thumbs).doesNotContainKeys("icon0001", "icon0002");
        verify(articles).findCoversByIdIn(java.util.Set.of(3150, 17, 404));
    }

    @Test
    @DisplayName("no article links, no query; a failed read is a list without pictures")
    void articleThumbsDegrade() {
        assertThat(service.articleThumbs(stored.getLinks())).isEmpty();
        verify(articles, never()).findCoversByIdIn(any());

        stored.link("button01").orElseThrow().setUrl("/article/3150");
        when(articles.findCoversByIdIn(any())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(service.articleThumbs(stored.getLinks())).isEmpty();
    }

    @Test
    @DisplayName("an article link left without text takes the article's title; typed text is kept")
    void articleLinkTakesTheArticleTitle() {
        when(articles.findTitlesByIdIn(List.of(3150))).thenReturn(List.of(article(3150, "Есенният&nbsp;<i>фестивал</i>")));

        SocialLink created = service.createLink(form("bg", SocialLinkPlacement.BUTTON, " ", "/article/3150"));
        assertThat(created.rawTitle("bg")).isEqualTo("Есенният фестивал");

        SocialLink typed = service.createLink(form("bg", SocialLinkPlacement.BUTTON, "Прочетете", "/article/3150"));
        assertThat(typed.rawTitle("bg")).isEqualTo("Прочетете");
    }

    @Test
    @DisplayName("a link to an article that does not exist is refused")
    void missingArticleIsRefused() {
        when(articles.findTitlesByIdIn(List.of(999999))).thenReturn(List.of());

        assertThatThrownBy(() -> service.createLink(form("bg", SocialLinkPlacement.BUTTON, "X", "/article/999999")))
                .isInstanceOf(MgmtOperationException.class).hasMessageContaining("999999");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the Bulgarian name is required, and an avatar must exist in the library")
    void profileValidation() {
        SocialPageForm blank = new SocialPageForm();
        blank.setLang("bg");
        assertThatThrownBy(() -> service.saveProfile(blank)).isInstanceOf(MgmtOperationException.class);

        when(images.existsById(anyInt())).thenReturn(false);
        SocialPageForm avatar = new SocialPageForm();
        avatar.setName("Примерен вестник");
        avatar.setAvatarImageId(999);
        assertThatThrownBy(() -> service.saveProfile(avatar)).isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("999");
        verify(repository, never()).save(any());
    }
}
