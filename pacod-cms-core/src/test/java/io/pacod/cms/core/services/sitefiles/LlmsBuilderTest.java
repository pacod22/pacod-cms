package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.JournalIssueRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The suites run with Bulgarian as the original (OriginalLanguageExtension), hence the fixtures. */
class LlmsBuilderTest {

    private static final String ORIGIN = "https://demo.example.com";

    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final PageRepository pages = mock(PageRepository.class);
    private final JournalIssueRepository issues = mock(JournalIssueRepository.class);
    private final SiteSettingsRepository settings = mock(SiteSettingsRepository.class);
    private final LlmsBuilder builder = new LlmsBuilder(articles, categories, pages, issues, settings);

    @BeforeEach
    void content() {
        SiteSettings site = new SiteSettings();
        site.setNames(new ArrayList<>(List.of(new TextItem("Демо вестник", "bg"))));
        site.setTaglines(new ArrayList<>(List.of(new TextItem("Новини всеки ден", "bg"))));
        when(settings.findById(SiteSettings.ID)).thenReturn(Optional.of(site));

        Page about = new Page("about");
        about.putTitle("bg", "За нас");
        about.putDescription("bg", "Кои сме ние");
        about.putBody("bg", "<p>Ние сме <strong>редакция</strong>.</p>");
        Page untitled = new Page("blank");
        when(pages.findByPublishedTrueOrderBySortOrderAscSlugAsc()).thenReturn(List.of(about, untitled));

        Category news = new Category();
        news.setId(3);
        news.setName("Новини");
        when(categories.findAll(any(Sort.class))).thenReturn(List.of(news));
        when(articles.existsByCategoriesAndStatus(3, ArticleRepository.PUBLIC)).thenReturn(true);

        JournalIssue issue = new JournalIssue();
        issue.setId(2);
        issue.setVolume(1);
        issue.setNumber(4);
        issue.setYear(2026);
        when(issues.findPublished(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(issue)));

        Article article = new Article(9);
        article.setTitle("Първа статия");
        article.setDescription("Кратко описание");
        article.setText("<h2>Начало</h2><p>Текст.</p><script>x()</script>");
        article.setDate(LocalDateTime.of(2026, 7, 3, 8, 0));
        when(articles.findPublishedIndexForLlms(any(Pageable.class))).thenReturn(List.of(article));
        when(articles.findPublishedForLlms(any(Pageable.class))).thenReturn(List.of(article));
    }

    @Test
    void theIndexFollowsLlmsTxt() {
        assertThat(builder.index(ORIGIN, new SiteFiles())).isEqualTo("""
                # Демо вестник

                > Новини всеки ден

                ## Pages

                - [За нас](https://demo.example.com/p/about): Кои сме ние

                ## Sections

                - [Новини](https://demo.example.com/list/3)

                ## Issues

                - [Vol. 1, No. 4 (2026)](https://demo.example.com/issues/2)

                ## Articles

                - [Първа статия](https://demo.example.com/article/9): Кратко описание

                ## Optional

                - [Full text](https://demo.example.com/llms-full.txt): the standing pages and the newest articles in full, as Markdown
                """);
    }

    @Test
    void noFullTextLinkWhileItIsOff() {
        SiteFiles files = new SiteFiles();
        files.getLlmsFull().setMode(SiteFileMode.OFF);
        assertThat(builder.index(ORIGIN, files)).doesNotContain("llms-full.txt", "## Optional");
    }

    @Test
    void additionsAndExclusions() {
        SiteFiles files = new SiteFiles();
        files.getLlms().setMode(SiteFileMode.AUTO_PLUS);
        files.getLlms().setExclusions("/issues/*\n/p/about");
        files.getLlms().setAdditions("## Контакт\n\n- [Пишете ни](mailto:a@example.com)");
        String index = builder.index(ORIGIN, files);
        assertThat(index).doesNotContain("## Issues", "## Pages", "/p/about");
        assertThat(index).endsWith("## Контакт\n\n- [Пишете ни](mailto:a@example.com)\n");
    }

    @Test
    void theFullTextCarriesBodiesAsMarkdown() {
        String full = builder.full(ORIGIN, new SiteFiles());
        assertThat(full).startsWith("# Демо вестник\n\n> Новини всеки ден\n\n## Pages\n\n### За нас\n\n");
        assertThat(full).contains("Source: https://demo.example.com/p/about\n\nКои сме ние\n\nНие сме **редакция**.",
                "### Първа статия\n\nSource: https://demo.example.com/article/9\nPublished: 2026-07-03\n\n"
                        + "Кратко описание\n\n## Начало\n\nТекст.");
        assertThat(full).doesNotContain("x()", "This file is cut");
    }

    @Test
    void theFullTextIsCutAtItsCeilingAndSaysSo() {
        List<Article> many = new ArrayList<>();
        String body = "<p>" + "дълъг текст ".repeat(20_000) + "</p>";
        for (int i = 30; i > 0; i--) {
            Article article = new Article(i);
            article.setTitle("Статия " + i);
            article.setText(body);
            many.add(article);
        }
        when(articles.findPublishedForLlms(any(Pageable.class))).thenReturn(many);
        String full = builder.full(ORIGIN, new SiteFiles());
        assertThat(full.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(LlmsBuilder.FULL_MAX_BYTES);
        assertThat(full).contains("This file is cut at 5 MB: it carries the newest ");
        assertThat(full).contains("### Статия 30").doesNotContain("### Статия 1\n");
    }
}
