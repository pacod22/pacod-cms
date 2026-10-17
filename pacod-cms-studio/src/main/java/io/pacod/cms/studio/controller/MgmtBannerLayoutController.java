package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.studio.MgmtBannerLayoutService;
import io.pacod.cms.studio.MgmtBannerSkeletonService;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.BannerLayoutForm;
import io.pacod.cms.studio.form.BannerSlideForm;
import io.pacod.cms.studio.i18n.MgmtText;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The «Макети» screens: the folders behind the site's HTML5 bookings.
 *
 * <h2>Why the route is nested under {@code /mgmt/banners/}</h2>
 *
 * <p>Not for tidiness. Anything on a new prefix would fall outside {@code MgmtSecurityConfig}'s
 * authenticated matcher, into the permit-all chain, handing every one of these routes to anyone who
 * asked -- and outside the website screens {@code StudioPaths} files under {@code /sites/<id>}.
 * Under {@code /mgmt/banners/} there is no security or routing change to make.
 *
 * <p>The literal {@code layouts} beats {@code MgmtBannerController}'s {@code /{id}} on Spring's
 * pattern specificity, the same ordering {@code /mgmt/pages/languages} and {@code /mgmt/images/search}
 * already rely on. Losing that race would not fail loudly: it would 404 with a message about a
 * missing banner number, which is why {@code MgmtSecurityTest} pins each route separately.
 *
 * <p>Must stay in this package -- {@code MgmtControllerAdvice} is scoped to it, and a controller
 * outside loses {@code currentUser} under a {@code th:if} with nothing on screen to say so.
 */
@Slf4j
@StudioPermission(read = "ads.manage")
@Controller
@RequestMapping("/mgmt/banners/layouts")
public class MgmtBannerLayoutController {

    private final MgmtBannerLayoutService layoutService;
    private final MgmtBannerSkeletonService skeletonService;
    private final MgmtText text;

    public MgmtBannerLayoutController(MgmtBannerLayoutService layoutService,
                                      MgmtBannerSkeletonService skeletonService,
                                      MgmtText text) {
        this.layoutService = layoutService;
        this.skeletonService = skeletonService;
        this.text = text;
    }

    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q, Model model) {
        model.addAttribute("layouts", layoutService.findAll(q));
        // Folders sitting in the banner tree that no document describes. Without this the
        // screens describe only what the console itself created, and a folder copied in by hand
        // is unreachable.
        model.addAttribute("orphans", layoutService.discover());
        // Echoed back so the box keeps what was typed, and so the empty state can tell "no layouts
        // at all" apart from "none match".
        model.addAttribute("q", q);
        return "mgmt/banner-layouts";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("skeletons", skeletonService.findAll());
        return "mgmt/banner-layout-new";
    }

    /**
     * Creates the document and writes the folder.
     *
     * <p>Plain {@code @RequestParam}s rather than a bound form, the shape {@code page-new.html}
     * uses: the three values here are not fields of the layout being edited, they are the decision
     * of which layout to start.
     */
    @PostMapping
    public String create(@RequestParam(name = "id", required = false) String id,
                         @RequestParam(name = "title", required = false) String title,
                         @RequestParam(name = "skeleton", required = false) String skeleton,
                         Model model,
                         RedirectAttributes flash) {
        try {
            BannerLayout layout = layoutService.create(id, title, skeleton);
            flash.addFlashAttribute("message", text.msg("banners.flash.layoutCreated"));
            return "redirect:/mgmt/banners/layouts/" + layout.getId();

        } catch (MgmtOperationException e) {
            // Re-render rather than redirect, so what was typed survives the refusal.
            model.addAttribute("error", text.of(e));
            model.addAttribute("id", id);
            model.addAttribute("title", title);
            model.addAttribute("skeleton", skeleton);
            model.addAttribute("skeletons", skeletonService.findAll());
            return "mgmt/banner-layout-new";
        }
    }

    /**
     * Registers an archive of a folder that is already finished.
     *
     * <p>The route's parent is the list, which is where {@code UploadSizeAdvice} sends its flash
     * when an upload is larger than the multipart limit -- so an oversize archive lands on a screen
     * that can say what happened rather than on nothing.
     */
    @PostMapping("/import")
    public String importArchive(@RequestParam(name = "id", required = false) String id,
                                @RequestParam(name = "title", required = false) String title,
                                @RequestParam("file") MultipartFile file,
                                RedirectAttributes flash) {
        try {
            BannerLayout layout = layoutService.importArchive(id, title, file);
            flash.addFlashAttribute("message", text.msg("banners.flash.layoutImported"));
            return "redirect:/mgmt/banners/layouts/" + layout.getId();

        } catch (MgmtOperationException e) {
            log.warn("Banner layout import refused: {}", e.getMessage());
            flash.addFlashAttribute("error", text.msg("banners.flash.archiveRejected", text.of(e)));
            return "redirect:/mgmt/banners/layouts";
        }
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        BannerLayout layout = layoutService.require(id);
        model.addAttribute("form", BannerLayoutForm.of(layout));
        addEditAttributes(model, layout);
        return "mgmt/banner-layout-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable String id,
                       @Valid @ModelAttribute("form") BannerLayoutForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {

        if (binding.hasErrors()) {
            return rerender(model, id, form);
        }

        try {
            layoutService.save(id, form);
        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            return rerender(model, id, form);
        }

        flash.addFlashAttribute("message", text.msg("banners.flash.layoutSaved"));
        return "redirect:/mgmt/banners/layouts/" + id;
    }

    /** One file at a time: {@code spring.servlet.multipart.max-request-size} bounds the whole POST. */
    @PostMapping("/{id}/slides")
    public String addSlide(@PathVariable String id,
                           @RequestParam("file") MultipartFile file,
                           RedirectAttributes flash) {
        try {
            layoutService.addSlide(id, file);
            flash.addFlashAttribute("message", text.msg("banners.flash.slideAdded"));
        } catch (MediaException e) {
            log.warn("Slide upload for layout {} refused: {}", id, e.getMessage());
            flash.addFlashAttribute("error", text.msg("banners.flash.fileRejected", text.of(e)));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/banners/layouts/" + id;
    }

    /**
     * {@link #addSlide} answered with the new row instead of a redirect.
     *
     * <p>What {@code mgmt.js} posts when scripting is on. The builder holds unsaved links, captions
     * and dimensions, and a redirect would throw every one of them away to add a picture -- the
     * loss the article editor's {@code /images/upload} exists to prevent, here on a smaller screen.
     *
     * <p>The answer is the row <em>and</em> its four out-of-line action forms, rendered from the same
     * fragments as the page, so a row added without a reload binds {@code slides[N]} exactly as a
     * rendered one does -- and the next «Запази» posts the count {@code applySlides} checks for.
     *
     * <p>{@code ModelAndView} on both branches, never {@code Object}: no return-value handler claims
     * a declared {@code Object}, which is {@code UploadSizeAdvice}'s lesson. A refusal is the
     * {@code slide-refused} fragment at 409, which the script reads the reason out of.
     */
    @PostMapping("/{id}/slides/upload")
    public ModelAndView uploadSlide(@PathVariable String id,
                                    @RequestParam("file") MultipartFile file) {
        try {
            BannerLayout layout = layoutService.addSlide(id, file);
            ModelAndView view = new ModelAndView("mgmt/banner-layout-edit :: slide-added");
            view.addObject("layout", layout);
            view.addObject("imagePrefix", layout.publicFolder());
            view.addObject("form", BannerLayoutForm.of(layout));
            view.addObject("addedIndex", layout.slideCount() - 1);
            return view;

        } catch (MediaException e) {
            log.warn("Slide upload for layout {} refused: {}", id, e.getMessage());
            return refused(text.msg("banners.flash.fileRejected", text.of(e)));
        } catch (MgmtOperationException e) {
            return refused(text.of(e));
        }
    }

    private ModelAndView refused(String reason) {
        ModelAndView view = new ModelAndView("mgmt/banner-layout-edit :: slide-refused");
        view.addObject("error", reason);
        view.setStatus(HttpStatus.CONFLICT);
        return view;
    }

    /**
     * Swaps the picture on one frame, keeping its link and its position.
     *
     * <p>The action an advertiser sending a replacement creative mid-campaign actually needs.
     * Remove-and-re-add would cost the URL and the ordering that go with it.
     */
    @PostMapping("/{id}/slides/{index}/image")
    public String replaceSlideImage(@PathVariable String id,
                                    @PathVariable int index,
                                    @RequestParam("file") MultipartFile file,
                                    RedirectAttributes flash) {
        try {
            layoutService.replaceSlideImage(id, index, file);
            flash.addFlashAttribute("message", text.msg("banners.flash.slideImageReplaced"));
        } catch (MediaException e) {
            log.warn("Slide image replacement for layout {} refused: {}", id, e.getMessage());
            flash.addFlashAttribute("error", text.msg("banners.flash.fileRejected", text.of(e)));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/banners/layouts/" + id;
    }

    /**
     * {@link #replaceSlideImage} answered with the frame's new picture instead of a redirect.
     *
     * <p>What clicking or dropping onto a thumbnail posts. The filename changes on purpose -- see
     * {@code MgmtBannerLayoutService.replaceSlideImage} -- so the answer carries it, and the screen
     * repaints both the {@code <img>} and the name under it.
     */
    @PostMapping("/{id}/slides/{index}/image/upload")
    @ResponseBody
    public ResponseEntity<?> uploadSlideImage(@PathVariable String id,
                                              @PathVariable int index,
                                              @RequestParam("file") MultipartFile file) {
        try {
            BannerLayout layout = layoutService.replaceSlideImage(id, index, file);
            String image = layout.getSlides().get(index).getImage();
            return ResponseEntity.ok(Map.of(
                    "index", index,
                    "image", image,
                    "src", layout.publicFolder() + image));

        } catch (MediaException e) {
            log.warn("Slide image replacement for layout {} refused: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", text.msg("banners.flash.fileRejected", text.of(e))));
        } catch (MgmtOperationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", text.of(e)));
        }
    }

    @PostMapping("/{id}/slides/{index}/delete")
    public String removeSlide(@PathVariable String id,
                              @PathVariable int index,
                              RedirectAttributes flash) {
        try {
            layoutService.removeSlide(id, index);
            flash.addFlashAttribute("message", text.msg("banners.flash.slideRemoved"));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/banners/layouts/" + id;
    }

    /**
     * Moves a slide one place.
     *
     * <p>A form POST answered by a redirect, unlike the two uploads above. The builder does hold
     * unsaved links and captions a redirect discards, but a reorder is a deliberate structural edit
     * made between saves, while an upload is what an operator reaches for mid-edit -- so the uploads
     * use {@code fetch} and this does not.
     */
    @PostMapping("/{id}/slides/{index}/move")
    public String moveSlide(@PathVariable String id,
                            @PathVariable int index,
                            @RequestParam("delta") int delta,
                            RedirectAttributes flash) {
        try {
            layoutService.moveSlide(id, index, delta < 0 ? -1 : 1);
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/banners/layouts/" + id;
    }

    /**
     * The file editor.
     *
     * <p>{@code ?file=} rather than a path segment, for the reason {@code MgmtPageController} uses
     * {@code ?lang=}: a file path has slashes in it, and putting it in the URL would both compete
     * with the catch-all on specificity and need encoding at every link.
     */
    @GetMapping("/{id}/files")
    public String files(@PathVariable String id,
                        @RequestParam(name = "file", required = false) String path,
                        Model model) {

        BannerLayout layout = layoutService.require(id);
        addEditAttributes(model, layout);
        // Only this screen consumes it, and it walks the folder -- on the builder that would be a
        // filesystem walk nothing reads.
        model.addAttribute("fileList", layoutService.listFiles(id));
        model.addAttribute("editing", path);
        model.addAttribute("content", path == null ? null : layoutService.fileText(id, path));
        return "mgmt/banner-layout-files";
    }

    @PostMapping("/{id}/files")
    public String saveFile(@PathVariable String id,
                           @RequestParam("file") String path,
                           @RequestParam("content") String content,
                           RedirectAttributes flash) {
        try {
            layoutService.saveFile(id, path, content);
            flash.addFlashAttribute("message", text.msg("banners.flash.fileWritten"));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/banners/layouts/" + id + "/files?file="
                + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    @PostMapping("/{id}/files/replace")
    public String replaceFile(@PathVariable String id,
                              @RequestParam("path") String path,
                              @RequestParam("file") MultipartFile file,
                              RedirectAttributes flash) {
        try {
            layoutService.replaceFile(id, path, file);
            flash.addFlashAttribute("message", text.msg("banners.flash.fileReplaced"));
        } catch (MediaException e) {
            log.warn("File replacement in layout {} refused: {}", id, e.getMessage());
            flash.addFlashAttribute("error", text.msg("banners.flash.fileRejected", text.of(e)));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/banners/layouts/" + id + "/files";
    }

    /**
     * Takes a folder that is already in the banner tree under console management.
     *
     * <p>The one route that makes a folder the console did not create reachable at all -- see
     * {@code MgmtBannerLayoutService.adopt} for what decides whether it becomes editable or stays
     * verbatim.
     */
    @PostMapping("/adopt")
    public String adopt(@RequestParam("folder") String folder, RedirectAttributes flash) {
        try {
            BannerLayout layout = layoutService.adopt(folder);
            flash.addFlashAttribute("message", layout.isBuilt()
                    ? text.msg("banners.flash.adoptedBuilt")
                    : text.msg("banners.flash.adoptedImported"));
            return "redirect:/mgmt/banners/layouts/" + layout.getId();

        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/banners/layouts";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes flash) {
        try {
            layoutService.delete(id);
        } catch (MgmtOperationException e) {
            // A refusal names the bookings, and is only actionable on the screen that lists them.
            flash.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/banners/layouts/" + id;
        }

        flash.addFlashAttribute("message", text.msg("banners.flash.layoutDeleted"));
        return "redirect:/mgmt/banners/layouts";
    }

    /**
     * Re-renders the builder after a refusal, keeping what the operator typed.
     *
     * <p>One thing has to be reconciled first. The template iterates the <em>document's</em> slides
     * for the thumbnails and binds {@code *{slides[i]}} against the <em>form</em>, so a refusal
     * caused by the two disagreeing about how many slides there are -- a frame uploaded or removed
     * in another tab -- renders a form shorter or longer than the rows around it. Spring's binder
     * auto-grows the short case into blank rows rather than failing, which is worse than either
     * outcome: the operator is told to reload while looking at fields that were never theirs.
     *
     * <p>So the list is squared up against the document here: values the operator typed survive at
     * every position that still exists, and a position that no longer does is dropped rather than
     * invented. Every other refusal on this screen -- a bad link, half a dimension -- keeps the form
     * exactly as posted, which is the convention the rest of the console follows.
     */
    private String rerender(Model model, String id, BannerLayoutForm form) {

        BannerLayout layout = layoutService.require(id);

        List<BannerSlideForm> slides = form.getSlides() == null
                ? new ArrayList<>()
                : new ArrayList<>(form.getSlides());
        while (slides.size() > layout.slideCount()) {
            slides.removeLast();
        }
        while (slides.size() < layout.slideCount()) {
            slides.add(BannerSlideForm.of(layout.getSlides().get(slides.size())));
        }
        form.setSlides(slides);

        addEditAttributes(model, layout);
        return "mgmt/banner-layout-edit";
    }

    /**
     * Everything the edit template reads besides {@code form}.
     *
     * <p>The stored document, the files on disk and the bookings are all shown and none is bound,
     * so none of them is reachable through {@code ${form}} -- and the error branches re-render
     * rather than redirect, which is why they all have to be put back by hand.
     */
    private void addEditAttributes(Model model, BannerLayout layout) {
        model.addAttribute("layout", layout);
        model.addAttribute("files", layoutService.files(layout.getId()));
        model.addAttribute("bookings", layoutService.bookingsUsing(layout.getId()));
        model.addAttribute("imagePrefix", layout.publicFolder());
        if (layout.isBuilt()) {
            model.addAttribute("skeleton", skeletonService.find(layout.getSkeleton()).orElse(null));
        }
    }
}
