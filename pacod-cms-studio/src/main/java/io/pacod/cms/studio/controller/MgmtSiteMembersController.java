package io.pacod.cms.studio.controller;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalMembers;
import io.pacod.cms.studio.site.StudioSites;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Who works on this website: everyone holding a role here or above (the organisation's, inherited,
 * shown but changed on the organisation's screen), invitations, and giving and taking roles at the
 * website itself. pacod-id applies every change as the signed-in person ({@link PortalMembers}).
 */
@StudioPermission(read = "website.read", write = "members.manage")
@Controller
@RequestMapping("/mgmt/members")
public class MgmtSiteMembersController {

    private final PortalMembers members;
    private final StudioSites sites;
    private final MgmtText text;

    public MgmtSiteMembersController(PortalMembers members, StudioSites sites, MgmtText text) {
        this.members = members;
        this.sites = sites;
        this.text = text;
    }

    @GetMapping
    public String show(Model model) {
        model.addAttribute("membersBase", "/mgmt");
        PortalModels.addMembers(model, members, scope(), text);
        return "mgmt/site-members";
    }

    @PostMapping("/invite")
    public String invite(@RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String role,
                         @RequestParam(defaultValue = "false") boolean asLink, RedirectAttributes redirect) {
        MembersActions.invite(members, scope(), email, role, asLink, text, redirect);
        return "redirect:/mgmt/members";
    }

    @PostMapping("/role")
    public String setRole(@RequestParam String principal, @RequestParam String role, RedirectAttributes redirect) {
        MembersActions.setRole(members, scope(), principal, role, text, redirect);
        return "redirect:/mgmt/members";
    }

    @PostMapping("/remove")
    public String remove(@RequestParam String principal, RedirectAttributes redirect) {
        MembersActions.remove(members, scope(), principal, text, redirect);
        return "redirect:/mgmt/members";
    }

    @PostMapping("/invitations/revoke")
    public String revoke(@RequestParam String invitation, RedirectAttributes redirect) {
        MembersActions.revoke(members, scope(), invitation, text, redirect);
        return "redirect:/mgmt/members";
    }

    private PortalMembers.Scope scope() {
        Tenant tenant = TenantContext.current();
        String name = sites.current().map(StudioSites.SiteView::name).orElse(tenant.siteId());
        return PortalMembers.Scope.website(tenant.organisationId(), tenant.siteId(), name);
    }
}
