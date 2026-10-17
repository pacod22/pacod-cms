package io.pacod.cms.studio.controller;

import io.pacod.cms.core.data.MenuLinkKind;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.ThemeToken;
import io.pacod.cms.studio.MgmtCategoryService;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.design.DesignLayouts;
import io.pacod.cms.studio.design.DesignMenus;
import io.pacod.cms.studio.design.DesignPreview;
import io.pacod.cms.studio.design.DesignTemplates;
import io.pacod.cms.studio.design.DesignText;
import io.pacod.cms.studio.design.DesignTheme;
import io.pacod.cms.studio.i18n.MgmtText;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A website's design: its layouts (one per page type), its theme, its template set and its menus.
 *
 * <p>Layouts and the theme are edited as drafts; {@code design.publish} puts a draft in front of
 * readers, and switching the template set needs it too. Menus have no draft. Every change is a
 * form POST and a redirect, except the editor's drag and drop ({@code arrange}), a {@code fetch}
 * that the page reloads after.
 */
@StudioPermission(read = "design.read", write = "design.write")
@Controller
@RequestMapping("/mgmt/design")
public class MgmtDesignController {

    private final DesignLayouts layouts;
    private final DesignTheme theme;
    private final DesignTemplates templates;
    private final DesignMenus menus;
    private final MgmtCategoryService categories;
    private final MgmtLanguageService languages;
    private final MgmtText text;
    private final DesignPreview preview;

    public MgmtDesignController(DesignLayouts layouts, DesignTheme theme, DesignTemplates templates, DesignMenus menus,
                                MgmtCategoryService categories, MgmtLanguageService languages, MgmtText text,
                                DesignPreview preview) {
        this.preview = preview;
        this.layouts = layouts;
        this.theme = theme;
        this.templates = templates;
        this.menus = menus;
        this.categories = categories;
        this.languages = languages;
        this.text = text;
    }

    private void common(Model model) {
        model.addAttribute("labels", new DesignText(text));
        model.addAttribute("set", layouts.currentSet());
        model.addAttribute("aiAvailable", ai != null && ai.availableHere());
    }

    private io.pacod.cms.studio.ai.AiAccess ai;
    private io.pacod.cms.studio.ai.DesignAssist assist;

    /** Design assist, where AI is available; optional so a context without it still starts. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useAi(io.pacod.cms.studio.ai.AiAccess ai, io.pacod.cms.studio.ai.DesignAssist assist) {
        this.ai = ai;
        this.assist = assist;
    }

    /** Runs design assist, said in a flash either way; nothing but the draft is ever touched. */
    private void assist(RedirectAttributes redirect, java.util.function.Supplier<io.pacod.cms.studio.ai.DesignAssist.Outcome> work,
                        String applied) {
        java.util.Optional<io.pacod.cms.studio.ai.AiRefusal> refusal = ai == null
                ? java.util.Optional.of(io.pacod.cms.studio.ai.AiRefusal.NOT_SET_UP) : ai.refusalHere();
        if (refusal.isPresent()) {
            redirect.addFlashAttribute("error", text.label(refusal.get()));
            return;
        }
        try {
            io.pacod.cms.studio.ai.DesignAssist.Outcome outcome = work.get();
            redirect.addFlashAttribute("message", outcome.refused().isEmpty()
                    ? text.msg(applied, outcome.applied())
                    : text.msg(applied, outcome.applied()) + " " + text.msg("design.assist.refused", String.join(", ", outcome.refused())));
        } catch (io.pacod.cms.studio.ai.AiGateway.AiException e) {
            redirect.addFlashAttribute("error", text.msg("ai.error.gateway", e.getMessage()));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
    }

    @GetMapping
    public String overview(Model model) {
        common(model);
        model.addAttribute("pageTypes", layouts.overview());
        model.addAttribute("theme", theme.load());
        return "mgmt/design";
    }

    // ---------------------------------------------------------------------------------- preview

    /** The frame widths the preview offers; anything else is the full width. */
    private static final List<String> WIDTHS = List.of("full", "tablet", "phone");

    /**
     * The website's drafts, framed from its own origin ({@link DesignPreview}). A page type to
     * look at, a template set to try ({@code ?set=}, from the template screen) and a width. A
     * {@code GET} that mints a short-lived token and writes nothing.
     */
    @GetMapping("/preview")
    public String preview(@RequestParam(required = false) String page, @RequestParam(required = false) String set,
                          @RequestParam(required = false) String width, Model model) {
        PageType type = page == null || page.isBlank() ? PageType.HOME : pageType(page);
        DesignPreview.View view = preview.view(type, set == null || set.isBlank() ? null : set);
        common(model);
        model.addAttribute("preview", view);
        model.addAttribute("pageTypes", PageType.values());
        model.addAttribute("pageTypeSlug", type.name().toLowerCase(Locale.ROOT));
        model.addAttribute("widths", WIDTHS);
        model.addAttribute("width", width != null && WIDTHS.contains(width) ? width : "full");
        model.addAttribute("previewSetName", view.set() == null ? null : preview.setName(view.set()));
        return "mgmt/design-preview";
    }

    // ---------------------------------------------------------------------------------- layouts

    private static PageType pageType(String value) {
        try {
            return PageType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MgmtNotFoundException("design.error.pageType", value);
        }
    }

    private static String back(PageType type) {
        return "redirect:/mgmt/design/layouts/" + type.name().toLowerCase(Locale.ROOT);
    }

    /** A layout draft for the page type from a description of the page (design assist). */
    @PostMapping("/layouts/{pageType}/assist")
    public String assistLayout(@PathVariable String pageType, @RequestParam(defaultValue = "") String brief,
                               RedirectAttributes redirect) {
        PageType type = pageType(pageType);
        assist(redirect, () -> assist.layout(type, brief), "design.assist.layout.applied");
        return back(type);
    }

    @GetMapping("/layouts/{pageType}")
    public String layout(@PathVariable String pageType, Model model) {
        PageType type = pageType(pageType);
        common(model);
        model.addAttribute("editor", layouts.editor(type));
        model.addAttribute("categories", categories.findAll(null, null, null, Map.of()));
        model.addAttribute("pageTypeSlug", type.name().toLowerCase(Locale.ROOT));
        return "mgmt/design-layout";
    }

    @PostMapping("/layouts/{pageType}/sections")
    public String addSection(@PathVariable String pageType, @RequestParam String region, @RequestParam String type,
                             RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        try {
            var section = layouts.add(page, region, type);
            redirect.addFlashAttribute("message", text.msg("design.flash.added"));
            redirect.addFlashAttribute("openSection", section.getId());
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back(page);
    }

    @PostMapping("/layouts/{pageType}/sections/{sectionId}")
    public String saveSection(@PathVariable String pageType, @PathVariable String sectionId,
                              HttpServletRequest request, RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        Map<String, String> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, v) -> {
            if (name.startsWith("f_") && v.length > 0) {
                values.put(name.substring(2), v[0]);
            }
        });
        try {
            layouts.update(page, sectionId, values);
            redirect.addFlashAttribute("message", text.msg("design.flash.saved"));
        } catch (DesignLayouts.SettingsRefused e) {
            redirect.addFlashAttribute("error", text.msg("design.error.settings", describe(e.errors())));
            redirect.addFlashAttribute("openSection", sectionId);
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back(page);
    }

    /** {@code required:heading, range:count} as words: "Heading: required; Count: out of range". */
    private String describe(List<String> errors) {
        DesignText labels = new DesignText(text);
        return String.join("; ", errors.stream().map(e -> {
            String[] parts = e.split(":", 2);
            return labels.field(parts.length > 1 ? parts[1] : e) + ": " + text.msg("design.refused." + parts[0]);
        }).toList());
    }

    @PostMapping("/layouts/{pageType}/sections/{sectionId}/move")
    public String move(@PathVariable String pageType, @PathVariable String sectionId, @RequestParam String dir,
                       RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        try {
            layouts.move(page, sectionId, "up".equals(dir) ? -1 : 1);
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back(page);
    }

    @PostMapping("/layouts/{pageType}/sections/{sectionId}/region")
    public String moveTo(@PathVariable String pageType, @PathVariable String sectionId, @RequestParam String region,
                         RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        try {
            layouts.moveTo(page, sectionId, region);
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back(page);
    }

    @PostMapping("/layouts/{pageType}/sections/{sectionId}/remove")
    public String remove(@PathVariable String pageType, @PathVariable String sectionId, RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        try {
            layouts.remove(page, sectionId);
            redirect.addFlashAttribute("message", text.msg("design.flash.removed"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back(page);
    }

    /** The editor's drag and drop: each region's section ids, in order. */
    @PostMapping(value = "/layouts/{pageType}/arrange", consumes = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, String>> arrange(@PathVariable String pageType,
                                                       @RequestBody Map<String, List<String>> order) {
        PageType page = pageType(pageType);
        try {
            layouts.arrange(page, order);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (MgmtOperationException e) {
            return ResponseEntity.status(409).body(Map.of("status", "refused", "message", text.of(e)));
        }
    }

    @PostMapping("/layouts/{pageType}/publish")
    @StudioPermission(read = "design.read", write = "design.publish")
    public String publish(@PathVariable String pageType, RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        layouts.publish(page);
        redirect.addFlashAttribute("message", text.msg("design.flash.published"));
        return back(page);
    }

    @PostMapping("/layouts/{pageType}/discard")
    public String discard(@PathVariable String pageType, RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        layouts.discard(page);
        redirect.addFlashAttribute("message", text.msg("design.flash.discarded"));
        return back(page);
    }

    @PostMapping("/layouts/{pageType}/reset")
    public String reset(@PathVariable String pageType, RedirectAttributes redirect) {
        PageType page = pageType(pageType);
        layouts.reset(page);
        redirect.addFlashAttribute("message", text.msg("design.flash.reset"));
        return back(page);
    }

    // ---------------------------------------------------------------------------------- theme

    @GetMapping("/theme")
    public String theme(Model model) {
        common(model);
        model.addAttribute("theme", theme.load());
        model.addAttribute("presets", theme.presets().values());
        model.addAttribute("tokens", Arrays.asList(ThemeToken.values()));
        model.addAttribute("fonts", ThemeToken.FONTS.keySet().stream().sorted().toList());
        model.addAttribute("swatchTokens", List.of("bg", "ink", "accent", "highlight"));
        return "mgmt/design-theme";
    }

    @PostMapping("/theme")
    public String saveTheme(HttpServletRequest request, @RequestParam(required = false) String css,
                            RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, v) -> {
            if (name.startsWith("t_") && v.length > 0) {
                values.put(name.substring(2), v[0]);
            }
        });
        try {
            theme.saveDraft(values, css);
            redirect.addFlashAttribute("message", text.msg("design.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design/theme";
    }

    /** A theme draft from a description of the look (design assist). */
    @PostMapping("/theme/assist")
    public String assistTheme(@RequestParam(defaultValue = "") String brief, RedirectAttributes redirect) {
        assist(redirect, () -> assist.theme(brief), "design.assist.theme.applied");
        return "redirect:/mgmt/design/theme";
    }

    @PostMapping("/theme/preset")
    public String preset(@RequestParam String preset, RedirectAttributes redirect) {
        try {
            theme.applyPreset(preset);
            redirect.addFlashAttribute("message", text.msg("design.theme.flash.preset"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design/theme";
    }

    @PostMapping("/theme/publish")
    @StudioPermission(read = "design.read", write = "design.publish")
    public String publishTheme(RedirectAttributes redirect) {
        theme.publish();
        redirect.addFlashAttribute("message", text.msg("design.flash.published"));
        return "redirect:/mgmt/design/theme";
    }

    @PostMapping("/theme/discard")
    public String discardTheme(RedirectAttributes redirect) {
        theme.discard();
        redirect.addFlashAttribute("message", text.msg("design.flash.discarded"));
        return "redirect:/mgmt/design/theme";
    }

    // ---------------------------------------------------------------------------------- template set

    @GetMapping("/template")
    public String template(@RequestParam(required = false) String set, Model model) {
        common(model);
        model.addAttribute("sets", templates.sets());
        if (set != null && !set.isBlank()) {
            model.addAttribute("candidate", set);
            List<DesignTemplates.PageReport> report = templates.report(set);
            model.addAttribute("report", report);
            model.addAttribute("reportClean", report.stream().allMatch(DesignTemplates.PageReport::clean));
        }
        return "mgmt/design-template";
    }

    @PostMapping("/template")
    @StudioPermission(read = "design.read", write = "design.publish")
    public String switchTemplate(@RequestParam String set, RedirectAttributes redirect) {
        try {
            templates.apply(set);
            redirect.addFlashAttribute("message", text.msg("design.template.flash.switched"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design";
    }

    // ---------------------------------------------------------------------------------- menus

    @GetMapping("/menus/{key}")
    public String menu(@PathVariable String key, Model model) {
        common(model);
        model.addAttribute("menu", menus.load(key));
        model.addAttribute("menuKey", key);
        model.addAttribute("menuKeys", DesignMenus.KEYS);
        model.addAttribute("kinds", Arrays.asList(MenuLinkKind.values()));
        model.addAttribute("languages", languages.findAll());
        return "mgmt/design-menu";
    }

    private static DesignMenus.ItemForm itemForm(HttpServletRequest request) {
        Map<String, String> labels = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, v) -> {
            if (name.startsWith("label_") && v.length > 0) {
                labels.put(name.substring(6), v[0]);
            }
        });
        MenuLinkKind kind;
        try {
            kind = MenuLinkKind.valueOf(String.valueOf(request.getParameter("kind")));
        } catch (IllegalArgumentException e) {
            kind = MenuLinkKind.NONE;
        }
        return new DesignMenus.ItemForm(labels, kind, request.getParameter("target"));
    }

    @PostMapping("/menus/{key}/items")
    public String addItem(@PathVariable String key, @RequestParam(required = false) String parent,
                          HttpServletRequest request, RedirectAttributes redirect) {
        try {
            menus.add(key, parent, itemForm(request));
            redirect.addFlashAttribute("message", text.msg("design.flash.added"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design/menus/" + key;
    }

    @PostMapping("/menus/{key}/items/{position}")
    public String saveItem(@PathVariable String key, @PathVariable String position,
                           HttpServletRequest request, RedirectAttributes redirect) {
        try {
            menus.update(key, position, itemForm(request));
            redirect.addFlashAttribute("message", text.msg("design.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design/menus/" + key;
    }

    @PostMapping("/menus/{key}/items/{position}/move")
    public String moveItem(@PathVariable String key, @PathVariable String position, @RequestParam String dir,
                           RedirectAttributes redirect) {
        try {
            menus.move(key, position, "up".equals(dir) ? -1 : 1);
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design/menus/" + key;
    }

    @PostMapping("/menus/{key}/items/{position}/remove")
    public String removeItem(@PathVariable String key, @PathVariable String position, RedirectAttributes redirect) {
        try {
            menus.remove(key, position);
            redirect.addFlashAttribute("message", text.msg("design.flash.removed"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/design/menus/" + key;
    }
}
