package io.pacod.cms.site.render;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.config.ThymeleafConfig;
import io.pacod.cms.site.data.LandingSlot;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.site.services.CategoryWidgetReader;
import io.pacod.cms.site.services.LanguageReader;
import io.pacod.cms.site.services.PageReader;
import io.pacod.cms.site.services.PageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a page's regions hold: the website's published layout or its set's default, resolved
 * against the set, each section's data looked up -- and one failing lookup costing one section.
 */
class PageComposerTest {

    private static final TemplateSetCatalog CATALOG = new TemplateSetCatalog();

    private LayoutReader layouts;
    private CategoryWidgetReader widgets;
    private PageReader pages;
    private PageComposer composer;

    @BeforeEach
    void setUp() {
        layouts = mock(LayoutReader.class);
        widgets = mock(CategoryWidgetReader.class);
        when(layouts.published(org.mockito.ArgumentMatchers.any())).thenReturn(new LayoutReader.Stored(null));
        when(widgets.readLatest(anyInt(), anyString())).thenAnswer(i -> slot(null, "", i.getArgument(0)));
        when(widgets.read(anyInt(), anyInt(), anyString())).thenAnswer(i -> slot(i.getArgument(0), "Category", i.getArgument(1)));
        pages = mock(PageReader.class);
        when(pages.findAll()).thenReturn(List.of());
        composer = new PageComposer(CATALOG, layouts, widgets, new PageService(pages, mock(LanguageReader.class)),
                new ThymeleafConfig().messageSource());
    }

    private static LandingSlot slot(Integer category, String name, int count) {
        List<ListItem> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Article a = new Article(i);
            a.setTitle("Article " + i);
            items.add(new ListItem(a, "en"));
        }
        return new LandingSlot(category, name, items);
    }

    private static Regions compose(PageComposer composer, String set, PageType type) {
        Tenant tenant = new Tenant("alpha", "org_a", "site_alpha", "en", ZoneId.of("UTC"), set);
        return TenantContext.callAs(tenant, () -> composer.compose(type, "en"));
    }

    private static List<Integer> ids(RenderedSection section) {
        return ((LandingSlot) section.data()).items().stream().map(ListItem::getId).toList();
    }

    @Test
    @DisplayName("a fresh website's home page is its set's default: a lead story, the next six, the most read")
    void freshHomeIsTheDefault() {
        Regions regions = compose(composer, "editorial", PageType.HOME);

        List<RenderedSection> main = regions.get("main");
        assertThat(main).extracting(s -> s.setting("style")).containsExactly("hero", "cards");
        assertThat(ids(main.get(0))).containsExactly(1);
        // One read of seven, sliced: the cards start where the lead story ends.
        assertThat(ids(main.get(1))).containsExactly(2, 3, 4, 5, 6, 7);
        RenderedSection mostRead = regions.get("aside").getFirst();
        assertThat(((LandingSlot) mostRead.data()).name()).isEqualTo("Most read");
        assertThat(regions.get("wide")).isEmpty();
        // No category is named anywhere: a new website has none.
        verify(widgets, never()).read(eq(2), anyInt(), anyString());
    }

    @Test
    @DisplayName("a page with content of its own renders it, and only it, by default")
    void contentPagesDefaultToTheirContent() {
        for (PageType type : List.of(PageType.ARTICLE, PageType.CATEGORY, PageType.PAGE, PageType.LATEST, PageType.SEARCH)) {
            Regions regions = compose(composer, "editorial", type);
            assertThat(regions.get("main")).extracting(RenderedSection::isPrimary).containsExactly(true);
        }
    }

    @Test
    @DisplayName("the published layout replaces the default; a category list reads that category, sliced")
    void publishedLayoutIsUsed() {
        when(layouts.published(PageType.HOME)).thenReturn(new LayoutReader.Stored(Map.of(
                "main", List.of(new Section("a", "article-list",
                        Map.of("source", "category", "category", 5, "count", 2, "offset", 1, "heading", "Travel"))))));

        Regions regions = compose(composer, "editorial", PageType.HOME);

        RenderedSection list = regions.get("main").getFirst();
        assertThat(ids(list)).containsExactly(2, 3);
        assertThat(((LandingSlot) list.data()).name()).isEqualTo("Travel");
        verify(widgets).read(5, 3, "en");
        assertThat(regions.get("aside")).isEmpty();
    }

    @Test
    @DisplayName("one failing lookup costs one section, never the page")
    void failingSectionIsLeftOut() {
        when(widgets.read(eq(PageComposer.MOST_READ), anyInt(), anyString())).thenThrow(new IllegalStateException("down"));

        Regions regions = compose(composer, "editorial", PageType.HOME);

        assertThat(regions.get("main")).hasSize(2);
        assertThat(regions.get("aside")).isEmpty();
    }

    @Test
    @DisplayName("a section the website's set cannot render is left out, not passed to a template to fail")
    void unsupportedSectionIsLeftOut() {
        when(layouts.published(PageType.ARTICLE)).thenReturn(new LayoutReader.Stored(Map.of(
                "main", List.of(new Section("p", "primary", Map.of()), Section.of(SectionType.RICH_TEXT,
                        Map.of("html", "<p>x</p>"))))));

        Regions probe = compose(composer, "probe", PageType.ARTICLE);
        Regions editorial = compose(composer, "editorial", PageType.ARTICLE);

        assertThat(probe.get("main")).extracting(RenderedSection::type).containsExactly("primary");
        assertThat(editorial.get("main")).extracting(RenderedSection::type).containsExactly("primary", "rich-text");
    }

    @Test
    @DisplayName("a website on a set this installation lacks composes with the default set")
    void unknownSetUsesTheDefault() {
        assertThat(compose(composer, "vanished", PageType.HOME).get("main")).hasSize(2);
    }

    @Test
    @DisplayName("a page section is its page's body in the language the page itself would serve; nothing of a page with nothing to show")
    void pageSection() {
        io.pacod.cms.core.data.Page platform = new io.pacod.cms.core.data.Page("platform");
        platform.putTitle("en", "The platform");
        platform.putBody("en", "<p>One platform.</p>");
        platform.putBody("de", "<p>Eine Plattform.</p>");
        platform.putBody("bg", "<p>Една платформа.</p>");
        io.pacod.cms.core.data.Page titled = new io.pacod.cms.core.data.Page("titled");
        titled.putTitle("en", "A title alone");
        // The reader's list holds the published pages alone: an unpublished one is not in it.
        when(pages.findAll()).thenReturn(List.of(platform, titled));
        when(layouts.published(PageType.HOME)).thenReturn(new LayoutReader.Stored(Map.of(
                "wide", List.of(new Section("p", "page", Map.of("page", "platform"))),
                "aside", List.of(new Section("t", "page", Map.of("page", "titled")),
                        new Section("u", "page", Map.of("page", "unpublished"))))));

        for (String[] asked : new String[][]{{"de", "de", "<p>Eine Plattform.</p>"}, {"fr", "en", "<p>One platform.</p>"},
                {null, "bg", "<p>Една платформа.</p>"}}) {
            // A Bulgarian website: the chain is the language asked for, English, then Bulgarian.
            Tenant tenant = new Tenant("alpha", "org_a", "site_alpha", "bg", ZoneId.of("UTC"), "magazine");
            Regions regions = TenantContext.callAs(tenant, () -> composer.compose(PageType.HOME, asked[0]));
            RenderedSection section = regions.get("wide").getFirst();
            assertThat(section.type()).isEqualTo("page");
            assertThat(section.fragment()).isEqualTo("standingPage");
            PageService.Rendered page = (PageService.Rendered) section.data();
            assertThat(page.language()).as(asked[0]).isEqualTo(asked[1]);
            assertThat(page.body()).as(asked[0]).isEqualTo(asked[2]);
            // No body, or no page: no section -- so the region is empty, and no band is drawn round it.
            assertThat(regions.isEmpty("aside")).isTrue();
        }
        verify(pages, atLeastOnce()).findAll();
    }

    @Test
    @DisplayName("regions handed to a template cannot be changed by it")
    void regionsAreImmutable() {
        Regions regions = compose(composer, "editorial", PageType.HOME);
        assertThatThrownBy(() -> regions.get("main").clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((LandingSlot) regions.get("main").getFirst().data()).items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
