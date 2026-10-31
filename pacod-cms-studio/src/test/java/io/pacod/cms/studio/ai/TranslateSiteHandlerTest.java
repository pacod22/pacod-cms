package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.repository.TagRepository;
import io.pacod.cms.core.services.TranslationWriter;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.studio.jobs.JobContext;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OriginalLanguageExtension makes bg the original; TestTenantExtension binds the website test. */
class TranslateSiteHandlerTest {

    private final AiGateway gateway = mock(AiGateway.class);
    private final AiAccess access = mock(AiAccess.class);
    private final PageRepository pages = mock(PageRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TagRepository tags = mock(TagRepository.class);
    private final MenuRepository menus = mock(MenuRepository.class);
    private final TranslationWriter writer = mock(TranslationWriter.class);
    private final ObjectMapper json = new ObjectMapper();
    private TranslateSiteHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TranslateSiteHandler(gateway, access, pages, categories, tags, menus, writer, io.pacod.cms.studio.ai.AiModels.fixed("@fast"));
        when(access.refusalFor(anyString())).thenReturn(Optional.empty());
        when(writer.putPage(anyString(), anyString(), any(), any(), any())).thenReturn(true);
        when(writer.putCategory(anyInt(), anyString(), any())).thenReturn(true);
        when(writer.putTag(anyString(), anyString(), any())).thenReturn(true);
        when(writer.replaceIfUnchanged(any(), any(), anyMap(), anyMap())).thenReturn(true);
        // Echoes the user's message back as the translation, as pacod-gw's mock does.
        when(gateway.complete(any())).thenAnswer(i -> {
            AiGateway.Call call = i.getArgument(0);
            return new AiGateway.Answer(json.readTree(json.writeValueAsString(Map.of("text", "[de] " + call.user()))), 10, "gw");
        });
    }

    private JobContext context(Map<String, String> params) {
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("test");
        job.setType(JobType.TRANSLATE_SITE);
        job.setParams(new LinkedHashMap<>(params));
        return new JobContext(job, mock(SiteLiveness.class));
    }

    private static Page page(String slug, String lang, String title, String body) {
        Page page = new Page(slug);
        page.getTitles().add(new TextItem(title, lang));
        page.getBodies().add(new TextItem(body, lang));
        return page;
    }

    private static Document label(String lang, String text) {
        return new Document("text", text).append("lang", lang);
    }

    @Test
    @DisplayName("parts are an allow-list, in their own order")
    void partsAllowList() {
        assertThat(TranslateSiteHandler.parts("social, pages,../x tags")).containsExactly("pages", "tags", "social");
        assertThat(TranslateSiteHandler.parts(null)).isEmpty();
    }

    @Test
    @DisplayName("a page is translated from the original; one without an original body is skipped, never translated from a fallback")
    void pages() {
        Page about = page("about", "bg", "За нас", "<p>Кои сме <b>ние</b></p>");
        Page english = page("contact", "en", "Contact", "<p>Write to us</p>");
        Page done = page("terms", "bg", "Условия", "<p>Текст</p>");
        done.getBodies().add(new TextItem("<p>Text</p>", "de"));
        when(pages.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(about, english, done));

        JobContext context = context(Map.of("parts", "pages", "languages", "de"));
        handler.run(context);

        verify(writer).putPage("about", "de", "[de] За нас", null, "[de] <p>Кои сме <b>ние</b></p>");
        verify(writer, never()).putPage(eq("contact"), anyString(), any(), any(), any());
        verify(writer, never()).putPage(eq("terms"), anyString(), any(), any(), any());
        assertThat(context.progress().log()).contains("page contact: no body in bg; skipped.", "page terms → de: already translated");
        assertThat(context.progress().done()).isEqualTo(3);
        assertThat(context.failed()).isZero();
    }

    @Test
    @DisplayName("a category is translated from its own language; tags only when asked for")
    void categoriesAndTags() {
        Category news = new Category(2, "Новини", new ArrayList<>(), "bg");
        Category german = new Category(3, "Hintergrund", new ArrayList<>(), "de");
        when(categories.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(news, german));
        when(tags.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(new Tag("пролет", null)));

        handler.run(context(Map.of("parts", "categories", "languages", "de")));
        verify(writer).putCategory(2, "de", "[de] Новини");
        verify(writer, never()).putCategory(eq(3), anyString(), any());
        verify(writer, never()).putTag(anyString(), anyString(), any());

        handler.run(context(Map.of("parts", "tags", "languages", "de")));
        verify(writer).putTag("пролет", "de", "[de] пролет");
    }

    @Test
    @DisplayName("a menu is rebuilt from what was read and written by compare-and-set; English stands in for a missing original")
    void menus() {
        Menu main = new Menu();
        main.setKey("main");
        when(menus.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(main));
        List<Object> items = List.of(
                new Document("labels", List.of(label("bg", "Начало"))).append("kind", "HOME"),
                new Document("labels", List.of(label("en", "Sections"), label("de", "Rubriken")))
                        .append("kind", "NONE")
                        .append("children", List.of(new Document("labels", List.of(label("en", "News"))).append("kind", "CATEGORY"))));
        Document raw = new Document("_id", "main").append("items", items);
        when(writer.read(Menu.class, "main")).thenReturn(Optional.of(raw));

        JobContext context = context(Map.of("parts", "menus", "languages", "de"));
        handler.run(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(writer).replaceIfUnchanged(eq(Menu.class), eq("main"), before.capture(), after.capture());
        // The very list read, not a copy.
        assertThat(before.getValue().get("items")).isSameAs(items);
        @SuppressWarnings("unchecked")
        List<Document> rebuilt = (List<Document>) after.getValue().get("items");
        assertThat(rebuilt.get(0).getList("labels", Document.class)).extracting(d -> d.getString("text"))
                .containsExactly("Начало", "[de] Начало");
        // "Rubriken" was there: kept, not translated again.
        assertThat(rebuilt.get(1).getList("labels", Document.class)).extracting(d -> d.getString("text"))
                .containsExactly("Sections", "Rubriken");
        Document child = rebuilt.get(1).getList("children", Document.class).get(0);
        assertThat(child.getList("labels", Document.class)).extracting(d -> d.getString("text"))
                .containsExactly("News", "[de] News");
        assertThat(child.getString("kind")).isEqualTo("CATEGORY");
        assertThat(rebuilt.get(1).getList("children", Document.class)).hasSize(1);
        // What was read is untouched.
        assertThat(((Document) items.get(0)).getList("labels", Document.class)).hasSize(1);
        verify(gateway, times(2)).complete(any());
        assertThat(context.failed()).isZero();
    }

    @Test
    @DisplayName("a menu edited meanwhile is not overwritten: that language fails, saying run it again")
    void menuChangedMeanwhile() {
        Menu main = new Menu();
        main.setKey("main");
        when(menus.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(main));
        when(writer.read(Menu.class, "main")).thenReturn(Optional.of(new Document("_id", "main")
                .append("items", List.of(new Document("labels", List.of(label("bg", "Начало")))))));
        when(writer.replaceIfUnchanged(any(), any(), anyMap(), anyMap())).thenReturn(false);

        JobContext context = context(Map.of("parts", "menus", "languages", "de"));
        handler.run(context);
        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("menu main → de: changed or removed meanwhile; run it again.");
    }

    @Test
    @DisplayName("the social page's texts and link titles go in one compare-and-set; dates and ids go back as read")
    void socialPage() {
        java.util.Date created = new java.util.Date(0);
        Document link = new Document("_id", "k3x9a2bq").append("titles", List.of(label("bg", "Абонирай се")))
                .append("url", "https://example.com").append("createdOn", created);
        Document raw = new Document("_id", SocialPage.ID).append("names", List.of(label("bg", "Демо")))
                .append("links", List.of(link));
        when(writer.read(SocialPage.class, SocialPage.ID)).thenReturn(Optional.of(raw));

        handler.run(context(Map.of("parts", "social", "languages", "de")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(writer).replaceIfUnchanged(eq(SocialPage.class), eq(SocialPage.ID), anyMap(), after.capture());
        assertThat((List<?>) after.getValue().get("names")).hasSize(2);
        // Absent stays absent: no "bios: null" where the document had no key.
        assertThat(after.getValue()).containsOnlyKeys("names", "links");
        @SuppressWarnings("unchecked")
        Document written = ((List<Document>) after.getValue().get("links")).get(0);
        assertThat(written.getString("_id")).isEqualTo("k3x9a2bq");
        assertThat(written.get("createdOn")).isSameAs(created);
        assertThat(written.getList("titles", Document.class)).extracting(d -> d.getString("text"))
                .containsExactly("Абонирай се", "[de] Абонирай се");
    }

    @Test
    @DisplayName("a run stops at its call limit and says how to go on")
    void callLimit() {
        List<Tag> many = IntStream.rangeClosed(1, TranslateSiteHandler.MAX_CALLS + 5)
                .mapToObj(i -> new Tag("t" + i, null)).toList();
        when(tags.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(many);

        JobContext context = context(Map.of("parts", "tags", "languages", "de"));
        handler.run(context);
        verify(gateway, times(TranslateSiteHandler.MAX_CALLS)).complete(any());
        assertThat(context.progress().log()).contains("Stopped after " + TranslateSiteHandler.MAX_CALLS, "Queue it again");
        assertThat(context.failed()).isZero();
    }

    @Test
    @DisplayName("a gateway failure fails that item; the run goes on")
    void gatewayFailure() {
        when(categories.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(new Category(2, "Новини", null, "bg"), new Category(4, "Спорт", null, null)));
        // doThrow: re-stubbing with when() would run setUp's answer with a null call.
        org.mockito.Mockito.doThrow(new AiGateway.AiException("The AI budget is used up")).when(gateway).complete(any());
        JobContext context = context(Map.of("parts", "categories", "languages", "de"));
        handler.run(context);
        assertThat(context.failed()).isEqualTo(2);
        assertThat(context.progress().log()).contains("category 2 → de: The AI budget is used up", "category 4 → de:");
    }
}
