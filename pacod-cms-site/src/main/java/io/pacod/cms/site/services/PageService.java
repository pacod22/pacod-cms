package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a standing page for one request: which page, in which language, and whether the reader
 * is being shown a fallback.
 *
 * <p>Filtering happens here rather than in {@link PageReader} so the cached list stays
 * language-independent -- the same split {@code BannerService} keeps from {@code BannerReader},
 * where caching an already-filtered result would make a campaign start and end late.
 *
 * <h2>The language documents are for saying things, not for finding them</h2>
 * {@link LanguageReader} supplies display names and notice text. It never decides whether a body
 * resolves: that is {@link LanguageChain} over the bare codes the {@code Page} actually stores.
 * An empty or unreachable {@code language} collection therefore costs the reader the switcher and
 * a nicely worded notice, never the page.
 */
@Slf4j
@Service
public class PageService {

    /**
     * The last resort when the {@code language} collection cannot supply a notice.
     *
     * <p>Reached when the collection is empty, unreadable, or holds neither the reader's language
     * nor English with a {@code fallbackNotice} filled in. It is deliberately a constant and
     * deliberately English: a page silently served in the wrong language with <em>no</em> notice
     * is the one outcome this whole mechanism exists to prevent.
     */
    private static final String DEFAULT_FALLBACK_NOTICE =
            "This page has not been translated yet and is shown in {0}.";

    private static final String DEFAULT_LEGAL_NOTICE =
            "The {0} text of this page is the legally binding one. "
                    + "Any translation is provided for information only.";

    private final PageReader pageReader;
    private final LanguageReader languageReader;

    public PageService(PageReader pageReader, LanguageReader languageReader) {
        this.pageReader = pageReader;
        this.languageReader = languageReader;
    }

    public Optional<Page> bySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return pageReader.findAll().stream()
                .filter(page -> slug.equalsIgnoreCase(page.getSlug()))
                .findFirst();
    }

    public List<Page> all() {
        return pageReader.findAll();
    }

    /** Every configured language, in display order. Drives the public switcher. */
    public List<Language> languages() {
        return languageReader.findAll();
    }

    /**
     * Everything the template needs to render one page honestly.
     *
     * @param page        the document
     * @param language    the bare code actually being rendered -- the requested one if the page has
     *                    it, otherwise the first language down {@link LanguageChain} that it does.
     *                    A <b>code</b> rather than a document, because a page can be written in a
     *                    language the studio no longer offers and it still has to render.
     * @param fallback    true when the reader is being served something other than what they asked
     *                    for -- which is not the same as "not translated": the chain has more
     *                    than one step
     * @param legalNotice true when the reader must be told the original-language text is the one that
     *                    governs
     */
    public record Rendered(Page page, String language, boolean fallback, boolean legalNotice,
                           String notice, String title, String body, String description) {
    }

    /**
     * The notice text, in the language the reader <b>asked for</b> -- not the one being rendered.
     *
     * <p>This is the whole point of the notice and it is easy to get backwards. A notice only ever
     * appears when the body has fallen back, so keying it off the rendered language would state,
     * in English, that the page is not available in German -- to the one reader who by definition
     * cannot read it.
     *
     * <p><b>In the fallback notice {@code {0}} is the language actually served, by its own native
     * name.</b> A hardcoded "shown in English" would be a lie on every page served in the
     * website's own language instead. Naming the served language by {@code nativeName} is also the
     * one scheme that keeps working for a language added later, without a matrix of every
     * language's name in every other.
     *
     * <p>Three tiers, because a half-filled collection must still produce a sentence: the reader's
     * own language, then English, then the constant above. A reader on one of the nine locales
     * only the chrome translates lands on the second tier -- English is the closest thing to a
     * lingua franca.
     *
     * <p><b>In the legal notice {@code {0}} is the website's own language</b>, whatever is being
     * served, because the notice states which text <em>governs</em>, not which text you are
     * reading.
     */
    private String noticeFor(String requestedCode, String servedCode, boolean legal,
                             List<Language> languages) {
        String template = notice(find(languages, requestedCode), legal);
        if (template == null) {
            template = notice(find(languages, LanguageChain.FALLBACK), legal);
        }
        if (template == null) {
            template = legal ? DEFAULT_LEGAL_NOTICE : DEFAULT_FALLBACK_NOTICE;
        }

        String named = legal ? LanguageChain.original() : servedCode;
        Language language = find(languages, named);
        String shown = language != null ? language.nativeOrCode()
                : (named == null ? "" : named.toUpperCase());
        return template.replace("{0}", shown);
    }

    private static String notice(Language language, boolean legal) {
        if (language == null) {
            return null;
        }
        String text = legal ? language.getLegalNotice() : language.getFallbackNotice();
        return text == null || text.isBlank() ? null : text;
    }

    private static Language find(List<Language> languages, String code) {
        if (code == null) {
            return null;
        }
        return languages.stream()
                .filter(language -> code.equalsIgnoreCase(language.getCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Picks the language to render in.
     *
     * <p>The order is {@link LanguageChain}'s -- the reader's language, then English, then
     * the website's own language. Three cases, deliberately distinguishable rather than collapsed into "show
     * whatever we have":
     * <ul>
     *   <li>the reader's language has a body -- render it, no notice;</li>
     *   <li>it does not, or the reader is on a locale only the chrome supports --
     *       render the first language down the chain that does, and say so, naming which language
     *       they are being given;</li>
     *   <li>the page is legally binding in the original language only -- say <em>that</em> instead, which is a
     *       different statement and the stronger one.</li>
     * </ul>
     *
     * <p>Two things here are easy to get wrong:
     *
     * <p><b>{@code fallback} means "served something other than what was asked for"</b>, not
     * "not translated". With more than one language down the chain, those are different facts.
     *
     * <p><b>The served language is the first in the chain with a BODY</b>, and every field is then
     * resolved against that <em>one</em> language rather than against the chain. Resolving each
     * field independently would put a German title over an English body on a page that has a
     * German title and nothing else -- three fields agreeing is what makes the notice true.
     */
    public Rendered render(Page page, String requestedLang) {
        String requestedCode = LanguageChain.normalise(requestedLang);
        List<String> chain = LanguageChain.forRequest(requestedCode);

        String servedCode = chain.stream().filter(page::hasBody).findFirst()
                .orElse(LanguageChain.original());
        List<String> served = List.of(servedCode);

        boolean readerIsElsewhere = requestedCode == null || !requestedCode.equals(servedCode);
        // The legal notice is about the ORIGINAL language, not about the chain: a reader already
        // on the original text needs no warning that the original text governs.
        boolean legalNotice = page.isOfficialInDefaultLanguageOnly()
                && !LanguageChain.original().equals(requestedCode);
        // A legal page states the stronger thing instead; two banners saying overlapping things
        // is how a reader learns to skip both.
        boolean fallback = readerIsElsewhere && !legalNotice;

        String notice = (fallback || legalNotice)
                ? noticeFor(requestedCode, servedCode, legalNotice, languages())
                : null;

        return new Rendered(page, servedCode, fallback, legalNotice, notice,
                page.title(served), page.body(served), page.description(served));
    }
}
