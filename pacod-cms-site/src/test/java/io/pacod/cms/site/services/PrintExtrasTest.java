package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.PdfEnrichment;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.PdfEnrichmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrintExtrasTest {

    private PdfEnrichmentRepository repository;
    private PrintExtras extras;
    private Article article;

    @BeforeEach
    void setUp() {
        repository = mock(PdfEnrichmentRepository.class);
        extras = new PrintExtras(repository);
        article = new Article(4);
        article.setTitle("Заглавие");
        article.setText("<p>Тяло</p>");
        article.setDescription("Собствено описание");
        article.setTags(List.of("град", "наука"));
    }

    @Test
    @DisplayName("code mode prints the description in exactly that language and the translated tags")
    void codeMode() {
        Tag city = new Tag("град", List.of(TextItem.builder().lang("en").text("city").build()));
        PrintExtras.PrintMeta bg = extras.meta(article, "bg", PdfMode.CODE, List.of(city));
        assertThat(bg.abstractText()).isEqualTo("Собствено описание");
        assertThat(bg.keywords()).containsExactly("град", "наука");

        PrintExtras.PrintMeta en = extras.meta(article, "en", PdfMode.CODE, List.of(city));
        // No English description: nothing, never the made-up "date; authors; title" line.
        assertThat(en.hasAbstract()).isFalse();
        assertThat(en.keywords()).containsExactly("city", "наука");
    }

    @Test
    @DisplayName("llm mode prints the enrichment only while it matches the current text")
    void llmModeChecksTheHash() {
        PdfEnrichment row = new PdfEnrichment();
        row.setAbstractText("Резюме от модела");
        row.setKeywords(List.of("ключова"));
        row.setSourceHash(PdfEnrichment.sourceHash("Заглавие", "<p>Тяло</p>"));
        when(repository.findById("4:bg")).thenReturn(Optional.of(row));

        PrintExtras.PrintMeta meta = extras.meta(article, "bg", PdfMode.LLM, List.of());
        assertThat(meta.abstractText()).isEqualTo("Резюме от модела");
        assertThat(meta.keywords()).containsExactly("ключова");

        article.setText("<p>Редактирано тяло</p>");
        PrintExtras.PrintMeta stale = extras.meta(article, "bg", PdfMode.LLM, List.of());
        assertThat(stale.abstractText()).isEqualTo("Собствено описание");
    }

    @Test
    @DisplayName("a database that cannot be read prints the article's own front matter")
    void failureFallsBack() {
        when(repository.findById("4:bg")).thenThrow(new IllegalStateException("down"));
        assertThat(extras.meta(article, "bg", PdfMode.LLM, List.of()).abstractText()).isEqualTo("Собствено описание");
    }
}
