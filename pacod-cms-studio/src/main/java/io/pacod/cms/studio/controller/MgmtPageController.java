package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.MgmtPageService;
import io.pacod.cms.studio.form.PageForm;
import io.pacod.cms.studio.i18n.MgmtText;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Editing the standing pages -- about, contact, advertise, terms, privacy and the rest.
 *
 * <p>Must stay in this package: {@code MgmtControllerAdvice} is scoped to
 * {@code io.pacod.cms.studio.controller}, and a controller outside it silently loses
 * {@code currentUser} and the topbar's user chip.
 *
 * <p>{@code /mgmt/pages} needs <b>no security change</b> -- it is under {@code /mgmt/}, inside
 * {@code MgmtSecurityConfig}'s authenticated matcher.
 *
 * <h2>The language is in the query string, not the path</h2>
 * {@code /mgmt/pages/terms?lang=de} rather than {@code /mgmt/pages/terms/de}. A third path segment
 * would sit exactly where {@code /mgmt/jobs/&#123;id&#125;/status} does and start competing with
 * {@code MgmtHomeController}'s {@code /mgmt/**} catch-all on specificity; a query parameter cannot.
 * It also makes "same page, other language" a link that changes one parameter, which is what the
 * tab strip is.
 */
@Slf4j
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/pages")
public class MgmtPageController {

    private final MgmtPageService pageService;
    private final MgmtLanguageService languageService;
    private final MgmtText text;
    private final io.pacod.cms.studio.access.StudioAccess access;

    public MgmtPageController(MgmtPageService pageService, MgmtLanguageService languageService, MgmtText text,
                              io.pacod.cms.studio.access.StudioAccess access) {
        this.pageService = pageService;
        this.languageService = languageService;
        this.text = text;
        this.access = access;
    }

    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q, Model model) {
        model.addAttribute("pages", pageService.findAll(q));
        model.addAttribute("q", q);
        model.addAttribute("languages", languageService.findAll());
        // The list column shows a title, and a page written only in English has none in the
        // original language.
        // Same chain the site resolves with, so the console cannot show a blank where the public
        // page shows text.
        model.addAttribute("titleChain", LanguageChain.forRequest(null));
        return "mgmt/pages";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new PageForm());
        return "mgmt/page-new";
    }

    @PostMapping
    public String create(@RequestParam String slug, RedirectAttributes redirect) {
        try {
            Page created = pageService.create(slug);
            redirect.addFlashAttribute("message", text.msg("pages.flash.created"));
            return "redirect:/mgmt/pages/" + created.getSlug();
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/pages/new";
        }
    }

    @GetMapping("/{slug}")
    public String edit(@PathVariable String slug,
                       @RequestParam(name = "lang", required = false) String lang,
                       Model model) {
        Page page = require(slug);
        Language language = languageOrOriginal(lang);

        model.addAttribute("form", PageForm.of(page, language.getCode()));
        addEditAttributes(model, page, language);
        return "mgmt/page-edit";
    }

    @PostMapping("/{slug}")
    public String save(@PathVariable String slug,
                       @Valid @ModelAttribute("form") PageForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes redirect) {
        // The path is the authority on which page is being written; the hidden field only has to
        // agree. Trusting the field would let a crafted POST edit a different page.
        form.setSlug(slug);
        Page page = require(slug);
        Language language = languageOrOriginal(form.getLang());

        if (binding.hasErrors()) {
            // Re-render rather than redirect: a redirect would discard the operator's unsaved
            // rich text, which is the whole thing the editor's beforeunload guard warns about.
            addEditAttributes(model, page, language);
            return "mgmt/page-edit";
        }

        try {
            pageService.save(form, access.allowed("content.publish"));
            redirect.addFlashAttribute("message",
                    text.msg("pages.flash.savedLanguage", language.displayName()));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/pages/" + slug + "?lang=" + language.getCode();
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{slug}/remove-translation")
    public String removeTranslation(@PathVariable String slug,
                                    @RequestParam String lang,
                                    RedirectAttributes redirect) {
        try {
            pageService.removeTranslation(slug, lang);
            redirect.addFlashAttribute("message", text.msg("pages.flash.translationRemoved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/pages/" + slug;
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{slug}/delete")
    public String delete(@PathVariable String slug, RedirectAttributes redirect) {
        pageService.delete(slug);
        redirect.addFlashAttribute("message", text.msg("pages.flash.deleted"));
        return "redirect:/mgmt/pages";
    }

    /** Everything the edit screen needs beyond the form itself. */
    private void addEditAttributes(Model model, Page page, Language language) {
        model.addAttribute("page", page);
        model.addAttribute("language", language);
        model.addAttribute("languages", languageService.findAll());
        model.addAttribute("originalCode", LanguageChain.original());
    }

    /**
     * The requested language, or the site's own when the code is unknown.
     *
     * <p>Falls back to a synthesised {@link Language} rather than throwing when even the original
     * language has no row: the tab strip is empty in that state, but the screen still renders and
     * the operator can still reach {@code /mgmt/pages/languages} to fix it. A 500 here would make
     * an empty language collection unrecoverable from the console.
     */
    private Language languageOrOriginal(String code) {
        return languageService.find(code)
                .or(() -> languageService.find(LanguageChain.original()))
                .orElseGet(() -> {
                    // No row for the original either: a nameless stand-in, which displays as
                    // its code -- naming it here would be guessing which language it is.
                    return new Language(LanguageChain.original());
                });
    }

    private Page require(String slug) {
        return pageService.find(slug).orElseThrow(() ->
                new MgmtNotFoundException("pages.error.notFound", slug));
    }
}
