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
import io.pacod.cms.studio.ai.AiCatalog;
import io.pacod.cms.studio.ai.AiFeature;
import io.pacod.cms.studio.ai.AiGatewayConfig;
import io.pacod.cms.studio.ai.AiModels;
import io.pacod.cms.studio.ai.AiRefusal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    private final AiGatewayConfig gatewayConfig;
    private final AiModels models;
    private final AiCatalog catalog;

    public MgmtPlatformController(PortalSettings settings, PortalOrganisations organisations, PortalIdentity identity,
                                  StudioSites sites, MgmtText text, AiGateway gateway, AiGatewayConfig gatewayConfig,
                                  AiModels models, AiCatalog catalog) {
        this.settings = settings;
        this.organisations = organisations;
        this.identity = identity;
        this.sites = sites;
        this.text = text;
        this.gateway = gateway;
        this.gatewayConfig = gatewayConfig;
        this.models = models;
        this.catalog = catalog;
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
        AiGatewayConfig.Effective ai = gatewayConfig.effective();
        model.addAttribute("aiConfigured", ai.configured());
        model.addAttribute("aiGateway", ai);
        model.addAttribute("aiGatewayUpdated", ai.updatedOn() == null ? null
                : UPDATED.format(ai.updatedOn().atZone(ZoneId.systemDefault())));
        model.addAttribute("aiModel", models.defaultModel());
        addModels(model, ai.configured());
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
        ping(redirect, null);
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    private void ping(RedirectAttributes redirect, String savedFirst) {
        long started = System.nanoTime();
        try {
            String model = models.defaultModel();
            AiGateway.Answer answer = gateway.ping(model);
            long millis = (System.nanoTime() - started) / 1_000_000;
            String ok = text.msg("portal.platform.ai.test.ok", millis, model,
                    answer.requestId() == null ? "—" : answer.requestId());
            redirect.addFlashAttribute("message", savedFirst == null ? ok : savedFirst + " " + ok);
        } catch (AiGateway.AiException e) {
            if (savedFirst != null) {
                redirect.addFlashAttribute("message", savedFirst);
            }
            redirect.addFlashAttribute("error", text.msg("portal.platform.ai.test.failed", e.getMessage()));
        }
    }

    /**
     * A new gateway address and key, stored in the control plane and used from the next call on,
     * over the environment's. The key is never shown back, so it is entered with every save -- a
     * key only ever goes with the address it was saved with.
     */
    @PostMapping("/ai-gateway")
    public RedirectView setGateway(@RequestParam(defaultValue = "") String url,
                                   @RequestParam(defaultValue = "") String apiKey,
                                   @RequestParam(defaultValue = "false") boolean test,
                                   HttpServletRequest request, RedirectAttributes redirect) {
        try {
            gatewayConfig.set(url, apiKey, StudioPrincipals.currentSubject());
            String saved = text.msg("portal.platform.ai.gateway.saved");
            if (test) {
                ping(redirect, saved);
            } else {
                redirect.addFlashAttribute("message", saved);
            }
        } catch (AiGatewayConfig.Refused e) {
            redirect.addFlashAttribute("error", text.msg("portal.platform.ai.gateway.error." + e.code()));
        }
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    /** Forgets the stored address and key: the environment's apply again. */
    @PostMapping("/ai-gateway/reset")
    public RedirectView resetGateway(HttpServletRequest request, RedirectAttributes redirect) {
        gatewayConfig.clear();
        redirect.addFlashAttribute("message", text.msg("portal.platform.ai.gateway.resetDone"));
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    /** One feature's row on the models card. */
    public record FeatureRow(AiFeature feature, String override, boolean overrideOffered, AiModels.Resolved inUse,
                             List<AiCatalog.Choice> choices) {
    }

    private void addModels(Model model, boolean gatewaySetUp) {
        AiModels.Stored stored = models.stored();
        AiCatalog.Lookup lookup = gatewaySetUp ? catalog.lookup() : new AiCatalog.Lookup(null, null);
        AiCatalog.Catalog c = lookup.catalog();
        List<FeatureRow> rows = new ArrayList<>();
        for (AiFeature f : AiFeature.values()) {
            String override = stored.features().get(f);
            List<AiCatalog.Choice> choices = c == null ? List.of() : c.offeredFor(f);
            rows.add(new FeatureRow(f, override, override == null || offers(choices, override), models.resolve(f), choices));
        }
        String storedDefault = stored.defaultModel();
        List<AiCatalog.Choice> defaults = c == null ? List.of() : c.offeredForDefault();
        model.addAttribute("aiCatalog", c);
        model.addAttribute("aiCatalogError", lookup.error());
        model.addAttribute("aiFeatures", rows);
        model.addAttribute("aiDefaultChoices", defaults);
        model.addAttribute("aiStoredDefault", storedDefault);
        model.addAttribute("aiStoredDefaultOffered", storedDefault == null || offers(defaults, storedDefault));
        model.addAttribute("aiEnvironmentDefault", models.environmentDefault());
        model.addAttribute("aiModelsStored", storedDefault != null || !stored.features().isEmpty());
        model.addAttribute("aiModelsUpdated", stored.updatedOn() == null ? null
                : UPDATED.format(stored.updatedOn().atZone(ZoneId.systemDefault())));
        model.addAttribute("aiModelsUpdatedBy", stored.updatedBy());
    }

    private static boolean offers(List<AiCatalog.Choice> choices, String id) {
        return choices.stream().anyMatch(ch -> ch.id().equals(id));
    }

    /**
     * The default model or template and each feature's override, chosen from what the gateway
     * offers; only what changes is checked against its list. A blank select clears its entry.
     */
    @PostMapping("/ai-models")
    public RedirectView setModels(@RequestParam(name = "default", defaultValue = "") String defaultModel,
                                  @RequestParam Map<String, String> params,
                                  HttpServletRequest request, RedirectAttributes redirect) {
        Map<AiFeature, String> features = new EnumMap<>(AiFeature.class);
        for (AiFeature f : AiFeature.values()) {
            String posted = params.get("feature_" + f.key());
            if (posted != null) {
                features.put(f, posted);
            }
        }
        try {
            models.set(new AiModels.Change(defaultModel, features), StudioPrincipals.currentSubject());
            redirect.addFlashAttribute("message", text.msg("portal.platform.ai.models.saved"));
        } catch (AiGatewayConfig.Refused e) {
            String where = AiFeature.of(e.field()).map(text::label).orElseGet(() -> text.msg("portal.platform.ai.models.default"));
            redirect.addFlashAttribute("error", text.msg("portal.platform.ai.models.error." + e.code(), where));
        }
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    /** Forgets the chosen models: the properties apply again. Asks no gateway. */
    @PostMapping("/ai-models/reset")
    public RedirectView resetModels(HttpServletRequest request, RedirectAttributes redirect) {
        models.clear();
        redirect.addFlashAttribute("message", text.msg("portal.platform.ai.models.resetDone"));
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
