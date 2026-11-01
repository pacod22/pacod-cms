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
import io.pacod.cms.core.control.data.AiCallParams;
import io.pacod.cms.studio.ai.AiTemplateDetails;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

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
            catalog.forget();
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
        catalog.forget();
        redirect.addFlashAttribute("message", text.msg("portal.platform.ai.gateway.resetDone"));
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN);
    }

    /** One feature's row on the models card: its choice, the parameters it sends, and what the gateway makes of both. */
    public record FeatureRow(AiFeature feature, String override, boolean overrideOffered, AiModels.Resolved inUse,
                             List<AiCatalog.Choice> choices, List<AiCatalog.Unusable> unusable, AiCallParams params,
                             Details details) {
    }

    /**
     * What the gateway says of one model or template, for the screen.
     *
     * @param offered whether the gateway's list names it for this key
     * @param model   the model, for a model id
     * @param spec    the template's settings, for a template the gateway answered for
     * @param pool    a template's pool models the key can see
     * @param notes   where the feature's own parameters meet the gateway's: capped, ignored, fitted
     */
    public record Details(String id, boolean offered, boolean template, AiCatalog.ModelOption model,
                          AiTemplateDetails spec, String specError, List<AiCatalog.ModelOption> pool, List<String> notes) {
    }

    private void addModels(Model model, boolean gatewaySetUp) {
        AiModels.Stored stored = models.stored();
        // Asked afresh on every visit: a template added on the gateway a moment ago must be offered.
        AiCatalog.Lookup lookup = gatewaySetUp ? catalog.refresh() : new AiCatalog.Lookup(null, null);
        AiCatalog.Catalog c = lookup.catalog();
        Map<String, AiCatalog.TemplateLookup> specs = templateSpecs(c, stored);
        Function<String, AiCatalog.TemplateLookup> spec = id -> specs.getOrDefault(id, new AiCatalog.TemplateLookup(null, null));
        List<FeatureRow> rows = new ArrayList<>();
        for (AiFeature f : AiFeature.values()) {
            String override = stored.features().get(f);
            List<AiCatalog.Choice> choices = c == null ? List.of() : c.offeredFor(f);
            AiModels.Resolved inUse = models.resolve(f);
            AiCallParams params = stored.paramsOf(f);
            rows.add(new FeatureRow(f, override, override == null || offers(choices, override), inUse, choices,
                    c == null ? List.of() : c.unusableFor(f), params, details(c, inUse.model(), params, spec)));
        }
        String storedDefault = stored.defaultModel();
        List<AiCatalog.Choice> defaults = c == null ? List.of() : c.offeredForDefault();
        model.addAttribute("aiDefaultDetails", details(c, models.defaultModel(), AiCallParams.NONE, spec));
        model.addAttribute("aiEfforts", efforts(c, stored));
        model.addAttribute("aiCatalog", c);
        model.addAttribute("aiCatalogError", lookup.error());
        model.addAttribute("aiFeatures", rows);
        model.addAttribute("aiDefaultChoices", defaults);
        model.addAttribute("aiDefaultUnusable", c == null ? List.of() : c.unusableForDefault());
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
     * The settings of every template the card shows, asked in parallel when there are several: a
     * gateway that answers slowly then costs one read timeout, not one per template.
     */
    private Map<String, AiCatalog.TemplateLookup> templateSpecs(AiCatalog.Catalog c, AiModels.Stored stored) {
        if (c == null) {
            return Map.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(models.defaultModel());
        for (AiFeature f : AiFeature.values()) {
            ids.add(models.resolve(f).model());
        }
        ids.removeIf(id -> c.template(id).isEmpty());
        Map<String, AiCatalog.TemplateLookup> out = new LinkedHashMap<>();
        if (ids.size() <= 2) {
            ids.forEach(id -> out.put(id, catalog.template(id)));
            return out;
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<AiCatalog.TemplateLookup>> asked = new LinkedHashMap<>();
            ids.forEach(id -> asked.put(id, pool.submit(() -> catalog.template(id))));
            asked.forEach((id, future) -> {
                try {
                    out.put(id, future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    out.put(id, new AiCatalog.TemplateLookup(null, String.valueOf(e.getCause())));
                }
            });
        }
        return out;
    }

    /**
     * {@code id}'s details for the card, judged against {@code params}. Only an id the gateway's
     * list names is asked about, so nothing posted ever reaches the gateway unchecked.
     */
    private Details details(AiCatalog.Catalog c, String id, AiCallParams params,
                            Function<String, AiCatalog.TemplateLookup> spec) {
        boolean template = id != null && id.startsWith("@");
        if (c == null || id == null || !c.has(id)) {
            return new Details(id, false, template, null, null, null, List.of(), List.of());
        }
        Optional<AiCatalog.ModelOption> m = c.model(id);
        if (m.isPresent()) {
            return new Details(id, true, false, m.get(), null, null, List.of(), notes(params, null, List.of(m.get())));
        }
        AiCatalog.TemplateLookup t = spec.apply(id);
        List<AiCatalog.ModelOption> pool = c.poolOf(id);
        return new Details(id, true, true, null, t.details(), t.error(), pool, notes(params, t.details(), pool));
    }

    /** Where what the feature sends meets what the gateway applies; nothing when it sends nothing. */
    private List<String> notes(AiCallParams p, AiTemplateDetails spec, List<AiCatalog.ModelOption> models) {
        List<String> notes = new ArrayList<>();
        if (p.maxTokens() != null) {
            Integer ceiling = spec != null ? spec.maxOutputTokens()
                    : models.size() == 1 ? models.getFirst().maxOutputTokens() : null;
            if (ceiling != null && p.maxTokens() > ceiling) {
                notes.add(text.msg("portal.platform.ai.details.note.budgetCapped", p.maxTokens(), ceiling));
            }
        }
        if (p.temperature() != null) {
            if (spec != null && spec.temperature() != null && spec.temperature().overridesCaller()) {
                notes.add(text.msg("portal.platform.ai.details.note.temperatureLocked"));
            } else if (models.stream().anyMatch(AiCatalog.ModelOption::refusesTemperature)) {
                notes.add(text.msg("portal.platform.ai.details.note.temperatureDropped"));
            }
        }
        String effort = p.reasoningEffort();
        if (effort != null) {
            if (spec != null && spec.reasoningEffort() != null && spec.reasoningEffort().overridesCaller()) {
                notes.add(text.msg("portal.platform.ai.details.note.effortLocked"));
            } else if (!models.isEmpty() && models.stream().allMatch(AiCatalog.ModelOption::takesNoEffort)) {
                notes.add(text.msg("portal.platform.ai.details.note.effortIgnored"));
            } else if (!"none".equals(effort) && models.stream()
                    .anyMatch(mo -> !mo.efforts().isEmpty() && !mo.efforts().contains(effort))) {
                notes.add(text.msg("portal.platform.ai.details.note.effortFitted", effort));
            }
        }
        return notes;
    }

    /** Offered when no listed model reports its levels: the common three, which the gateway fits or drops. */
    private static final List<String> COMMON_EFFORTS = List.of("low", "medium", "high");

    /** The reasoning levels offered: {@code none}, every listed model's (else the common three), then any stored one. */
    private static List<String> efforts(AiCatalog.Catalog c, AiModels.Stored stored) {
        Set<String> out = new LinkedHashSet<>();
        out.add("none");
        List<String> listed = c == null ? List.of() : c.efforts();
        out.addAll(listed.isEmpty() ? COMMON_EFFORTS : listed);
        stored.params().values().forEach(p -> {
            if (p.reasoningEffort() != null) {
                out.add(p.reasoningEffort());
            }
        });
        return List.copyOf(out);
    }

    /**
     * The details of one choice alone, for the card to swap in when a select changes: a blank id is
     * the default in effect -- the feature's, or the installation's.
     */
    @GetMapping("/ai-models/details")
    public String details(@RequestParam(defaultValue = "") String id, @RequestParam(defaultValue = "") String feature,
                          Model model) {
        Optional<AiFeature> f = AiFeature.of(feature);
        String target = id.isBlank() ? f.map(models::environmentModel).orElse(models.environmentDefault()) : id.strip();
        AiCatalog.Catalog c = gatewayConfig.effective().configured() ? catalog.lookup().catalog() : null;
        model.addAttribute("d", details(c, target, f.map(models::params).orElse(AiCallParams.NONE), catalog::template));
        return "mgmt/ai-details :: details";
    }

    /**
     * What each feature's calls send beside the model: a minimum budget, a temperature, a
     * reasoning effort; a blank field sends nothing. Asks no gateway.
     */
    @PostMapping("/ai-params")
    public RedirectView setParams(@RequestParam Map<String, String> params, HttpServletRequest request,
                                  RedirectAttributes redirect) {
        Map<AiFeature, AiCallParams> change = new EnumMap<>(AiFeature.class);
        try {
            for (AiFeature f : AiFeature.values()) {
                String max = params.get("maxTokens_" + f.key());
                String temperature = params.get("temperature_" + f.key());
                String effort = params.get("effort_" + f.key());
                if (max == null && temperature == null && effort == null) {
                    continue;
                }
                change.put(f, new AiCallParams(integer(max, f), decimal(temperature, f),
                        effort == null || effort.isBlank() ? null : effort.strip()));
            }
            models.setParams(change, StudioPrincipals.currentSubject());
            redirect.addFlashAttribute("message", text.msg("portal.platform.ai.params.saved"));
        } catch (AiGatewayConfig.Refused e) {
            String where = AiFeature.of(e.field()).map(text::label).orElseGet(() -> text.msg("portal.platform.ai.params.title"));
            redirect.addFlashAttribute("error", text.msg("portal.platform.ai.params.error." + e.code(), where));
        }
        return PortalModels.toPlatform(request, StudioPaths.PLATFORM_SCREEN + "#ai-models");
    }

    private static Integer integer(String s, AiFeature f) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.strip());
        } catch (NumberFormatException e) {
            throw new AiGatewayConfig.Refused("max_tokens_invalid", f.key());
        }
    }

    private static Double decimal(String s, AiFeature f) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new AiGatewayConfig.Refused("temperature_invalid", f.key());
        }
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
