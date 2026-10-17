package io.pacod.cms.studio.controller;

import io.pacod.cms.core.control.data.OrgCreation;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.PlatformPermission;
import io.pacod.cms.studio.access.PlatformScreen;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.form.PlatformSettingsForm;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalIdentity;
import io.pacod.cms.studio.portal.PortalOrganisations;
import io.pacod.cms.studio.portal.PortalSettings;
import io.pacod.cms.studio.site.StudioPaths;
import io.pacod.cms.studio.site.StudioSites;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.AiRefusal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Arrays;

/**
 * The installation: who may create organisations, the self-service limits, AI for new
 * organisations, and every organisation with its websites and its AI switch. Platform admins only.
 */
@PlatformScreen
@PlatformPermission(read = "platform.manage")
@Controller
@RequestMapping(StudioPaths.PLATFORM_SCREEN)
public class MgmtPlatformController {

    private final PortalSettings settings;
    private final PortalOrganisations organisations;
    private final PortalIdentity identity;
    private final StudioSites sites;
    private final MgmtText text;
    private final AiGateway gateway;
    private final String model;

    public MgmtPlatformController(PortalSettings settings, PortalOrganisations organisations, PortalIdentity identity,
                                  StudioSites sites, MgmtText text, AiGateway gateway,
                                  @Value("${pacod.ai.model:@fast}") String model) {
        this.settings = settings;
        this.organisations = organisations;
        this.identity = identity;
        this.sites = sites;
        this.text = text;
        this.gateway = gateway;
        this.model = model;
    }

    @GetMapping
    public String show(Model model) {
        model.addAttribute("form", PlatformSettingsForm.of(settings.current()));
        model.addAttribute("orgCreationFixed", settings.orgCreationFixed());
        model.addAttribute("orgCreations", Arrays.asList(OrgCreation.values()));
        model.addAttribute("organisations", organisations.all());
        model.addAttribute("siteCount", sites.all().size());
        model.addAttribute("identityEnabled", identity.enabled());
        model.addAttribute("canProvision", identity.canProvision());
        model.addAttribute("aiConfigured", gateway.configured());
        model.addAttribute("aiModel", this.model);
        model.addAttribute("aiNotSetUp", AiRefusal.NOT_SET_UP);
        return "mgmt/platform";
    }

    @PostMapping
    public RedirectView save(@ModelAttribute("form") PlatformSettingsForm form, HttpServletRequest request,
                             RedirectAttributes redirect) {
        try {
            settings.save(form.getOrgCreation(), form.getMaxOrgsPerUser(), form.getMaxSitesPerOrg(),
                    form.isAiEnabledForNewOrgs(), StudioPrincipals.currentSubject());
            redirect.addFlashAttribute("message", text.msg("portal.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    /**
     * One short call through the gateway with the installation's key and default model, so a
     * platform admin learns whether AI works before any organisation finds out. The answer names
     * the time, the model and the gateway's request id; a refusal says why, with the gateway's
     * error code -- never the key.
     */
    @PostMapping("/ai-test")
    public RedirectView testAi(HttpServletRequest request, RedirectAttributes redirect) {
        long started = System.nanoTime();
        try {
            AiGateway.Answer answer = gateway.ping(model);
            long millis = (System.nanoTime() - started) / 1_000_000;
            redirect.addFlashAttribute("message", text.msg("portal.platform.ai.test.ok", millis, model,
                    answer.requestId() == null ? "—" : answer.requestId()));
        } catch (AiGateway.AiException e) {
            redirect.addFlashAttribute("error", text.msg("portal.platform.ai.test.failed", e.getMessage()));
        }
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    @PostMapping("/organisations/{orgId}/ai")
    public RedirectView setAi(@PathVariable String orgId, @RequestParam(defaultValue = "false") boolean enabled,
                              HttpServletRequest request, RedirectAttributes redirect) {
        try {
            organisations.setAiEnabled(orgId, enabled);
            redirect.addFlashAttribute("message", text.msg(enabled ? "portal.platform.flash.aiOn" : "portal.platform.flash.aiOff"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }
}
