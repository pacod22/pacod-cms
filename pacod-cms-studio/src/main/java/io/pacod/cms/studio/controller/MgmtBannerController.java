package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.studio.MgmtBannerLayoutService;
import io.pacod.cms.studio.MgmtBannerService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.BannerForm;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * Banner administration.
 *
 * <p>Six routes in the standard console shape; the seventh, {@link #uploadCreative}, mirrors
 * {@code MgmtArticleController.addImage}.
 *
 * <p>Must stay in this package: {@code MgmtControllerAdvice} is scoped to
 * {@code io.pacod.cms.studio.controller}, and a controller outside it loses
 * {@code currentUser} -- which the topbar renders under a {@code th:if}, so the failure is
 * silent.
 */
@Slf4j
@StudioPermission(read = "ads.manage")
@Controller
@RequestMapping("/mgmt/banners")
public class MgmtBannerController {

    private final MgmtBannerService bannerService;
    private final MgmtBannerLayoutService layoutService;
    private final MgmtText text;

    private io.pacod.cms.core.repository.CategoryRepository categories;

    /** The sections a booking may name; optional, so the screen's tests that book none need none. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useCategories(io.pacod.cms.core.repository.CategoryRepository categories) {
        this.categories = categories;
    }

    public MgmtBannerController(MgmtBannerService bannerService,
                                MgmtBannerLayoutService layoutService,
                                MgmtText text) {
        this.bannerService = bannerService;
        this.layoutService = layoutService;
        this.text = text;
    }

    /** The search term is a GET parameter, so a filtered list is a bookmarkable URL. */
    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q, Model model) {
        model.addAttribute("banners", bannerService.findAll(q));
        // The list shows each row's live verdict -- running / scheduled / expired / off -- and the
        // clock has to come from somewhere the template can compare against.
        model.addAttribute("now", LocalDateTime.now());
        // Echoed back so the box keeps what was typed, and so the empty state can tell
        // "no banners at all" apart from "none match".
        model.addAttribute("q", q);
        return "mgmt/banners";
    }

    /**
     * The create form, optionally pre-filled from a layout.
     *
     * <p>{@code ?layout=} is the hand-off from «Макети». It fills the three fields a layout knows
     * the answer to and nothing else: the operator still names the booking, chooses its pages and
     * sets its period, because those are decisions about a campaign rather than facts about a
     * folder. An unknown id is ignored rather than 404ing -- the screen is still usable, and the
     * empty source field says as much as an error page would.
     *
     * <p>The size is derived and is <b>never</b> {@code AUTO}; see {@code MgmtBannerService.validate}
     * for what that would render. A layout whose dimensions match no known frame leaves the field
     * unset for the operator to pick, rather than being guessed at.
     */
    @GetMapping("/new")
    public String createForm(@RequestParam(name = "layout", required = false) String layoutId,
                             Model model) {
        BannerForm form = new BannerForm();

        layoutService.find(layoutId).ifPresent(layout -> {
            form.setType(BannerType.HTML5);
            form.setSource(layout.publicPath());
            form.setName(layout.getTitle());
            form.setPlacement(layoutService.suggestedPlacement(layout));
            layoutService.suggestedSize(layout).ifPresent(form::setSize);
            model.addAttribute("fromLayout", layout);
        });

        model.addAttribute("form", form);
        model.addAttribute("isNew", true);
        addChoices(model);
        return "mgmt/banner-edit";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") BannerForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {

        if (binding.hasErrors()) {
            return rerenderNew(model);
        }

        try {
            Banner banner = bannerService.create(form);
            flash.addFlashAttribute("message", text.msg("banners.flash.created"));
            return "redirect:/mgmt/banners/" + banner.getId();

        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            return rerenderNew(model);
        }
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable Integer id, Model model) {
        model.addAttribute("form", BannerForm.of(require(id)));
        model.addAttribute("isNew", false);
        addEditAttributes(model, id);
        return "mgmt/banner-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable Integer id,
                       @Valid @ModelAttribute("form") BannerForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {

        if (binding.hasErrors()) {
            return rerenderEdit(model, id);
        }

        try {
            bannerService.save(id, form);
        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            return rerenderEdit(model, id);
        }

        flash.addFlashAttribute("message", text.msg("banners.flash.saved"));
        return "redirect:/mgmt/banners/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id, RedirectAttributes flash) {
        bannerService.delete(id);

        flash.addFlashAttribute("message", text.msg("banners.flash.deleted"));
        return "redirect:/mgmt/banners";
    }

    /**
     * Uploads a creative for an image banner.
     *
     * <p>Separate from the main form so the file is durable the moment it is accepted, exactly as
     * article images are. The parameter is {@code file}; see {@code MgmtArticleController}.
     */
    @PostMapping("/{id}/creative")
    public String uploadCreative(@PathVariable Integer id,
                                 @RequestParam("file") MultipartFile file,
                                 RedirectAttributes flash) {
        try {
            bannerService.uploadCreative(id, file);
            flash.addFlashAttribute("message", text.msg("banners.flash.uploaded"));
        } catch (MediaException e) {
            log.warn("Banner creative upload for banner {} refused: {}", id, e.getMessage());
            flash.addFlashAttribute("error", text.msg("banners.flash.fileRejected", text.of(e)));
        }
        return "redirect:/mgmt/banners/" + id;
    }

    /**
     * Re-adds everything the edit template reads besides {@code form}.
     *
     * <p>The error branches go through this rather than redirecting, so the operator keeps what
     * they typed -- which is also why every one of these attributes has to be put back by hand.
     */
    private String rerenderNew(Model model) {
        model.addAttribute("isNew", true);
        addChoices(model);
        return "mgmt/banner-edit";
    }

    private String rerenderEdit(Model model, Integer id) {
        model.addAttribute("isNew", false);
        addEditAttributes(model, id);
        return "mgmt/banner-edit";
    }

    private void addEditAttributes(Model model, Integer id) {
        // The stored document, not the form: the counters and the current creative are shown on
        // this screen but deliberately not bound, so they are unavailable through ${form}.
        Banner banner = require(id);
        model.addAttribute("banner", banner);
        model.addAttribute("now", LocalDateTime.now());
        // Null unless the source names a folder the console owns. The link is what stops an
        // operator editing the booking's path by hand when the layout screen would do it for them.
        model.addAttribute("layout", layoutService.layoutOf(banner.getSource()).orElse(null));
        addChoices(model);
    }

    private void addChoices(Model model) {
        model.addAttribute("types", BannerType.values());
        model.addAttribute("placements", BannerPlacement.values());
        model.addAttribute("sizes", BannerSize.values());
        // Bookable pages only: /social renders no ad columns, so a booking naming it would run
        // nowhere. See BannerPage.SOCIAL.
        model.addAttribute("pages", BannerPage.bookable());
        // The website's sections, for booking a banner on their articles and lists only. A read
        // that fails costs the choice, not the screen: the booking keeps the sections it has.
        List<io.pacod.cms.core.data.Category> sections = List.of();
        if (categories != null) {
            try {
                sections = categories.findAll(org.springframework.data.domain.Sort.by("id"));
            } catch (org.springframework.dao.DataAccessException e) {
                log.warn("Could not read the sections; the banner form offers none: {}", e.getMessage());
            }
        }
        model.addAttribute("categories", sections);
    }

    private Banner require(Integer id) {
        return bannerService.find(id).orElseThrow(
                () -> new MgmtNotFoundException("banners.error.noBanner.id", id));
    }
}
