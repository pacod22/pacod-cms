package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.studio.MgmtAuthorService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.form.AuthorForm;
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
 * Author administration: a list plus an edit form.
 */
@Slf4j
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/authors")
public class MgmtAuthorController {

    private final MgmtAuthorService authorService;
    private final MgmtText text;

    public MgmtAuthorController(MgmtAuthorService authorService, MgmtText text) {
        this.authorService = authorService;
        this.text = text;
    }

    /**
     * The search term and the column ordering are GET parameters, so a filtered, sorted list is
     * a bookmarkable URL -- and the column headers are plain links rather than a script that
     * reorders the DOM. A control that needs JavaScript to mean anything fails silently when the
     * script does -- a gallery's dead arrows, an editor that cannot load its plugins -- and a
     * header that silently stops sorting is the same shape of failure.
     *
     * <p>Both are normalised before they reach the model, never echoed raw: the header marks the
     * active column and computes each link's next direction from these two attributes, so
     * {@code ?sort=nonsense} must arrive as the default rather than as itself.
     */
    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "sort", required = false) String sort,
                       @RequestParam(name = "dir", required = false) String dir,
                       Model model) {

        String sortKey = MgmtAuthorService.sortKey(sort);
        String direction = MgmtAuthorService.sortDirection(dir);

        model.addAttribute("authors", authorService.findAll(q, sortKey, direction));
        // Echoed back so the box keeps what was typed, and so the empty state can tell
        // "no authors at all" apart from "none match".
        model.addAttribute("q", q);
        // The search form and the "Изчисти" link carry these as hidden fields / parameters: a
        // GET form replaces the whole query string, so without them typing in the box would
        // silently reset the sort -- the trap the image library's hidden "size" field documents.
        model.addAttribute("sort", sortKey);
        model.addAttribute("dir", direction);
        return "mgmt/authors";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new AuthorForm());
        model.addAttribute("isNew", true);
        return "mgmt/author-edit";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") AuthorForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {

        if (binding.hasErrors()) {
            model.addAttribute("isNew", true);
            return "mgmt/author-edit";
        }

        Author author = authorService.create(form);

        flash.addFlashAttribute("message", text.msg("articles.flash.authorCreated"));
        return "redirect:/mgmt/authors/" + author.getId();
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable Integer id, Model model) {
        Author author = authorService.find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.authorNotFound", id));

        model.addAttribute("form", AuthorForm.of(author));
        model.addAttribute("isNew", false);
        return "mgmt/author-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable Integer id,
                       @Valid @ModelAttribute("form") AuthorForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {

        if (binding.hasErrors()) {
            model.addAttribute("isNew", false);
            return "mgmt/author-edit";
        }

        authorService.save(id, form);

        flash.addFlashAttribute("message", text.msg("articles.flash.authorSaved"));
        return "redirect:/mgmt/authors/" + id;
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id, RedirectAttributes flash) {
        authorService.delete(id);

        flash.addFlashAttribute("message", text.msg("articles.flash.authorDeleted"));
        return "redirect:/mgmt/authors";
    }
}
