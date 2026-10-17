package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Language resolution for the standing pages.
 *
 * <p>Every case here is one a reader sees and no exception reports: a page silently served in the
 * wrong language, a notice written in a language the reader cannot read, or a "translated" badge
 * on text that is really the Bulgarian original.
 */
class PageServiceTest {

    private PageReader reader;
    private LanguageReader languages;
    private PageService service;

    @BeforeEach
    void setUp() {
        reader = mock(PageReader.class);
        languages = mock(LanguageReader.class);
        when(languages.findAll()).thenReturn(configuredLanguages());
        service = new PageService(reader, languages);
    }

    /**
     * Language documents shaped as {@code seed/languages.json} seeds them, trimmed to what the
     * notices need. Built here rather than read from the seed: this asserts what the code does with
     * the documents, and a test that read the shipped file would pass on the day someone emptied
     * it.
     */
    private static List<Language> configuredLanguages() {
        return List.of(
                language("bg", "Български", "Български",
                        "Тази страница още не е преведена и се показва на {0}.",
                        "Правно обвързващ е българският текст на тази страница."),
                language("en", "Английски", "English",
                        "This page has not been translated yet and is shown in {0}.",
                        "The Bulgarian text of this page is the legally binding one."),
                language("de", "Немски", "Deutsch",
                        "Diese Seite ist noch nicht übersetzt und wird auf {0} angezeigt.",
                        "Rechtsverbindlich ist der bulgarische Text dieser Seite."),
                language("zh", "Китайски", "中文", "本页面尚未翻译，现以{0}显示。", null));
    }

    private static Language language(String code, String bulgarian, String nativeName,
                                     String fallbackNotice, String legalNotice) {
        Language language = new Language(code);
        language.setAdminName(bulgarian);
        language.setNativeName(nativeName);
        language.setFallbackNotice(fallbackNotice);
        language.setLegalNotice(legalNotice);
        return language;
    }

    private static Page page(String slug) {
        Page page = new Page(slug);
        page.putTitle("bg", "За нас");
        page.putBody("bg", "<p>Български текст</p>");
        return page;
    }

    @Test
    @DisplayName("a translated language is rendered as itself, with no notice")
    void translatedLanguageWins() {
        Page page = page("about");
        page.putTitle("de", "Über uns");
        page.putBody("de", "<p>Deutscher Text</p>");

        PageService.Rendered r = service.render(page, "de");

        assertThat(r.language()).isEqualTo("de");
        assertThat(r.body()).contains("Deutscher");
        assertThat(r.fallback()).isFalse();
        assertThat(r.notice()).isNull();
    }

    @Test
    @DisplayName("an untranslated language falls back to ENGLISH when the page has it")
    void missingTranslationPrefersEnglish() {
        Page page = page("about");
        page.putTitle("en", "About us");
        page.putBody("en", "<p>English text</p>");

        PageService.Rendered r = service.render(page, "de");

        // English before Bulgarian: the reader asked for German, and English is the closer thing
        // to a lingua franca for someone who could not read Bulgarian either.
        assertThat(r.language()).isEqualTo("en");
        assertThat(r.body()).contains("English text");
        assertThat(r.title()).isEqualTo("About us");
        assertThat(r.fallback()).isTrue();
        // The notice is in the language the reader ASKED for, and it names the language they are
        // actually being given -- a hardcoded "shown in Bulgarian" would be a lie.
        assertThat(r.notice()).isEqualTo("Diese Seite ist noch nicht übersetzt und wird auf English angezeigt.");
    }

    @Test
    @DisplayName("with no English either, the chain ends at Bulgarian")
    void missingEnglishFallsThroughToBulgarian() {
        PageService.Rendered r = service.render(page("about"), "de");

        assertThat(r.language()).isEqualTo("bg");
        assertThat(r.body()).contains("Български");
        assertThat(r.fallback()).isTrue();
        assertThat(r.notice()).contains("Български");
    }

    @Test
    @DisplayName("every field is resolved in the language actually served, not field by field")
    void oneLanguageWinsForEveryField() {
        Page page = page("about");
        page.putBody("en", "<p>English text</p>");
        // A German title and no German body. Resolving each field down the chain independently
        // would put this title over the English body and the notice would be wrong about both.
        page.putTitle("de", "Über uns");

        PageService.Rendered r = service.render(page, "de");

        assertThat(r.language()).isEqualTo("en");
        assertThat(r.body()).contains("English text");
        assertThat(r.title()).isNotEqualTo("Über uns");
    }

    @Test
    @DisplayName("a Bulgarian reader falls forward to English rather than getting nothing")
    void theOriginalLanguageAlsoFallsBack() {
        Page page = new Page("about");
        page.putTitle("en", "About us");
        page.putBody("en", "<p>English only</p>");

        PageService.Rendered r = service.render(page, "bg");

        assertThat(r.language()).isEqualTo("en");
        assertThat(r.fallback()).isTrue();
        assertThat(r.notice()).isEqualTo("Тази страница още не е преведена и се показва на English.");
    }

    @Test
    @DisplayName("a locale the pages are not authored in at all gets an English notice")
    void unsupportedLocaleGetsEnglish() {
        // Greek: a language the reader may ask for that no page is authored in.
        PageService.Rendered r = service.render(page("about"), "el");

        assertThat(r.language()).isEqualTo("bg");
        assertThat(r.fallback()).isTrue();
        assertThat(r.notice()).isEqualTo("This page has not been translated yet and is shown in Български.");
    }

    @Test
    @DisplayName("a legal page says which text governs, instead of that a translation is missing")
    void legalNoticeReplacesTheFallbackNotice() {
        Page terms = page("terms");
        terms.setOfficialInDefaultLanguageOnly(true);

        PageService.Rendered r = service.render(terms, "de");

        // Both notices would be true here. Only the stronger one renders: two banners with
        // overlapping meaning is how a reader learns to skip both.
        assertThat(r.legalNotice()).isTrue();
        assertThat(r.fallback()).isFalse();
        assertThat(r.notice()).contains("Rechtsverbindlich");
    }

    @Test
    @DisplayName("a Bulgarian reader on a legal page gets no notice at all")
    void theDefaultLanguageNeverSeesANotice() {
        Page terms = page("terms");
        terms.setOfficialInDefaultLanguageOnly(true);

        PageService.Rendered r = service.render(terms, "bg");

        assertThat(r.notice()).isNull();
        assertThat(r.legalNotice()).isFalse();
    }

    @Test
    @DisplayName("a region-qualified locale still matches, because the cookie stores one")
    void regionQualifiedLocalesResolve() {
        Page page = page("about");
        page.putBody("en", "<p>English</p>");

        // CookieLocaleResolver serialises a whole Locale, so the lang cookie can hold en-US.
        assertThat(service.render(page, "en-US").language()).isEqualTo("en");
        assertThat(service.render(page, "EN").language()).isEqualTo("en");
    }

    @Test
    @DisplayName("blank stored text counts as no translation, not as an empty page")
    void blankIsAbsent() {
        Page page = page("about");
        // put() refuses to store a blank, which is what keeps the two distinguishable. This
        // asserts the behaviour rather than the storage, so it survives a change of mechanism.
        page.putBody("de", "   ");

        PageService.Rendered r = service.render(page, "de");

        assertThat(r.fallback()).isTrue();
        assertThat(r.body()).contains("Български");
        assertThat(page.writtenIn()).containsExactly("bg");
    }

    @Test
    @DisplayName("a notice the reader's language has no wording for falls through to English's")
    void noticeFallsThroughToEnglishWording() {
        Page terms = page("terms");
        terms.setOfficialInDefaultLanguageOnly(true);

        // Chinese is configured with a fallback notice but no legal one. The reader still gets a
        // sentence: the alternative is a legal page served in a language it does not govern with
        // nothing saying so.
        PageService.Rendered r = service.render(terms, "zh");

        assertThat(r.legalNotice()).isTrue();
        assertThat(r.notice()).isEqualTo("The Bulgarian text of this page is the legally binding one.");
    }

    @Test
    @DisplayName("an empty language collection still produces a notice, and still renders the page")
    void anEmptyLanguageCollectionDegradesToTheConstant() {
        // The collection is what the studio offers and what the switcher lists -- never what
        // resolution depends on. An unreachable one costs the reader the wording, not the page.
        when(languages.findAll()).thenReturn(List.of());

        PageService.Rendered r = service.render(page("about"), "de");

        assertThat(r.body()).contains("Български");
        assertThat(r.notice()).isEqualTo("This page has not been translated yet and is shown in BG.");
    }

    @Test
    @DisplayName("a body in a language the studio no longer offers is still served")
    void aRemovedLanguageStillResolves() {
        Page page = page("about");
        page.putBody("es", "<p>Texto</p>");

        // Spanish is not in configuredLanguages(): the row was deleted, the text was not.
        PageService.Rendered r = service.render(page, "es");

        assertThat(r.language()).isEqualTo("es");
        assertThat(r.body()).contains("Texto");
        assertThat(r.fallback()).isFalse();
    }

    @Test
    @DisplayName("a page resolves by its slug, and an unknown slug by nothing")
    void slugsResolve() {
        Page page = page("terms");
        when(reader.findAll()).thenReturn(List.of(page));

        assertThat(service.bySlug("terms")).containsSame(page);
        assertThat(service.bySlug("nope")).isEmpty();
    }

    @Test
    @DisplayName("the page's own switcher offers only the languages it is written in")
    void switcherListsOnlyRealTranslations() {
        Page page = page("about");
        page.putBody("en", "<p>English</p>");
        page.putBody("zh", "<p>中文</p>");

        // Offering every configured language would give the reader a link back to the page they
        // are already on. Read off the document, so a body in a language no longer offered by the
        // studio still counts -- the text did not disappear because a row did.
        assertThat(page.writtenIn()).containsExactly("bg", "en", "zh");
    }
}
