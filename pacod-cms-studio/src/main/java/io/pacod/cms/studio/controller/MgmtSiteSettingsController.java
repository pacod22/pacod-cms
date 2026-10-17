package io.pacod.cms.studio.controller;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.form.SiteSettingsForm;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalWebsites;
import io.pacod.cms.studio.site.StudioPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

/**
 * A website's own settings: its name, language, time zone and status, its domains, and deleting it.
 * Inside the website ({@code /sites/<id>/mgmt/settings}); the website is the one bound to the
 * request, never a posted value.
 *
 * <p>Deleting needs {@code org.manage} -- an organisation admin's decision, not a website admin's,
 * whose role would go with the website.
 */
@StudioPermission(read = "website.manage")
@Controller
@RequestMapping("/mgmt/settings")
public class MgmtSiteSettingsController {

    private final PortalWebsites websites;
    private final StudioAccess access;
    private final MgmtText text;

    public MgmtSiteSettingsController(PortalWebsites websites, StudioAccess access, MgmtText text) {
        this.websites = websites;
        this.access = access;
        this.text = text;
    }

    @GetMapping
    public String show(Model model) {
        Website website = websites.require(siteId());
        model.addAttribute("website", website);
        model.addAttribute("form", SiteSettingsForm.of(website));
        model.addAttribute("domains", websites.domainsOf(website.getId()));
        model.addAttribute("statuses", List.of(WebsiteStatus.ACTIVE, WebsiteStatus.SUSPENDED));
        model.addAttribute("canDelete", access.allowed("org.manage"));
        return "mgmt/site-settings";
    }

    @PostMapping
    public String save(@ModelAttribute("form") SiteSettingsForm form, RedirectAttributes redirect) {
        try {
            websites.update(siteId(), form.getName(), form.getLanguage(), form.getTimezone(), form.getStatus(), form.isAi());
            redirect.addFlashAttribute("message", text.msg("portal.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/settings";
    }

    @PostMapping("/domains")
    public String addDomain(@RequestParam(defaultValue = "") String host, RedirectAttributes redirect) {
        try {
            websites.addDomain(siteId(), host);
            redirect.addFlashAttribute("message", text.msg("portal.domain.flash.added", host.strip()));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/settings";
    }

    @PostMapping("/domains/primary")
    public String makePrimary(@RequestParam String host, RedirectAttributes redirect) {
        try {
            websites.makePrimary(siteId(), host);
            redirect.addFlashAttribute("message", text.msg("portal.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/settings";
    }

    @PostMapping("/domains/remove")
    public String removeDomain(@RequestParam String host, RedirectAttributes redirect) {
        try {
            websites.removeDomain(siteId(), host);
            redirect.addFlashAttribute("message", text.msg("portal.domain.flash.removed", host));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/settings";
    }

    @PostMapping("/delete")
    @StudioPermission(read = "org.manage")
    public ModelAndView delete(@RequestParam(defaultValue = "") String confirmation, HttpServletRequest request,
                               RedirectAttributes redirect) {
        String siteId = siteId();
        Website website = websites.require(siteId);
        try {
            websites.delete(siteId, confirmation);
            redirect.addFlashAttribute("message", text.msg("portal.site.flash.deleted", website.getName()));
            return new ModelAndView(PortalModels.toPlatform(request,
                    StudioPaths.ORGANISATIONS_SCREEN + "/" + website.getOrganisationId()));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
            return new ModelAndView("redirect:/mgmt/settings");
        }
    }

    private static String siteId() {
        return TenantContext.current().siteId();
    }
}
