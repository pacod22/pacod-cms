package io.pacod.cms.studio;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.repository.LanguageRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.studio.form.LanguageForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adding, editing and removing a language.
 *
 * <p>Every case here is one that fails <em>silently</em> if it goes wrong: a language deleted out
 * from under ten pages of text, a code that no {@code ?lang} could ever match, or a chain language
 * removed so that "falls back to English" stops describing what the site does.
 */
class MgmtLanguageServiceTest {

    private LanguageRepository languages;
    private PageRepository pages;
    private MgmtLanguageService service;

    @BeforeEach
    void setUp() {
        languages = mock(LanguageRepository.class);
        pages = mock(PageRepository.class);
        service = new MgmtLanguageService(languages, pages);
    }

    private static Language language(String code) {
        Language language = new Language(code);
        language.setAdminName(code.toUpperCase());
        language.setNativeName(code.toUpperCase());
        return language;
    }

    private static Page pageWith(String slug, String lang) {
        Page page = new Page(slug);
        page.putBody(lang, "<p>text</p>");
        return page;
    }

    @Test
    @DisplayName("the two chain languages cannot be deleted, whatever the pages say")
    void chainLanguagesAreRefused() {
        when(languages.findById("bg")).thenReturn(Optional.of(language("bg")));
        when(languages.findById("en")).thenReturn(Optional.of(language("en")));
        when(pages.findAll()).thenReturn(List.of());

        // Not because anything would fail to resolve -- LanguageChain works off bare codes and
        // would carry on -- but because the list would stop describing what the site does.
        assertThatThrownBy(() -> service.delete("bg"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("fallback languages");
        assertThatThrownBy(() -> service.delete("en"))
                .isInstanceOf(MgmtOperationException.class);

        verify(languages, never()).delete(any());
    }

    @Test
    @DisplayName("a language still holding text is refused, and the refusal NAMES the pages")
    void languageInUseIsRefusedByName() {
        when(languages.findById("de")).thenReturn(Optional.of(language("de")));
        when(pages.findAll()).thenReturn(List.of(pageWith("about", "de"), pageWith("terms", "bg")));

        assertThatThrownBy(() -> service.delete("de"))
                .isInstanceOf(MgmtOperationException.class)
                // A count tells the operator they are blocked; the names tell them what to do.
                .hasMessageContaining("/p/about")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("/p/terms"));

        verify(languages, never()).delete(any());
    }

    @Test
    @DisplayName("a language nothing is written in deletes")
    void unusedLanguageDeletes() {
        Language de = language("de");
        when(languages.findById("de")).thenReturn(Optional.of(de));
        when(pages.findAll()).thenReturn(List.of(pageWith("about", "bg")));

        service.delete("de");

        verify(languages).delete(de);
    }

    @Test
    @DisplayName("a title or description alone still blocks the delete, not just a body")
    void anyTextBlocksTheDelete() {
        Page page = new Page("about");
        page.putTitle("de", "Über uns");
        when(languages.findById("de")).thenReturn(Optional.of(language("de")));
        when(pages.findAll()).thenReturn(List.of(page));

        // writtenIn() counts bodies, because that is what a reader can read. The guard is wider on
        // purpose: deleting the row would strand the title with no screen left to reach it from.
        assertThatThrownBy(() -> service.delete("de")).isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("the code is normalised and narrowed to what a ?lang could ever match")
    void codesAreNarrow() {
        assertThat(MgmtLanguageService.normaliseCode(" ES ")).isEqualTo("es");
        // CookieLocaleResolver serialises a whole Locale, so a region-qualified value is a real
        // input here; it reduces to the bare subtag rather than being refused.
        assertThat(MgmtLanguageService.normaliseCode("pt-BR")).isEqualTo("pt");

        assertThatThrownBy(() -> MgmtLanguageService.normaliseCode("e"))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> MgmtLanguageService.normaliseCode("español"))
                .isInstanceOf(MgmtOperationException.class);
        assertThatThrownBy(() -> MgmtLanguageService.normaliseCode(""))
                .isInstanceOf(MgmtOperationException.class);
    }

    @Test
    @DisplayName("a duplicate code is refused rather than overwriting the language")
    void duplicateCodeIsRefused() {
        when(languages.existsById("de")).thenReturn(true);

        LanguageForm form = new LanguageForm();
        form.setCode("de");
        form.setAdminName("Немски");
        form.setNativeName("Deutsch");

        // save() on an existing id is an update, so without this a "new" language would silently
        // replace the existing one -- names, notices and all -- and report success.
        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("a blank notice stores as null, so PageService can fall through to English")
    void blankNoticeStoresAsNull() {
        Language de = language("de");
        when(languages.findById("de")).thenReturn(Optional.of(de));
        when(languages.save(any(Language.class))).thenAnswer(i -> i.getArgument(0));

        LanguageForm form = LanguageForm.of(de);
        form.setFallbackNotice("   ");

        Language saved = service.update("de", form);

        // "" and null are not the same fact downstream: null means "use English's", while a stored
        // blank would render a page with no notice at all -- the outcome the notice exists for.
        assertThat(saved.getFallbackNotice()).isNull();
    }

    @Test
    @DisplayName("page counts come from bodies, one pass over the collection")
    void pageCountsReadBodies() {
        when(pages.findAll()).thenReturn(List.of(
                pageWith("about", "bg"), pageWith("terms", "bg"), pageWith("links", "de")));

        assertThat(service.pageCounts()).containsEntry("bg", 2).containsEntry("de", 1);
    }
}
