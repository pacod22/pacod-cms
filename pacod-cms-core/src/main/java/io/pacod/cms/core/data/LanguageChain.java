package io.pacod.cms.core.data;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Which languages to try, in order, when resolving one piece of translated text.
 *
 * <h2>The chain is codes, and that is the point</h2>
 * A {@link Page}'s texts are {@link TextItem}s keyed by a bare language code, and this class
 * resolves against <b>those strings</b> — never against the {@code language} collection. So an
 * empty collection, an unreadable one, or an operator deleting a row cannot make {@code /p/about}
 * stop resolving: {@code ["de", "en", "fr"]} still finds whatever the document actually holds.
 * The collection governs what the console <em>offers</em> and what the public switcher
 * <em>lists</em>, and nothing else.
 *
 * <h2>Requested, then English, then the original</h2>
 * A reader who asked for German and finds no German is better served by English than by the
 * website's own language when that is anything else -- English is the closest thing to a lingua
 * franca, and it is what {@code PageService} falls back to for the notice text. The original stays
 * last because it is the language every page is guaranteed to be written in: a chain ending at
 * English would render a page written only in the original blank.
 *
 * <p>{@link #original()} is <b>not</b> merely the end of the chain. It is the language the website
 * is written in and the one that legally governs its terms and privacy pages -- see
 * {@code Page.officialInDefaultLanguageOnly}, which is deliberately independent of this order.
 * Serving a legal page in English does not make English the governing text, and the notice says
 * so.
 *
 * <h2>Where the original comes from</h2>
 * The website's own setting, through the {@link TenantContext} bound to the current unit of work.
 * Where none is bound, the configured {@code pacod.content.default-language} (default {@code en}),
 * set once at startup by {@code ContentLanguageConfig}. It is read statically because the
 * documents that resolve text through this class are not beans.
 */
public final class LanguageChain {

    /** Tried before {@link #original()} for a reader who asked for anything else. */
    public static final String FALLBACK = "en";

    /** Used until configuration says otherwise. */
    public static final String DEFAULT_ORIGINAL = "en";

    private static volatile String original = DEFAULT_ORIGINAL;

    private LanguageChain() {
    }

    /**
     * The website's own language: last resort, legal authority, and the one page bodies always have.
     *
     * <p>The bound website's ({@link TenantContext}), and the configured default only where none is
     * bound -- startup, and code that is not about any one website.
     */
    public static String original() {
        return TenantContext.find()
                .map(Tenant::defaultLanguage)
                .map(LanguageChain::normalise)
                .orElse(original);
    }

    /**
     * Sets the website's own language and returns the previous one, so a test can restore it.
     *
     * @throws IllegalArgumentException for a value that does not normalise to a bare code
     */
    public static String useOriginal(String code) {
        String normalised = normalise(code);
        if (normalised == null || !normalised.matches("[a-z]{2,3}")) {
            throw new IllegalArgumentException("Not a language code: " + code);
        }
        String previous = original;
        original = normalised;
        return previous;
    }

    /**
     * The codes to try for a reader who asked for {@code requested}, most specific first.
     *
     * <p>Duplicates are dropped, so on a website written in German a German reader gets
     * {@code [de, en]} and an English one {@code [en, de]} -- the second entry is not dead in either
     * case: a page written only in the other language still resolves rather than rendering empty.
     *
     * <p>A {@code null} or blank request is a reader with no stated preference, which means the
     * website's own language first.
     */
    public static List<String> forRequest(String requested) {
        String normalised = normalise(requested);

        List<String> chain = new ArrayList<>(3);
        if (normalised != null) {
            chain.add(normalised);
            // English before the original only for a reader who ASKED for something. With no
            // stated preference the order flips: a reader who said nothing is owed the website's
            // own language, not the fallback meant for people who cannot read it. The console's
            // page list resolves through here too, with no request at all.
            chain.add(FALLBACK);
            chain.add(original);
        } else {
            chain.add(original);
            chain.add(FALLBACK);
        }

        return chain.stream().distinct().toList();
    }

    /**
     * A bare two-letter code, or {@code null} when there is nothing to normalise.
     *
     * <p>The {@code lang} cookie can hold a region-qualified value — {@code CookieLocaleResolver}
     * serialises a whole {@code Locale}, so {@code "en-US"} reaches us. One normalisation, applied
     * wherever a request-supplied code enters, reduces it to {@code "en"}.
     */
    public static String normalise(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String bare = code.trim().toLowerCase();
        int separator = Math.max(bare.indexOf('-'), bare.indexOf('_'));
        return separator > 0 ? bare.substring(0, separator) : bare;
    }
}
