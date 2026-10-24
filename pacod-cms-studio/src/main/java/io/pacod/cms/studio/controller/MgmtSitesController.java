package io.pacod.cms.studio.controller;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.PlatformScreen;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.demo.StudioDemo;
import io.pacod.cms.studio.form.NewSiteForm;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalOrganisations;
import io.pacod.cms.studio.portal.PortalWebsites;
import io.pacod.cms.studio.site.StudioPaths;
import io.pacod.cms.studio.site.StudioSites;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The website list -- where signing in lands, and where an address that names a website's screen
 * but no website is sent -- and creating a website. Platform screens: they read the control plane
 * only, so they answer with or without a {@code /sites/<id>} prefix.
 */
@Slf4j
@PlatformScreen
@Controller
public class MgmtSitesController {

    /** Content languages the form suggests; any two- or three-letter code is accepted. */
    static final List<String> SUGGESTED_LANGUAGES = List.of("en", "de", "fr", "es", "it", "pt", "nl", "pl", "bg", "ru");

    private final StudioSites sites;
    private final StudioAccess access;
    private final PortalOrganisations organisations;
    private final PortalWebsites websites;
    private final MgmtText text;

    public MgmtSitesController(StudioSites sites, StudioAccess access, PortalOrganisations organisations,
                               PortalWebsites websites, MgmtText text) {
        this.sites = sites;
        this.access = access;
        this.organisations = organisations;
        this.websites = websites;
        this.text = text;
    }

    /**
     * The websites the signed-in person holds any role on -- at the website, at its organisation,
     * or at the platform. Listing one is not opening it: every screen inside checks its own
     * permission.
     */
    @GetMapping(StudioPaths.SITES_SCREEN)
    public String list(Model model) {
        model.addAttribute("sites", sites.all().stream()
                .filter(site -> access.touches(List.of("platform", "organisation:" + site.organisationId(),
                        "cms.website:" + site.id())))
                .toList());
        // The organisations only name and offer; a control plane that cannot be read costs those,
        // never the list (StudioSites.all() degrades the same way).
        Map<String, String> names = Map.of();
        boolean canCreateSite = false;
        boolean canCreateOrganisation = false;
        try {
            names = organisations.all().stream()
                    .collect(Collectors.toMap(PortalOrganisations.OrgView::id, PortalOrganisations.OrgView::name, (a, b) -> a));
            canCreateSite = !organisations.creatable().isEmpty();
            canCreateOrganisation = organisations.allowance().allowed();
        } catch (DataAccessException e) {
            log.error("Could not read the organisations: {}", e.getMessage());
        }
        model.addAttribute("organisationNames", names);
        model.addAttribute("canCreateSite", canCreateSite);
        model.addAttribute("canCreateOrganisation", canCreateOrganisation);
        return "mgmt/sites";
    }

    @GetMapping(StudioPaths.SITES_SCREEN + "/new")
    public String newSite(@RequestParam(required = false) String org, Model model) {
        NewSiteForm form = new NewSiteForm();
        form.setOrganisationId(org);
        return newSiteScreen(form, model);
    }

    @PostMapping(StudioPaths.SITES_SCREEN + "/new")
    public ModelAndView create(@ModelAttribute("form") NewSiteForm form, Model model, HttpServletRequest request,
                               RedirectAttributes redirect) {
        try {
            Website website = websites.create(new PortalWebsites.NewSite(form.getOrganisationId(), form.getId(),
                    form.getName(), form.getLanguage(), form.getTimezone(), form.hostList(), form.isDemo(),
                    form.getTemplateSet(), form.getDemoKind()));
            String created = text.msg("portal.site.flash.created", website.getName());
            if (form.isDemo()) {
                try {
                    // create() refused an unknown kind before anything existed.
                    StudioDemo.Outcome demo = websites.addDemo(website,
                            io.pacod.cms.core.seed.DemoContent.Demo.of(form.getDemoKind()).orElseThrow());
                    created += " " + text.msg("portal.site.flash.demo", demo.content().articles(),
                            demo.content().images(), demo.promotions());
                } catch (RuntimeException e) {
                    // The website exists and works; only its sample content is missing, which
                    // `make seed` can still add.
                    log.error("Demo content for the new website {} failed: {}", website.getId(), e.getMessage());
                    redirect.addFlashAttribute("error", text.msg("portal.site.flash.demoFailed", e.getMessage()));
                }
            }
            redirect.addFlashAttribute("message", created);
            return new ModelAndView(PortalModels.toSite(request, website.getId(), "/mgmt/dashboard"));
        } catch (MgmtOperationException e) {
            model.addAttribute("error", text.of(e));
            return new ModelAndView(newSiteScreen(form, model));
        }
    }

    private String newSiteScreen(NewSiteForm form, Model model) {
        List<PortalOrganisations.OrgView> creatable = organisations.creatable();
        if ((form.getOrganisationId() == null || form.getOrganisationId().isBlank()) && creatable.size() == 1) {
            form.setOrganisationId(creatable.getFirst().id());
        }
        model.addAttribute("form", form);
        model.addAttribute("organisations", creatable);
        model.addAttribute("siteDomain", websites.siteDomain());
        model.addAttribute("languages", SUGGESTED_LANGUAGES);
        model.addAttribute("timezones", ZoneId.getAvailableZoneIds().stream().sorted().toList());
        model.addAttribute("canCreateOrganisation", organisations.allowance().allowed());
        model.addAttribute("demoLanguages", websites.demoLanguages());
        model.addAttribute("demoKinds", io.pacod.cms.core.seed.DemoContent.Demo.values());
        model.addAttribute("templateSets", websites.templateSets());
        return "mgmt/site-new";
    }
}
