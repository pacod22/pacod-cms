package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLink;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.MgmtSocialService;
import io.pacod.cms.studio.MgmtSocialStatsService;
import io.pacod.cms.studio.MonitoringRange;
import io.pacod.cms.studio.form.SocialLinkForm;
import io.pacod.cms.studio.form.SocialPageForm;
import io.pacod.cms.studio.i18n.MgmtText;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code /mgmt/social}: the link-in-bio page's profile and links, and its statistics.
 *
 * <p>Everything is under {@code /mgmt/}, so it sits inside {@code MgmtSecurityConfig}'s
 * authenticated matcher; a new prefix would fall into the permit-all chain instead.
 * {@code /links} and {@code /stats} are literal segments with no {@code /{id}} competing at their
 * level. {@code MgmtSecurityTest} pins every route.
 *
 * <p>Languages work the way {@code MgmtPageController}'s do: one language per screen, chosen by a
 * {@code ?lang} link, each save writing that language's texts and the shared fields -- never one
 * form carrying every language, where a field that failed to render would post empty and delete a
 * translation.
 */
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/social")
public class MgmtSocialController {

    /**
     * The header mgmt.js sets when it posts one of the link cards' forms. The same URL then
     * answers with the card instead of a redirect -- a narrower mapping, which Spring prefers, so
     * every action keeps a single URL and the no-script half is the form's own {@code action}.
     */
    static final String CARD_HEADER = "X-Mgmt-Fragment";
    static final String CARD_VALUE = "social-links";

    private final MgmtSocialService socialService;
    private final MgmtSocialStatsService statsService;
    private final MgmtLanguageService languageService;
    private final MgmtText text;
    private final io.pacod.cms.studio.access.StudioAccess access;

    public MgmtSocialController(MgmtSocialService socialService, MgmtSocialStatsService statsService,
                                MgmtLanguageService languageService, MgmtText text,
                                io.pacod.cms.studio.access.StudioAccess access) {
        this.socialService = socialService;
        this.statsService = statsService;
        this.languageService = languageService;
        this.text = text;
        this.access = access;
    }

    /** A source the operator can tag a bio address with, and that address. */
    public record TaggedUrl(SocialSource source, String url) {
    }

    /* ---------------------------------------------------------------- the profile */

    @GetMapping
    public String edit(@RequestParam(name = "lang", required = false) String lang, Model model) {
        SocialPage page = socialService.load();
        Language language = languageOrOriginal(lang);
        model.addAttribute("form", SocialPageForm.of(page, language.getCode()));
        addEditAttributes(model, page, language);
        return "mgmt/social";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") SocialPageForm form, BindingResult binding,
                       Model model, RedirectAttributes redirect) {
        Language language = languageOrOriginal(form.getLang());
        form.setLang(language.getCode());

        if (binding.hasErrors()) {
            addEditAttributes(model, socialService.load(), language);
            return "mgmt/social";
        }
        try {
            socialService.saveProfile(form, access.allowed("content.publish"));
        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            addEditAttributes(model, socialService.load(), language);
            return "mgmt/social";
        }
        redirect.addFlashAttribute("message", text.msg("pages.social.flash.saved", language.displayName()));
        return "redirect:/mgmt/social?lang=" + language.getCode();
    }

    /* ---------------------------------------------------------------- the links */

    /**
     * Adding a link, from the small form inside each link card on {@code /mgmt/social} -- the
     * no-script half. With scripting on, mgmt.js posts the same form to the same URL with
     * {@link #CARD_HEADER} and {@link #createLinkInCard} answers instead, so nothing reloads.
     *
     * <p>Either way the operator stays on {@code /mgmt/social}. Adding is not a screen of its own:
     * leaving the page that holds the profile form is a navigation, so an unsaved bio would raise
     * the browser's "leave this page?" prompt, and declining it would look like a button that did
     * nothing. Translations, the schedule and switching off are edited on the link's own screen
     * once it exists.
     */
    @PostMapping("/links")
    public String createLink(@Valid @ModelAttribute("addForm") SocialLinkForm form, BindingResult binding,
                             @RequestParam(name = "tab", required = false) String tab,
                             RedirectAttributes redirect) {
        String back = "redirect:/mgmt/social" + langQuery(tab) + "#links-" + placementOf(form);
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", firstError(binding));
            return back;
        }
        try {
            socialService.createLink(form);
            redirect.addFlashAttribute("message", text.msg("pages.social.flash.linkAdded"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back;
    }

    /**
     * The same, posted by mgmt.js: answers with the re-rendered card rather than a redirect, so
     * the page -- and whatever is typed, unsaved, in the profile form above -- stays where it is.
     * A refusal is the card again at 422, with the add form open, the typed values in it and the
     * reason above them. {@code ModelAndView}, never {@code Object}: {@code UploadSizeAdvice}'s
     * lesson.
     */
    @PostMapping(value = "/links", headers = CARD_HEADER + "=" + CARD_VALUE)
    public ModelAndView createLinkInCard(@Valid @ModelAttribute("addForm") SocialLinkForm form,
                                         BindingResult binding,
                                         @RequestParam(name = "tab", required = false) String tab) {
        SocialLinkPlacement placement = placementOf(form);
        if (binding.hasErrors()) {
            return refusedCard(placement, tab, form, firstError(binding));
        }
        try {
            socialService.createLink(form);
        } catch (MgmtOperationException e) {
            return refusedCard(placement, tab, form, text.of(e));
        }
        return card(placement, tab, text.msg("pages.social.flash.linkAdded"));
    }

    @GetMapping("/links/{id}")
    public String editLink(@PathVariable String id,
                           @RequestParam(name = "lang", required = false) String lang, Model model) {
        SocialLink link = requireLink(id);
        Language language = languageOrOriginal(lang);
        model.addAttribute("form", SocialLinkForm.of(link, language.getCode()));
        addLinkAttributes(model, link, language);
        return "mgmt/social-link-edit";
    }

    @PostMapping("/links/{id}")
    public String saveLink(@PathVariable String id,
                           @Valid @ModelAttribute("form") SocialLinkForm form, BindingResult binding,
                           Model model, RedirectAttributes redirect) {
        // The path is the authority on which link is written; the form carries no id at all.
        SocialLink link = requireLink(id);
        Language language = languageOrOriginal(form.getLang());
        form.setLang(language.getCode());

        if (binding.hasErrors()) {
            addLinkAttributes(model, link, language);
            return "mgmt/social-link-edit";
        }
        try {
            socialService.saveLink(id, form);
        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            addLinkAttributes(model, link, language);
            return "mgmt/social-link-edit";
        }
        redirect.addFlashAttribute("message", text.msg("pages.flash.savedLanguage", language.displayName()));
        return "redirect:/mgmt/social/links/" + id + "?lang=" + language.getCode();
    }

    @PostMapping("/links/{id}/move")
    public String moveLink(@PathVariable String id, @RequestParam("delta") int delta,
                           @RequestParam(name = "lang", required = false) String lang) {
        SocialLinkPlacement placement = placementOf(id);
        socialService.moveLink(id, delta);
        return "redirect:/mgmt/social" + langQuery(lang) + "#links-" + placement;
    }

    /** ↑/↓ posted by mgmt.js: the card again, in its new order, and no reload. */
    @PostMapping(value = "/links/{id}/move", headers = CARD_HEADER + "=" + CARD_VALUE)
    public ModelAndView moveLinkInCard(@PathVariable String id, @RequestParam("delta") int delta,
                                       @RequestParam(name = "lang", required = false) String lang) {
        SocialLinkPlacement placement = placementOf(id);
        socialService.moveLink(id, delta);
        return card(placement, lang, null);
    }

    @PostMapping("/links/{id}/toggle")
    public String toggleLink(@PathVariable String id,
                             @RequestParam(name = "lang", required = false) String lang,
                             RedirectAttributes redirect) {
        SocialLinkPlacement placement = placementOf(id);
        boolean active = socialService.toggleLink(id);
        redirect.addFlashAttribute("message", linkState(active));
        return "redirect:/mgmt/social" + langQuery(lang) + "#links-" + placement;
    }

    /** On/off posted by mgmt.js: the card again, the new state stated in its heading. */
    @PostMapping(value = "/links/{id}/toggle", headers = CARD_HEADER + "=" + CARD_VALUE)
    public ModelAndView toggleLinkInCard(@PathVariable String id,
                                         @RequestParam(name = "lang", required = false) String lang) {
        SocialLinkPlacement placement = placementOf(id);
        boolean active = socialService.toggleLink(id);
        return card(placement, lang, linkState(active));
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/links/{id}/delete")
    public String deleteLink(@PathVariable String id, RedirectAttributes redirect) {
        SocialLinkPlacement placement = placementOf(id);
        socialService.deleteLink(id);
        redirect.addFlashAttribute("message", text.msg("pages.social.flash.linkDeleted"));
        return "redirect:/mgmt/social#links-" + placement;
    }

    /* ---------------------------------------------------------------- statistics */

    /**
     * {@code ?range=}, {@code ?source=} and {@code ?link=} are each resolved leniently, the
     * monitoring screen's {@code ?banner=} rule: something unknown is no filter, never a 400 and
     * never a heading that names something the chart does not draw.
     */
    @GetMapping("/stats")
    @StudioPermission(read = "stats.read")
    public String stats(@RequestParam(name = "range", required = false) String range,
                        @RequestParam(name = "source", required = false) String source,
                        @RequestParam(name = "link", required = false) String link,
                        Model model) {
        MonitoringRange selected = MonitoringRange.of(range);
        SocialPage page = socialService.load();
        List<MgmtSocialStatsService.Bucket> buckets = statsService.series(selected);
        SocialSource sourceFilter = MgmtSocialStatsService.sourceFilter(source);
        String linkFilter = MgmtSocialStatsService.linkFilter(link, page, buckets);

        model.addAttribute("range", selected);
        model.addAttribute("ranges", MonitoringRange.values());
        model.addAttribute("page", page);
        model.addAttribute("exists", socialService.exists());
        model.addAttribute("report", statsService.report(buckets, page, sourceFilter, linkFilter,
                statsService.allTimeClicks()));
        return "mgmt/social-stats";
    }

    /* ---------------------------------------------------------------- internals */

    /**
     * One link card, server-rendered from the same {@code links-card} fragment the page loops
     * over, so a card replaced in place cannot differ from the one a reload would draw -- no
     * client-side twin of a row to drift. {@code tab} is the language tab the page is on, which the
     * card's own forms carry for the no-script redirect.
     */
    private ModelAndView card(SocialLinkPlacement placement, String tab, String note) {
        ModelAndView view = new ModelAndView("mgmt/social :: links-card");
        addCardAttributes(view.getModelMap(), socialService.load(), languageOrOriginal(tab));
        view.addObject("cardGroup", placement);
        view.addObject("cardNote", note);
        return view;
    }

    private ModelAndView refusedCard(SocialLinkPlacement placement, String tab, SocialLinkForm form,
                                     String reason) {
        ModelAndView view = card(placement, tab, null);
        view.addObject("addForm", form);
        view.addObject("addFor", placement);
        view.addObject("addError", reason);
        view.setStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        return view;
    }

    private SocialLinkPlacement placementOf(String id) {
        return socialService.findLink(id).map(SocialLink::getPlacement).orElse(SocialLinkPlacement.BUTTON);
    }

    private static SocialLinkPlacement placementOf(SocialLinkForm form) {
        return form.getPlacement() == null ? SocialLinkPlacement.BUTTON : form.getPlacement();
    }

    private String firstError(BindingResult binding) {
        return binding.getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElseGet(() -> text.msg("pages.social.error.notAdded"));
    }

    private String linkState(boolean active) {
        return text.msg(active ? "pages.social.flash.linkOn" : "pages.social.flash.linkOff");
    }

    private void addEditAttributes(Model model, SocialPage page, Language language) {
        addCardAttributes(model.asMap(), page, language);
        model.addAttribute("exists", socialService.exists());
        model.addAttribute("languages", languageService.findAll());
        model.addAttribute("placements", SocialLinkPlacement.values());
        model.addAttribute("avatar", socialService.avatar(page).orElse(null));
        // The address the console prints for the operator to paste: the website's base URL from
        // its settings, so it names the host readers will actually reach.
        String publicUrl = socialService.publicUrl();
        model.addAttribute("publicUrl", publicUrl);
        model.addAttribute("taggedUrls", Arrays.stream(SocialSource.values())
                .filter(SocialSource::isTaggable)
                .map(s -> new TaggedUrl(s, publicUrl + "?src=" + s.key()))
                .toList());
    }

    /** What the {@code links-card} fragment reads -- shared by the page and the in-place answers. */
    private void addCardAttributes(Map<String, Object> model, SocialPage page, Language language) {
        model.put("page", page);
        model.put("language", language);
        // The add form posts its text under this code: the website's own original language.
        model.put("originalCode", LanguageChain.original());
        model.put("labelChain", LanguageChain.forRequest(null));
        model.put("now", LocalDateTime.now());
        model.put("icons", page.linksOf(SocialLinkPlacement.ICON));
        model.put("buttons", page.linksOf(SocialLinkPlacement.BUTTON));
        model.put("clicks", statsService.clicksByLink(MonitoringRange.D30));
        model.put("socialIcons", SocialIcon.values());
        model.put("articleThumbs", socialService.articleThumbs(page.getLinks()));
    }

    private void addLinkAttributes(Model model, SocialLink link, Language language) {
        model.addAttribute("link", link);
        model.addAttribute("language", language);
        model.addAttribute("languages", languageService.findAll());
        model.addAttribute("originalCode", LanguageChain.original());
        model.addAttribute("labelChain", LanguageChain.forRequest(null));
        model.addAttribute("socialIcons", SocialIcon.values());
        model.addAttribute("placements", SocialLinkPlacement.values());
    }

    private SocialLink requireLink(String id) {
        return socialService.findLink(id).orElseThrow(() ->
                new MgmtNotFoundException("pages.social.error.linkNotFound", id));
    }

    private Language languageOrOriginal(String code) {
        String normalised = LanguageChain.normalise(code);
        return languageService.find(normalised)
                .or(() -> languageService.find(LanguageChain.original()))
                .orElseGet(() -> {
                    // No row for the original either: a nameless stand-in, which displays as
                    // its code -- naming it here would be guessing which language it is.
                    return new Language(LanguageChain.original());
                });
    }

    private static String langQuery(String lang) {
        String code = LanguageChain.normalise(lang);
        return code != null && code.matches("[a-z]{2,3}") ? "?lang=" + code : "";
    }
}
