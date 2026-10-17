package io.pacod.cms.studio.controller;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.OrganisationPermission;
import io.pacod.cms.studio.access.PlatformScreen;
import io.pacod.cms.studio.access.StudioAccess;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalMembers;
import io.pacod.cms.studio.portal.PortalOrganisations;
import io.pacod.cms.studio.site.StudioPaths;
import io.pacod.cms.studio.site.StudioSites;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Organisations: the list, creating one, and an organisation's own screen -- its name, its websites,
 * its members, deleting it.
 *
 * <p>Platform screens: they belong to no website. The list and the create action are open to anyone
 * signed in, and {@code PortalOrganisations} decides who may create; everything under
 * {@code /{orgId}} carries {@link OrganisationPermission}, checked at that organisation before the
 * handler runs.
 */
@PlatformScreen
@Controller
@RequestMapping(StudioPaths.ORGANISATIONS_SCREEN)
public class MgmtOrganisationController {

    private final PortalOrganisations organisations;
    private final PortalMembers members;
    private final StudioSites sites;
    private final StudioAccess access;
    private final MgmtText text;

    public MgmtOrganisationController(PortalOrganisations organisations, PortalMembers members, StudioSites sites,
                                      StudioAccess access, MgmtText text) {
        this.organisations = organisations;
        this.members = members;
        this.sites = sites;
        this.access = access;
        this.text = text;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("organisations", organisations.visible());
        model.addAttribute("allowance", organisations.allowance());
        return "mgmt/organisations";
    }

    @PostMapping
    public RedirectView create(@RequestParam(defaultValue = "") String name, HttpServletRequest request,
                               RedirectAttributes redirect) {
        try {
            PortalOrganisations.Created created = organisations.create(name);
            redirect.addFlashAttribute("message", text.msg(created.settled()
                    ? "portal.org.flash.created" : "portal.org.flash.createdPending"));
            return PortalModels.toPlatform(request, StudioPaths.ORGANISATIONS_SCREEN + "/" + created.organisation().getId());
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
            return PortalModels.toPlatform(request, StudioPaths.ORGANISATIONS_SCREEN);
        }
    }

    @GetMapping("/{orgId}")
    @OrganisationPermission(read = "org.read")
    public String show(@PathVariable String orgId, Model model) {
        Organisation organisation = organisations.require(orgId);
        model.addAttribute("organisation", organisations.view(organisation));
        model.addAttribute("sites", sites.all().stream().filter(s -> orgId.equals(s.organisationId())).toList());
        model.addAttribute("canManage", access.allowedAt("org.manage", StudioAccess.pathOfOrganisation(orgId)));
        model.addAttribute("canCreateSite", access.allowedAt("website.create", StudioAccess.pathOfOrganisation(orgId)));
        model.addAttribute("membersBase", StudioPaths.ORGANISATIONS_SCREEN + "/" + orgId);
        PortalModels.addMembers(model, members, scope(organisation), text);
        return "mgmt/organisation";
    }

    @PostMapping("/{orgId}")
    @OrganisationPermission(read = "org.manage")
    public RedirectView rename(@PathVariable String orgId, @RequestParam(defaultValue = "") String name,
                               HttpServletRequest request, RedirectAttributes redirect) {
        try {
            organisations.rename(orgId, name);
            redirect.addFlashAttribute("message", text.msg("portal.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return back(request, orgId);
    }

    @PostMapping("/{orgId}/delete")
    @OrganisationPermission(read = "org.manage")
    public RedirectView delete(@PathVariable String orgId, HttpServletRequest request, RedirectAttributes redirect) {
        try {
            organisations.delete(orgId);
            redirect.addFlashAttribute("message", text.msg("portal.org.flash.deleted"));
            return PortalModels.toPlatform(request, StudioPaths.ORGANISATIONS_SCREEN);
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
            return back(request, orgId);
        }
    }

    @PostMapping("/{orgId}/members/invite")
    @OrganisationPermission(read = "members.manage")
    public RedirectView invite(@PathVariable String orgId, @RequestParam(defaultValue = "") String email,
                               @RequestParam(defaultValue = "") String role,
                               @RequestParam(defaultValue = "false") boolean asLink,
                               HttpServletRequest request, RedirectAttributes redirect) {
        MembersActions.invite(members, scope(organisations.require(orgId)), email, role, asLink, text, redirect);
        return back(request, orgId);
    }

    @PostMapping("/{orgId}/members/role")
    @OrganisationPermission(read = "members.manage")
    public RedirectView setRole(@PathVariable String orgId, @RequestParam String principal, @RequestParam String role,
                                HttpServletRequest request, RedirectAttributes redirect) {
        MembersActions.setRole(members, scope(organisations.require(orgId)), principal, role, text, redirect);
        return back(request, orgId);
    }

    @PostMapping("/{orgId}/members/remove")
    @OrganisationPermission(read = "members.manage")
    public RedirectView remove(@PathVariable String orgId, @RequestParam String principal,
                               HttpServletRequest request, RedirectAttributes redirect) {
        MembersActions.remove(members, scope(organisations.require(orgId)), principal, text, redirect);
        return back(request, orgId);
    }

    @PostMapping("/{orgId}/members/invitations/revoke")
    @OrganisationPermission(read = "members.manage")
    public RedirectView revoke(@PathVariable String orgId, @RequestParam String invitation,
                               HttpServletRequest request, RedirectAttributes redirect) {
        MembersActions.revoke(members, scope(organisations.require(orgId)), invitation, text, redirect);
        return back(request, orgId);
    }

    private static PortalMembers.Scope scope(Organisation organisation) {
        return PortalMembers.Scope.organisation(organisation.getId(),
                organisation.getName() == null ? organisation.getId() : organisation.getName());
    }

    private static RedirectView back(HttpServletRequest request, String orgId) {
        return PortalModels.toPlatform(request, StudioPaths.ORGANISATIONS_SCREEN + "/" + orgId);
    }
}
