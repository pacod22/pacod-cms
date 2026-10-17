package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.LanguageForm;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The language list behind the standing pages: add, edit, remove.
 *
 * <h2>It lives under {@code /mgmt/pages/}, and that is not cosmetic</h2>
 * Anything on a new prefix would fall outside {@code MgmtSecurityConfig}'s authenticated matcher,
 * into the permit-all chain, and outside the website screens {@code StudioPaths} files under
 * {@code /sites/<id>} — language CRUD served to anyone who asked, with nothing on any screen to
 * show for it. {@code MgmtSecurityTest} pins these paths separately from {@code /mgmt/pages} for
 * that reason.
 *
 * <p>The literal {@code /mgmt/pages/languages} beats {@code MgmtPageController}'s
 * {@code /mgmt/pages/{slug}} on specificity, the same ordering that already lets
 * {@code /mgmt/images/search} coexist with {@code /mgmt/images/{id}}. It is checked by request in
 * the tests rather than reasoned about here, because losing that race would not fail — it would
 * quietly 404 with "Няма страница с адрес „languages“".
 *
 * <p>Must stay in this package: {@code MgmtControllerAdvice} is scoped to
 * {@code io.pacod.cms.studio.controller}, and a controller outside it silently loses
 * {@code currentUser} and the topbar's user chip.
 */
@Slf4j
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/pages/languages")
public class MgmtLanguageController {

    private final MgmtLanguageService languageService;
    private final MgmtText text;

    public MgmtLanguageController(MgmtLanguageService languageService, MgmtText text) {
        this.languageService = languageService;
        this.text = text;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("languages", languageService.findAll());
        model.addAttribute("pageCounts", languageService.pageCounts());
        addChainAttributes(model);
        return "mgmt/languages";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new LanguageForm());
        addChainAttributes(model);
        return "mgmt/language-new";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") LanguageForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            addChainAttributes(model);
            return "mgmt/language-new";
        }
        try {
            Language created = languageService.create(form);
            redirect.addFlashAttribute("message", text.msg("pages.flash.languageCreated"));
            return "redirect:/mgmt/pages/languages/" + created.getCode();
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/pages/languages/new";
        }
    }

    @GetMapping("/{code}")
    public String edit(@PathVariable String code, Model model) {
        Language language = require(code);
        model.addAttribute("form", LanguageForm.of(language));
        model.addAttribute("language", language);
        model.addAttribute("usedBy", languageService.usedBy(language.getCode()));
        addChainAttributes(model);
        return "mgmt/language-edit";
    }

    @PostMapping("/{code}")
    public String save(@PathVariable String code,
                       @Valid @ModelAttribute("form") LanguageForm form,
                       BindingResult binding, Model model, RedirectAttributes redirect) {
        Language language = require(code);
        // The path is the authority on which language is being written; the form's code field is
        // display-only on this screen. Trusting it would let a crafted POST rename a row's twin.
        form.setCode(language.getCode());

        if (binding.hasErrors()) {
            model.addAttribute("language", language);
            model.addAttribute("usedBy", languageService.usedBy(language.getCode()));
            addChainAttributes(model);
            return "mgmt/language-edit";
        }

        try {
            languageService.update(language.getCode(), form);
            redirect.addFlashAttribute("message", text.msg("pages.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/pages/languages/" + language.getCode();
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{code}/delete")
    public String delete(@PathVariable String code, RedirectAttributes redirect) {
        try {
            languageService.delete(code);
            redirect.addFlashAttribute("message", text.msg("pages.flash.languageDeleted"));
            return "redirect:/mgmt/pages/languages";
        } catch (MgmtOperationException e) {
            // Back to the screen that names what is blocking it, not to the list -- the refusal is
            // only actionable where the pages are named. Same shape as a refused image delete.
            redirect.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/pages/languages/" + code;
        }
    }

    /** The two codes {@code LanguageChain} names in code, so the screens can say which they are. */
    private void addChainAttributes(Model model) {
        model.addAttribute("originalCode", LanguageChain.original());
        model.addAttribute("fallbackCode", LanguageChain.FALLBACK);
    }

    private Language require(String code) {
        return languageService.find(code).orElseThrow(() ->
                new MgmtNotFoundException("pages.error.languageNotFound", code));
    }
}
