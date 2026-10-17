package io.pacod.cms.studio;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.repository.LanguageRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.studio.form.LanguageForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The languages the standing pages can be written in: add, edit, remove.
 *
 * <p>Read straight off {@link LanguageRepository}, with no reader and no cache. The console must
 * not grow one -- the same rule the monitoring screen follows: half a dozen rows are free to read,
 * and a 60 s cache would show an operator the list as it was before their own edit.
 *
 * <h2>Deleting is reference-counted, exactly like deleting an image</h2>
 * {@code ImageService.deleteImage} refuses while an article still points at the image; this
 * refuses while a page still holds text in the language, and <b>names the pages</b> rather than
 * counting them, for the reason {@code /mgmt/images/{id}} names the articles: a number tells the
 * operator that they are blocked, not what to do about it.
 *
 * <p>Nothing here cascades. A "delete the language and all its texts" button would let one click
 * destroy translations across every page with no undo, and this project has no undo -- the closest
 * thing is {@code Article.previousText}, which exists for exactly one field.
 */
@Slf4j
@Service
public class MgmtLanguageService {

    private final LanguageRepository languageRepository;
    private final PageRepository pageRepository;

    public MgmtLanguageService(LanguageRepository languageRepository, PageRepository pageRepository) {
        this.languageRepository = languageRepository;
        this.pageRepository = pageRepository;
    }

    public List<Language> findAll() {
        return languageRepository.findAllByOrderBySortOrderAscCodeAsc();
    }

    public Optional<Language> find(String code) {
        String normalised = LanguageChain.normalise(code);
        return normalised == null ? Optional.empty() : languageRepository.findById(normalised);
    }

    /**
     * How many pages hold a body in each language, keyed by code.
     *
     * <p>One pass over a handful of documents in the JVM rather than a query per row -- the same
     * trade the author and category lists make. Counts bodies, not titles: a title with no body
     * behind it is not a translation anybody can read.
     */
    public Map<String, Integer> pageCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Page page : pageRepository.findAll()) {
            for (String code : page.writtenIn()) {
                counts.merge(code, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The pages holding any text at all in this language -- what a refused delete has to name. */
    public List<Page> usedBy(String code) {
        String normalised = LanguageChain.normalise(code);
        if (normalised == null) {
            return List.of();
        }
        List<Page> using = new ArrayList<>();
        for (Page page : pageRepository.findAll()) {
            if (page.hasAnyTextIn(normalised)) {
                using.add(page);
            }
        }
        return using;
    }

    /**
     * Creates a language. The code is the id, so it is chosen once and never edited afterwards --
     * renaming it would orphan every {@code TextItem} already written under it.
     */
    public Language create(LanguageForm form) {
        String code = normaliseCode(form.getCode());

        if (languageRepository.existsById(code)) {
            throw new MgmtOperationException("pages.error.languageExists", code);
        }

        Language language = new Language(code);
        apply(language, form);
        return languageRepository.save(language);
    }

    /** Everything except the code, which is the id. */
    public Language update(String code, LanguageForm form) {
        Language language = require(code);
        apply(language, form);
        return languageRepository.save(language);
    }

    /**
     * Removes a language from the list the console offers and the site lists.
     *
     * <p>Two refusals, and they are different in kind. The chain languages are refused because
     * {@code LanguageChain} names them in code: the list would no longer describe what the site
     * does. Everything else is refused only while text still exists in it -- delete the
     * translations first and the row goes.
     */
    public void delete(String code) {
        Language language = require(code);
        String normalised = language.getCode();

        if (LanguageChain.original().equals(normalised) || LanguageChain.FALLBACK.equals(normalised)) {
            throw new MgmtOperationException("pages.error.languageInChain", language.displayName());
        }

        List<Page> using = usedBy(normalised);
        if (!using.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Page page : using) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append("/p/").append(page.getSlug());
            }
            throw new MgmtOperationException("pages.error.languageInUse",
                    language.displayName(), using.size(), names.toString());
        }

        languageRepository.delete(language);
    }

    private void apply(Language language, LanguageForm form) {
        language.setAdminName(trimToNull(form.getAdminName()));
        language.setNativeName(trimToNull(form.getNativeName()));
        language.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        // Blank stores as null for the reason Page.put drops blanks: PageService has to tell "no
        // notice written for this language, use English's" from "deliberately empty", and a page
        // served in the wrong language with no notice at all is the outcome it exists to prevent.
        language.setFallbackNotice(trimToNull(form.getFallbackNotice()));
        language.setLegalNotice(trimToNull(form.getLegalNotice()));
    }

    private Language require(String code) {
        return find(code).orElseThrow(() ->
                new MgmtNotFoundException("pages.error.languageNotFound", code));
    }

    private static String trimToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * Two or three lowercase letters, and nothing else.
     *
     * <p>Narrow on purpose: the code is a document id, a URL parameter and the key of every stored
     * {@code TextItem}, and it is compared against whatever {@code ?lang} or the {@code lang}
     * cookie carries -- which {@code LanguageChain.normalise} has already reduced to a bare
     * subtag. A code that could not survive that normalisation would be a language nothing ever
     * resolves to, silently.
     */
    static String normaliseCode(String raw) {
        String code = LanguageChain.normalise(raw);
        if (code == null || !code.matches("[a-z]{2,3}")) {
            throw new MgmtOperationException("pages.error.languageCode");
        }
        return code;
    }
}
