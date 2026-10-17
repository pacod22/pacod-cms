package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.PlatformScreen;
import io.pacod.cms.studio.MgmtNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The studio's entry points: the sign-in page, the landing redirect, and a catch-all that makes
 * an unmapped {@code /mgmt/} address a 404 page.
 */
@Slf4j
@PlatformScreen
@Controller
@RequestMapping("/mgmt")
public class MgmtHomeController {

    /** The dashboard is the landing page. */
    @GetMapping({"", "/"})
    public String home() {
        return "redirect:/mgmt/dashboard";
    }

    /**
     * The sign-in page: a link to pacod-id, where signing in happens. Nothing is posted here --
     * pacod-id's redirect comes back to {@code /mgmt/oauth2/code/pacod-id}, which the filter chain
     * handles before any controller.
     */
    @GetMapping("/login")
    public String login() {
        return "mgmt/login";
    }

    /**
     * Refuses anything under {@code /mgmt/} that no other mapping claims.
     *
     * <p>Rendered as the studio's own not-found screen, in its chrome, rather than falling through
     * to the static resource handler's bare 404.
     *
     * <p>GET only. A POST to an unmapped console path gets 405 from Spring, which is the more
     * accurate answer anyway. Console assets live at {@code /mgmt-assets/**}, outside this
     * mapping, so they are unaffected.
     */
    @GetMapping("/**")
    public String notFound(HttpServletRequest request) {
        throw new MgmtNotFoundException("error.page.notFound", request.getRequestURI());
    }
}
