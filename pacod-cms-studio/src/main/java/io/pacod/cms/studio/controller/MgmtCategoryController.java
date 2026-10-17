package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.studio.MgmtCategoryService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.CategoryForm;
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

import java.util.Map;

/**
 * Category administration.
 */
@Slf4j
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/categories")
public class MgmtCategoryController {

    private final MgmtCategoryService categoryService;
    private final MgmtText text;

    public MgmtCategoryController(MgmtCategoryService categoryService, MgmtText text) {
        this.categoryService = categoryService;
        this.text = text;
    }

    /** The search term is a GET parameter, so a filtered list is a bookmarkable URL. */
    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "sort", required = false) String sort,
                       @RequestParam(name = "dir", required = false) String dir,
                       Model model) {

        // One aggregation for the whole column, not one query per row. A category with no
        // articles is absent from the map, so the template defaults it to zero. Deliberately not
        // scoped to the filter: the count is a per-category total, and the rows the filter kept
        // are a subset of its keys either way.
        //
        // Read before the list, because «Статии» is a sortable column and its values are not on
        // the document -- findAll takes the same map rather than running the aggregation again.
        Map<Integer, Long> usage = categoryService.countArticlesByCategory();

        String sortKey = MgmtCategoryService.sortKey(sort);
        String direction = MgmtCategoryService.sortDirection(dir);

        model.addAttribute("categories", categoryService.findAll(q, sortKey, direction, usage));
        model.addAttribute("usage", usage);
        // Echoed back so the box keeps what was typed, and so the empty state can tell
        // "no categories at all" apart from "none match".
        model.addAttribute("q", q);
        // Normalised, never raw: the header marks the active column and computes each link's next
        // direction from these. They also ride in the search form as hidden fields -- a GET form
        // replaces the whole query string, so without them typing would reset the ordering.
        model.addAttribute("sort", sortKey);
        model.addAttribute("dir", direction);
        return "mgmt/categories";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new CategoryForm());
        model.addAttribute("isNew", true);
        return "mgmt/category-edit";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CategoryForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {

        if (binding.hasErrors()) {
            model.addAttribute("isNew", true);
            return "mgmt/category-edit";
        }

        try {
            Category category = categoryService.create(form);
            flash.addFlashAttribute("message", text.msg("articles.flash.categoryCreated"));
            return "redirect:/mgmt/categories/" + category.getId();

        } catch (MgmtOperationException e) {
            model.addAttribute("isNew", true);
            model.addAttribute("error", text.of(e));
            return "mgmt/category-edit";
        }
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable Integer id, Model model) {
        Category category = categoryService.find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.categoryNotFound", id));

        model.addAttribute("form", CategoryForm.of(category));
        model.addAttribute("isNew", false);
        model.addAttribute("articleCount", categoryService.countArticles(id));
        return "mgmt/category-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable Integer id,
                       @Valid @ModelAttribute("form") CategoryForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {

        if (binding.hasErrors()) {
            model.addAttribute("isNew", false);
            model.addAttribute("articleCount", categoryService.countArticles(id));
            return "mgmt/category-edit";
        }

        categoryService.save(id, form);

        flash.addFlashAttribute("message", text.msg("articles.flash.categorySaved"));
        return "redirect:/mgmt/categories/" + id;
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id, RedirectAttributes flash) {
        categoryService.delete(id);

        flash.addFlashAttribute("message", text.msg("articles.flash.categoryDeleted"));
        return "redirect:/mgmt/categories";
    }
}
