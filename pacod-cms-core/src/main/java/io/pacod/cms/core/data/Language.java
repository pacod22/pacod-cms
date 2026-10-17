package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A language the standing pages can be written in, and everything the site has to say about it.
 *
 * <h2>Why this is a document and not an enum</h2>
 * Which languages a website offers is a fact about content, decided by the same person who writes
 * the content. As an enum, adding one would be a code change, a build and a deploy; as a row it is
 * edited at {@code /mgmt/pages/languages}.
 *
 * <h2>What this collection does NOT govern</h2>
 * <b>Resolution.</b> A {@link Page}'s texts are keyed by bare code strings and
 * {@link LanguageChain} resolves against those, so an empty collection, an unreachable database
 * or an operator deleting a row cannot stop {@code /p/about} rendering. This collection decides
 * what the console <em>offers</em> and what the public switcher <em>lists</em> — nothing else.
 * That split is the whole safety story of making this editable; do not let a reader path start
 * depending on a row existing.
 *
 * <h2>The code is the id</h2>
 * Same shape as {@link Page} and {@link Slug}: the identifier is the thing the URL and the stored
 * {@link TextItem}s are made of, so there is nothing to allocate. It also means the code is
 * chosen once and never edited — renaming it would orphan every {@code TextItem} written under
 * it, which is why the console offers no way to.
 *
 * <h2>Adding a language does not translate the built-in wording</h2>
 * The public site's own strings -- buttons, the search box, dates -- come from its message bundles
 * ({@code messages*.properties} in the site), in a fixed set of languages, and a language added
 * here outside that set is shown with English ones. Pages, articles and menu entries are data and
 * translate like any other content. The studio's language screen says so.
 */
@Data
@Document(collection = "language")
public class Language {

    public static final String COLLECTION = "language";

    /** {@code "bg"}, {@code "de"}, … — bare, lowercase, and the key every {@link TextItem} uses. */
    @Id
    private String code;

    /** The name the studio shows its operators, e.g. {@code German} for {@code de}. */
    private String adminName;

    /**
     * For the public switcher, where a reader should see their own language named — and for the
     * fallback notice, which names the language actually being served.
     *
     * <p>That second use is why this cannot be dropped as a nicety: it is the only name the site
     * has for a language in the language itself, and the alternative is a full matrix of every
     * language's name in every other.
     */
    private String nativeName;

    /** Ascending, for the tab strip and the public switcher. Ties fall back to the code. */
    private Integer sortOrder = 0;

    /**
     * "This page is not translated and is shown in {0}", written in <em>this</em> language.
     *
     * <p>{@code {0}} is substituted with the {@link #nativeName} of the language actually served.
     * The text is stored per language because the notice must be readable by the reader who
     * asked — see {@code PageService.noticeFor}. Blank falls back to English's, and if that is
     * missing too, to a constant in {@code PageService}: a notice is the one string that must
     * still appear when the collection is half-filled.
     */
    private String fallbackNotice;

    /**
     * "The {0} text of this page is the legally binding one", written in this language.
     *
     * <p>{@code {0}} is the native name of the website's original language
     * ({@link LanguageChain#original()}) whatever is being served, because the notice states which
     * text <em>governs</em>, not which text you are reading — the fallback order and legal
     * authority are two different facts about a document, and
     * {@code Page.officialInDefaultLanguageOnly} is independent of {@link LanguageChain}.
     */
    private String legalNotice;

    public Language() {
    }

    public Language(String code) {
        this.code = code;
    }

    /** What to show for this language when a name has not been filled in. */
    public String displayName() {
        if (adminName != null && !adminName.isBlank()) {
            return adminName;
        }
        if (nativeName != null && !nativeName.isBlank()) {
            return nativeName;
        }
        return code == null ? "" : code.toUpperCase();
    }

    /** Never blank: the notice needs a name for the served language and the code is better than none. */
    public String nativeOrCode() {
        return nativeName != null && !nativeName.isBlank()
                ? nativeName
                : (code == null ? "" : code.toUpperCase());
    }
}
