package io.pacod.cms.studio;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.studio.form.PageForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Editing the standing pages.
 *
 * <p>A handful of documents, so {@link #findAll(String)} reads the collection and filters in the
 * JVM through {@link MgmtSearch} -- the same trade the author, category and banner lists make, and
 * the reason this class is in {@code io.pacod.cms.studio} rather than a sub-package
 * ({@code MgmtSearch} is package-private).
 *
 * <h2>One language at a time</h2>
 * The editor edits <b>one</b> language per screen, chosen by a tab. {@link #save} therefore writes
 * only that language's title, body and description and leaves every other one untouched -- the
 * same apply-do-not-replace rule {@code MgmtArticleService.save} follows, and for a sharper
 * reason: a form that posted every body at once would have to carry one TinyMCE instance per
 * language -- a number that is now editable at {@code /mgmt/pages/languages} -- and any language
 * whose field failed to initialise would post empty and silently delete a translation.
 *
 * <h2>Blank means absent</h2>
 * Clearing a field <em>removes</em> that language's entry rather than storing {@code ""}. The
 * public side distinguishes "not translated, fall back and say so" from "deliberately empty", and
 * it can only do that if nothing ever writes an empty string -- the {@code article.embedded} trap,
 * avoided here by construction. {@link Page#put} is what enforces it.
 */
@Slf4j
@Service
public class MgmtPageService {

    private final PageRepository pageRepository;
    private final MgmtLanguageService languageService;

    public MgmtPageService(PageRepository pageRepository, MgmtLanguageService languageService) {
        this.pageRepository = pageRepository;
        this.languageService = languageService;
    }

    /**
     * Every page, newest ordering first, optionally filtered.
     *
     * <p>The search reads the slug and every stored title in every language, so an operator can
     * find the terms page by typing its title in any language it has, or {@code terms}.
     * {@code MgmtSearch.matches} requires every whitespace-separated part to occur somewhere, which
     * is what makes a two-word search work across two different fields.
     */
    public List<Page> findAll(String search) {
        List<Page> pages = new ArrayList<>(pageRepository.findAll());
        pages.sort((a, b) -> {
            int order = Integer.compare(
                    a.getSortOrder() == null ? 0 : a.getSortOrder(),
                    b.getSortOrder() == null ? 0 : b.getSortOrder());
            return order != 0 ? order : String.valueOf(a.getSlug()).compareTo(String.valueOf(b.getSlug()));
        });

        if (search == null || search.isBlank()) {
            return pages;
        }

        List<Page> matched = new ArrayList<>();
        for (Page page : pages) {
            List<String> haystack = new ArrayList<>(List.of());
            haystack.add(page.getSlug());
            // Every title the DOCUMENT holds, read raw. Iterating the configured languages
            // instead would stop finding a page by a title written in a language since removed
            // from the list -- and rawTitle, not title, because a resolved title falls back and
            // would add one copy of the same string per language.
            for (TextItem title : page.getTitles() == null ? List.<TextItem>of() : page.getTitles()) {
                if (title != null) {
                    haystack.add(title.getText());
                }
            }
            if (MgmtSearch.matches(search, haystack.toArray(new String[0]))) {
                matched.add(page);
            }
        }
        return matched;
    }

    public Optional<Page> find(String slug) {
        return slug == null ? Optional.empty() : pageRepository.findById(slug);
    }

    /** How many languages this page has a body in. Drives the list's coverage column. */
    public int translationCount(Page page) {
        return page.writtenIn().size();
    }

    /**
     * Creates a page. The slug is the id, so it is chosen once and never edited afterwards.
     *
     * <p>Rejected rather than silently overwritten when it is taken: {@code save()} on an existing
     * id is an update, so a duplicate slug here would replace a whole page with a blank one and
     * report success.
     */
    public Page create(String slug) {
        String normalized = normalizeSlug(slug);
        if (normalized.isBlank()) {
            throw new MgmtOperationException("pages.error.slugRequired");
        }
        if (pageRepository.existsById(normalized)) {
            throw new MgmtOperationException("pages.error.slugTaken", normalized);
        }

        Page page = new Page(normalized);
        page.setPublished(false);
        return pageRepository.save(page);
    }

    /** Writes one language's text plus the settings that are not per-language. */
    public Page save(PageForm form) {
        return save(form, true);
    }

    /** {@link #save(PageForm)}; without {@code content.publish} whether the page is live stays as it was. */
    public Page save(PageForm form, boolean mayPublish) {
        Page page = require(form.getSlug());
        String lang = languageOf(form.getLang()).getCode();

        page.putTitle(lang, form.getTitle());
        page.putBody(lang, form.getBody());
        page.putDescription(lang, form.getDescription());

        if (mayPublish) {
            page.setPublished(form.isPublished());
        }
        page.setOfficialInDefaultLanguageOnly(form.isOfficialInDefaultLanguageOnly());
        page.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        page.setLastModifiedOn(LocalDateTime.now());

        return pageRepository.save(page);
    }

    /**
     * Removes one language's text without touching the rest.
     *
     * <p>A separate action rather than "clear the fields and save", because clearing the body of
     * the default language is how you would accidentally leave every other language falling back
     * to nothing. This refuses that case outright.
     */
    public Page removeTranslation(String slug, String lang) {
        Page page = require(slug);
        Language language = languageOf(lang);

        // Only the ORIGINAL is refused, not both chain languages: removing a page's English text
        // is legitimate -- it simply falls through to the original, which is the end of the chain
        // and therefore the one text that cannot be allowed to go missing.
        if (LanguageChain.original().equals(language.getCode())) {
            throw new MgmtOperationException("pages.error.originalNotRemovable");
        }

        page.putTitle(language.getCode(), null);
        page.putBody(language.getCode(), null);
        page.putDescription(language.getCode(), null);
        page.setLastModifiedOn(LocalDateTime.now());
        return pageRepository.save(page);
    }

    public void delete(String slug) {
        pageRepository.delete(require(slug));
    }

    private Page require(String slug) {
        return find(slug).orElseThrow(() ->
                new MgmtNotFoundException("pages.error.notFound", slug));
    }

    /**
     * The configured language for a code.
     *
     * <p>Looked up in the collection rather than merely normalised, so the console cannot write a
     * translation in a language it does not offer -- a body under a code no screen lists would be
     * invisible to every operator while the site happily served it.
     */
    private Language languageOf(String lang) {
        return languageService.find(lang).orElseThrow(() ->
                new MgmtOperationException("pages.error.unknownLanguage", lang));
    }

    /** Lowercase, dashes, nothing that would need escaping in a URL. */
    static String normalizeSlug(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase().replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
