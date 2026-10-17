package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalMembers;
import io.pacod.cms.studio.site.StudioPaths;
import io.pacod.cms.studio.site.StudioSiteFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ui.Model;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

/** What the portal's screens share: the members card's model, and redirects across websites. */
@Slf4j
final class PortalModels {

    private PortalModels() {
    }

    /**
     * The members card, as {@code members} in the model.
     *
     * @param error why the list is missing, when pacod-id could not be asked; the rest of the
     *              screen still renders
     */
    record MembersCard(boolean available, boolean canManage, List<PortalMembers.Member> members,
                       List<PortalMembers.Invitation> invitations, List<PortalMembers.RoleOption> roles,
                       String self, String error) {
    }

    static void addMembers(Model model, PortalMembers members, PortalMembers.Scope scope, MgmtText text) {
        if (!members.available()) {
            model.addAttribute("members", new MembersCard(false, false, List.of(), List.of(), List.of(), null,
                    text.msg("portal.members.unavailable")));
            return;
        }
        boolean canManage = members.canManage(scope);
        List<PortalMembers.RoleOption> roles = canManage ? members.grantable(scope) : List.of();
        String self = StudioPrincipals.currentSubject();
        try {
            model.addAttribute("members", new MembersCard(true, canManage, members.members(scope),
                    members.invitations(scope), roles, self, null));
        } catch (MgmtOperationException e) {
            log.warn("Could not list the members of {}: {}", scope.key(), e.getMessage());
            model.addAttribute("members", new MembersCard(true, canManage, List.of(), List.of(), roles, self, text.of(e)));
        }
        model.addAttribute("roleLabels", new RoleLabels(members));
    }

    /** {@code ${roleLabels.of(role)}} in the templates. */
    public static final class RoleLabels {
        private final PortalMembers members;

        RoleLabels(PortalMembers members) {
            this.members = members;
        }

        public String of(String role) {
            return members.roleLabel(role);
        }
    }

    /** A redirect to a website's screen, from wherever this request is -- never under another prefix. */
    static RedirectView toSite(HttpServletRequest request, String siteId, String screen) {
        return absolute(request, StudioPaths.siteScreen(siteId, screen));
    }

    /** A redirect to a platform screen, without any website prefix. */
    static RedirectView toPlatform(HttpServletRequest request, String screen) {
        return absolute(request, screen);
    }

    private static RedirectView absolute(HttpServletRequest request, String path) {
        Object root = request.getAttribute(StudioSiteFilter.STUDIO_ROOT);
        RedirectView view = new RedirectView((root instanceof String r ? r : request.getContextPath()) + path);
        view.setContextRelative(false);
        return view;
    }
}
