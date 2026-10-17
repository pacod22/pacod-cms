package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalMembers;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The members card's four actions, shared by the organisation and the website screens. */
final class MembersActions {

    private MembersActions() {
    }

    static void invite(PortalMembers members, PortalMembers.Scope scope, String email, String role, boolean asLink,
                       MgmtText text, RedirectAttributes redirect) {
        try {
            PortalMembers.Invited invited = members.invite(scope, email, role, asLink);
            if (invited.link() != null) {
                redirect.addFlashAttribute("message", text.msg("portal.members.flash.invitedLink", invited.email()));
                redirect.addFlashAttribute("inviteLink", invited.link());
            } else {
                redirect.addFlashAttribute("message", text.msg("portal.members.flash.invited", invited.email()));
            }
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
    }

    static void setRole(PortalMembers members, PortalMembers.Scope scope, String principal, String role,
                        MgmtText text, RedirectAttributes redirect) {
        try {
            members.setRole(scope, principal, role);
            redirect.addFlashAttribute("message", text.msg("portal.members.flash.roleChanged"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
    }

    static void remove(PortalMembers members, PortalMembers.Scope scope, String principal,
                       MgmtText text, RedirectAttributes redirect) {
        try {
            members.remove(scope, principal);
            redirect.addFlashAttribute("message", text.msg("portal.members.flash.removed"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
    }

    static void revoke(PortalMembers members, PortalMembers.Scope scope, String invitation,
                       MgmtText text, RedirectAttributes redirect) {
        try {
            members.revoke(scope, invitation);
            redirect.addFlashAttribute("message", text.msg("portal.members.flash.revoked"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
    }
}
