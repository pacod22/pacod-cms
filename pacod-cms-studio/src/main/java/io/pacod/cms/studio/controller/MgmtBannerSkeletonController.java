package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.studio.MgmtBannerSkeletonService;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.BannerSkeletonForm;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The «Шаблони» screens: the looks a layout can be built from.
 *
 * <p>Nested under another controller's prefix, the shape {@code MgmtLanguageController} already has
 * beneath {@code /mgmt/pages}. The literal {@code skeletons} beats
 * {@code MgmtBannerLayoutController}'s {@code /{id}} on specificity -- and {@code skeletons} is a
 * reserved layout name for exactly that reason, so a layout cannot be created that would lose the
 * race and become unreachable.
 *
 * <p>Markup is edited in plain textareas, not TinyMCE. {@code mgmt.js} binds the editor to
 * {@code id="text"}, so these screens must not use that id: an editor that re-serialises HTML
 * through its schema would reformat a banner skeleton's markup on every open.
 */
@Slf4j
@StudioPermission(read = "ads.manage")
@Controller
@RequestMapping("/mgmt/banners/layouts/skeletons")
public class MgmtBannerSkeletonController {

    private final MgmtBannerSkeletonService skeletonService;
    private final MgmtText text;

    public MgmtBannerSkeletonController(MgmtBannerSkeletonService skeletonService, MgmtText text) {
        this.skeletonService = skeletonService;
        this.text = text;
    }

    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q, Model model) {
        model.addAttribute("skeletons", skeletonService.findAll(q));
        model.addAttribute("q", q);
        return "mgmt/banner-skeletons";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new BannerSkeletonForm());
        model.addAttribute("isNew", true);
        return "mgmt/banner-skeleton-edit";
    }

    /**
     * Fills the create form from an archive rather than saving one.
     *
     * <p>Deliberately not an import that writes a document: a skeleton is markup the operator is
     * about to read, and the reading is the step this screen exists for. It also means a refusal
     * from the parser arrives with the file still on screen to fix.
     */
    @PostMapping("/read")
    public String readArchive(@RequestParam("file") MultipartFile file, Model model) {
        model.addAttribute("isNew", true);
        try {
            model.addAttribute("form", skeletonService.readArchive(file));
            model.addAttribute("message", text.msg("banners.flash.skeletonRead"));
        } catch (MgmtOperationException e) {
            model.addAttribute("form", new BannerSkeletonForm());
            model.addAttribute("error", text.of(e));
        }
        return "mgmt/banner-skeleton-edit";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") BannerSkeletonForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {

        if (binding.hasErrors()) {
            model.addAttribute("isNew", true);
            return "mgmt/banner-skeleton-edit";
        }

        try {
            BannerSkeleton skeleton = skeletonService.create(form);
            flash.addFlashAttribute("message", text.msg("banners.flash.skeletonCreated"));
            return "redirect:/mgmt/banners/layouts/skeletons/" + skeleton.getId();

        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            model.addAttribute("isNew", true);
            return "mgmt/banner-skeleton-edit";
        }
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        BannerSkeleton skeleton = skeletonService.require(id);
        model.addAttribute("form", formOf(skeleton));
        addEditAttributes(model, skeleton);
        return "mgmt/banner-skeleton-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable String id,
                       @Valid @ModelAttribute("form") BannerSkeletonForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {

        // The path is the authority on which skeleton is being written; the field only has to
        // agree. Trusting the field would let a crafted POST edit a different skeleton.
        form.setId(id);

        if (binding.hasErrors()) {
            addEditAttributes(model, skeletonService.require(id));
            return "mgmt/banner-skeleton-edit";
        }

        try {
            skeletonService.save(id, form);
        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            addEditAttributes(model, skeletonService.require(id));
            return "mgmt/banner-skeleton-edit";
        }

        flash.addFlashAttribute("message", text.msg("banners.flash.skeletonSaved"));
        return "redirect:/mgmt/banners/layouts/skeletons/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes flash) {
        try {
            skeletonService.delete(id);
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/banners/layouts/skeletons/" + id;
        }

        flash.addFlashAttribute("message", text.msg("banners.flash.skeletonDeleted"));
        return "redirect:/mgmt/banners/layouts/skeletons";
    }

    private BannerSkeletonForm formOf(BannerSkeleton skeleton) {
        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setId(skeleton.getId());
        form.setTitle(skeleton.getTitle());
        form.setWidth(skeleton.getWidth());
        form.setHeight(skeleton.getHeight());
        form.setHtml(skeletonService.sourceOf(skeleton));
        form.setCss(skeleton.getCss());
        return form;
    }

    private void addEditAttributes(Model model, BannerSkeleton skeleton) {
        model.addAttribute("skeleton", skeleton);
        model.addAttribute("isNew", false);
    }
}
