package io.pacod.cms.studio.controller;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobStatus;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.ai.TranslateSiteHandler;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.pdf.GotenbergClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code /mgmt/jobs}: the website's background jobs -- the list, one job with its log, and the
 * forms that queue them. The runner is {@code JobRunner}; the queue {@code JobQueue}, which every
 * lookup here asks with the bound website's id, so another website's job is a 404.
 *
 * <p>Queuing is a form POST and a redirect to the job; the job screen polls {@code …/status} while
 * it runs (mgmt.js, the log appended) and its "Refresh" link is the no-script poll.
 */
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/jobs")
public class MgmtJobController {

    /** At most this many articles in one job. */
    static final int MAX_ARTICLES = io.pacod.cms.studio.jobs.ArticleSelection.MAX_ARTICLES;
    private static final Pattern JOB_ID = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern IDS = Pattern.compile("\\d{1,9}(\\s*,\\s*\\d{1,9})*");
    private static final Pattern LANGUAGES = Pattern.compile("[a-z]{2,3}(\\s*,\\s*[a-z]{2,3})*");

    private final JobQueue queue;
    private final GotenbergClient gotenberg;
    private final PrintTokens printTokens;
    private final AiAccess ai;
    private final MgmtLanguageService languages;
    private final MgmtText text;

    public MgmtJobController(JobQueue queue, GotenbergClient gotenberg, PrintTokens printTokens, AiAccess ai,
                             MgmtLanguageService languages, MgmtText text) {
        this.queue = queue;
        this.gotenberg = gotenberg;
        this.printTokens = printTokens;
        this.ai = ai;
        this.languages = languages;
        this.text = text;
    }

    /** The languages a translation may go into: the website's own, but its original. */
    private List<Language> translatable() {
        String original = LanguageChain.original();
        return languages.findAll().stream().filter(l -> l.getCode() != null && !l.getCode().equals(original)).toList();
    }

    private boolean pdfEnabled() {
        return gotenberg.enabled() && printTokens.enabled();
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        JobStatus filter = parseStatus(status);
        model.addAttribute("jobs", queue.forSite(TenantContext.current().siteId(), filter,
                PageRequest.of(Math.max(0, page), 30)));
        model.addAttribute("statusFilter", filter);
        model.addAttribute("statuses", JobStatus.values());
        model.addAttribute("maxArticles", MAX_ARTICLES);
        model.addAttribute("zone", java.time.ZoneId.systemDefault());
        model.addAttribute("aiRefusal", ai.refusalHere().orElse(null));
        model.addAttribute("translateLanguages", translatable());
        model.addAttribute("maxSiteCalls", TranslateSiteHandler.MAX_CALLS);
        return "mgmt/jobs";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("job", job(id));
        model.addAttribute("zone", java.time.ZoneId.systemDefault());
        return "mgmt/job";
    }

    /** The running job's poll: the log after {@code since}, and where the job stands. */
    @GetMapping("/{id}/status")
    @ResponseBody
    public Map<String, Object> status(@PathVariable String id, @RequestParam(defaultValue = "0") long since) {
        Job job = job(id);
        String log = job.getLog() == null ? "" : job.getLog();
        // The stored log is its last MAX_LOG characters: offset logChars - length is where it starts.
        long start = job.getLogChars() - log.length();
        Map<String, Object> answer = new LinkedHashMap<>();
        if (since < start || since > job.getLogChars()) {
            answer.put("replace", true);
            answer.put("logChunk", log);
        } else {
            answer.put("logChunk", log.substring((int) (since - start)));
        }
        answer.put("logLength", job.getLogChars());
        answer.put("terminal", job.getStatus().isTerminal());
        answer.put("statusLabel", text.label(job.getStatus()));
        answer.put("badgeClass", job.getStatus().getBadgeClass());
        answer.put("done", job.getDone());
        answer.put("total", job.getTotal());
        return answer;
    }

    /** PDFs of articles: numbers, or a range; optionally only some languages. */
    @PostMapping("/pdf")
    public String queuePdf(@RequestParam(required = false) String ids, @RequestParam(required = false) Integer from,
                           @RequestParam(required = false) Integer to, @RequestParam(required = false) String languages,
                           @RequestParam(defaultValue = "false") boolean next,
                           @RequestParam(required = false) String back,
                           Authentication authentication, RedirectAttributes redirect) {
        String failure = pdfEnabled() ? null : text.msg("jobs.pdf.off");
        Map<String, String> params = new LinkedHashMap<>();
        if (failure == null) {
            failure = selection(ids, from, to, params);
        }
        String langs = languages == null ? "" : languages.strip().toLowerCase(Locale.ROOT);
        if (failure == null && !langs.isEmpty()) {
            if (!LANGUAGES.matcher(langs).matches()) {
                failure = text.msg("jobs.error.ids");
            } else {
                params.put("languages", langs.replaceAll("\\s+", ""));
            }
        }
        if (failure != null) {
            redirect.addFlashAttribute("error", failure);
            return "redirect:" + backTo(back);
        }
        Tenant tenant = TenantContext.current();
        Job job = queue.enqueue(tenant.siteId(), tenant.organisationId(), JobType.PDF_ARTICLES, params,
                StudioPrincipals.displayName(authentication), next);
        redirect.addFlashAttribute("message", text.msg("jobs.flash.queued"));
        return "redirect:/mgmt/jobs/" + job.getId();
    }

    /**
     * Translations of articles into the languages ticked. Needs {@code content.write}, as the
     * class says, and AI available to this person here ({@link AiAccess}: {@code ai.use}, the
     * organisation's and the website's switches, a gateway).
     */
    @PostMapping("/translate")
    public String queueTranslation(@RequestParam(required = false) String ids, @RequestParam(required = false) Integer from,
                                   @RequestParam(required = false) Integer to,
                                   @RequestParam(name = "languages", required = false) List<String> targets,
                                   @RequestParam(defaultValue = "missing") String mode,
                                   @RequestParam(defaultValue = "false") boolean next,
                                   @RequestParam(required = false) String back,
                                   Authentication authentication, RedirectAttributes redirect) {
        String failure = ai.refusalHere().map(refusal -> text.label(refusal)).orElse(null);
        Map<String, String> params = new LinkedHashMap<>();
        if (failure == null) {
            failure = selection(ids, from, to, params);
        }
        if (failure == null) {
            failure = languages(targets, mode, params);
        }
        if (failure != null) {
            redirect.addFlashAttribute("error", failure);
            return "redirect:" + backTo(back);
        }
        Tenant tenant = TenantContext.current();
        Job job = queue.enqueue(tenant.siteId(), tenant.organisationId(), JobType.TRANSLATE_ARTICLES, params,
                StudioPrincipals.displayName(authentication), next);
        redirect.addFlashAttribute("message", text.msg("jobs.flash.queued"));
        return "redirect:/mgmt/jobs/" + job.getId();
    }

    /**
     * Translations of everything that is not an article -- the parts ticked, from
     * {@link TranslateSiteHandler#PARTS} -- under the same conditions as articles'.
     */
    @PostMapping("/translate-site")
    public String queueSiteTranslation(@RequestParam(name = "parts", required = false) List<String> parts,
                                       @RequestParam(name = "languages", required = false) List<String> targets,
                                       @RequestParam(defaultValue = "missing") String mode,
                                       @RequestParam(defaultValue = "false") boolean next,
                                       Authentication authentication, RedirectAttributes redirect) {
        String failure = ai.refusalHere().map(refusal -> text.label(refusal)).orElse(null);
        Map<String, String> params = new LinkedHashMap<>();
        if (failure == null) {
            List<String> chosen = TranslateSiteHandler.parts(parts == null ? "" : String.join(",", parts));
            if (chosen.isEmpty()) {
                failure = text.msg("jobs.error.parts");
            } else {
                params.put("parts", String.join(",", chosen));
            }
        }
        if (failure == null) {
            failure = languages(targets, mode, params);
        }
        if (failure != null) {
            redirect.addFlashAttribute("error", failure);
            return "redirect:/mgmt/jobs";
        }
        Tenant tenant = TenantContext.current();
        Job job = queue.enqueue(tenant.siteId(), tenant.organisationId(), JobType.TRANSLATE_SITE, params,
                StudioPrincipals.displayName(authentication), next);
        redirect.addFlashAttribute("message", text.msg("jobs.flash.queued"));
        return "redirect:/mgmt/jobs/" + job.getId();
    }

    /** Fills {@code params} with the languages ticked that this website offers, and the mode; or says what is wrong. */
    private String languages(List<String> targets, String mode, Map<String, String> params) {
        java.util.Set<String> offered = new java.util.LinkedHashSet<>();
        translatable().forEach(l -> offered.add(l.getCode()));
        List<String> chosen = targets == null ? List.of() : targets.stream()
                .flatMap(t -> java.util.Arrays.stream(t.split("[,\\s]+")))
                .map(t -> t.strip().toLowerCase(Locale.ROOT)).filter(offered::contains).distinct().toList();
        if (chosen.isEmpty()) {
            return text.msg("jobs.error.languages");
        }
        params.put("languages", String.join(",", chosen));
        params.put("mode", "all".equals(mode) ? "all" : "missing");
        return null;
    }

    /** Fills {@code params} with the articles named, or says what is wrong with them. */
    private String selection(String ids, Integer from, Integer to, Map<String, String> params) {
        String list = ids == null ? "" : ids.strip();
        if (!list.isEmpty() && IDS.matcher(list).matches()) {
            params.put("ids", list.replaceAll("\\s+", ""));
            return list.split(",").length > MAX_ARTICLES ? text.msg("jobs.error.tooMany", MAX_ARTICLES) : null;
        }
        if (list.isEmpty() && from != null && to != null && from > 0 && to >= from) {
            params.put("from", from.toString());
            params.put("to", to.toString());
            return to - from >= MAX_ARTICLES ? text.msg("jobs.error.tooMany", MAX_ARTICLES) : null;
        }
        return text.msg("jobs.error.ids");
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable String id, RedirectAttributes redirect) {
        boolean done = queue.cancel(TenantContext.current().siteId(), checkedId(id));
        redirect.addFlashAttribute(done ? "message" : "error", text.msg(done ? "jobs.flash.cancelled" : "jobs.error.notNow"));
        return "redirect:/mgmt/jobs/" + id;
    }

    @PostMapping("/{id}/promote")
    public String promote(@PathVariable String id, RedirectAttributes redirect) {
        boolean done = queue.promote(TenantContext.current().siteId(), checkedId(id));
        redirect.addFlashAttribute(done ? "message" : "error", text.msg(done ? "jobs.flash.promoted" : "jobs.error.notNow"));
        return "redirect:/mgmt/jobs/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        if (queue.delete(TenantContext.current().siteId(), checkedId(id))) {
            redirect.addFlashAttribute("message", text.msg("jobs.flash.deleted"));
            return "redirect:/mgmt/jobs";
        }
        redirect.addFlashAttribute("error", text.msg("jobs.error.notNow"));
        return "redirect:/mgmt/jobs/" + id;
    }

    private Job job(String id) {
        return queue.find(TenantContext.current().siteId(), checkedId(id))
                .orElseThrow(() -> new MgmtNotFoundException("jobs.error.notFound"));
    }

    private static String checkedId(String id) {
        if (id == null || !JOB_ID.matcher(id).matches()) {
            throw new MgmtNotFoundException("jobs.error.notFound");
        }
        return id;
    }

    private static JobStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return JobStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Where a refused form goes back to: the article it came from, or the list. Never elsewhere. */
    private static String backTo(String back) {
        return back != null && back.matches("/mgmt/articles/\\d{1,9}") ? back : "/mgmt/jobs";
    }
}
