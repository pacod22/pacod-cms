package io.pacod.cms.studio.controller;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.design.PrintTokens;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtIssueService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.MgmtPdfSettingsService;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.pdf.GotenbergClient;
import io.pacod.cms.studio.site.StudioSites;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code /mgmt/issues}: the website's journal issues ({@link JournalIssue}) -- the list, a new
 * issue, its editor (details, articles in order, filled from its month), generating its PDF (a
 * {@code PDF_ISSUE} job), publishing it at {@code /issues} on the website, deleting it, and
 * downloading a generated PDF.
 *
 * <p>Publishing and unpublishing are {@code content.publish}, deleting {@code content.delete}, as
 * for articles. A generated PDF is downloaded from here as an attachment -- served from the
 * studio's origin, it is never shown inline -- whether or not the issue is published.
 */
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/issues")
public class MgmtIssueController {

    private static final Pattern ID = Pattern.compile("\\d{1,9}");
    private static final Pattern IDS = Pattern.compile("\\d{1,9}(\\s*,\\s*\\d{1,9})*");
    private static final Pattern LANG = Pattern.compile("[a-z]{2,3}");

    private final MgmtIssueService issues;
    private final MgmtPdfSettingsService pdfSettings;
    private final JobQueue queue;
    private final GotenbergClient gotenberg;
    private final PrintTokens printTokens;
    private final AiAccess ai;
    private final StudioSites sites;
    private final MgmtText text;

    public MgmtIssueController(MgmtIssueService issues, MgmtPdfSettingsService pdfSettings, JobQueue queue,
                               GotenbergClient gotenberg, PrintTokens printTokens, AiAccess ai, StudioSites sites,
                               MgmtText text) {
        this.issues = issues;
        this.pdfSettings = pdfSettings;
        this.queue = queue;
        this.gotenberg = gotenberg;
        this.printTokens = printTokens;
        this.ai = ai;
        this.sites = sites;
        this.text = text;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("issues", issues.list(page));
        model.addAttribute("original", LanguageChain.original());
        return "mgmt/issues";
    }

    @GetMapping("/new")
    public String newIssue(Model model) {
        model.addAttribute("suggestion", issues.suggestion());
        return "mgmt/issue-new";
    }

    @PostMapping
    public String create(@RequestParam(defaultValue = "0") int volume, @RequestParam(defaultValue = "0") int number,
                         @RequestParam(defaultValue = "0") int year, @RequestParam(defaultValue = "0") int month,
                         RedirectAttributes redirect) {
        try {
            JournalIssue issue = issues.create(volume, number, year, month);
            redirect.addFlashAttribute("message", text.msg("issues.flash.created"));
            return "redirect:/mgmt/issues/" + issue.getId();
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
            return "redirect:/mgmt/issues/new";
        }
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        JournalIssue issue = issues.find(checkedId(id));
        PdfSettings settings = pdfSettings.load();
        String original = LanguageChain.original();
        model.addAttribute("issue", issue);
        model.addAttribute("titles", pdfSettings.fields(issue.getTitles()));
        model.addAttribute("editorials", pdfSettings.fields(issue.getEditorials()));
        model.addAttribute("languages", pdfSettings.fields(List.of()));
        model.addAttribute("rows", issues.rows(issue.getArticleIds(), original));
        model.addAttribute("articleIdsText", String.join(", ", issue.getArticleIds().stream().map(String::valueOf).toList()));
        model.addAttribute("maxArticles", JournalIssue.MAX_ARTICLES);
        model.addAttribute("pdfSettings", settings);
        model.addAttribute("pdfFormats", PdfFormat.values());
        model.addAttribute("pdfModes", PdfMode.values());
        model.addAttribute("pdfEnabled", gotenberg.enabled() && printTokens.enabled());
        model.addAttribute("aiRefusal", ai.refusalHere().orElse(null));
        model.addAttribute("zone", java.time.ZoneId.systemDefault());
        model.addAttribute("publicUrl", sites.current().map(StudioSites.SiteView::publicUrl)
                .map(origin -> origin + "/issues/" + issue.getId()).orElse(null));
        return "mgmt/issue-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable String id,
                       @RequestParam(defaultValue = "0") int volume, @RequestParam(defaultValue = "0") int number,
                       @RequestParam(defaultValue = "0") int year, @RequestParam(defaultValue = "0") int month,
                       @RequestParam(required = false) String coverImageId,
                       @RequestParam(required = false) String format, @RequestParam(required = false) String mode,
                       @RequestParam(name = "languages", required = false) List<String> languages,
                       @RequestParam(required = false) String articleIds,
                       @RequestParam Map<String, String> params,
                       RedirectAttributes redirect) {
        int issueId = checkedId(id);
        try {
            issues.save(issueId, new MgmtIssueService.IssueEdit(volume, number, year, month,
                    prefixed(params, "title_"), prefixed(params, "editorial_"), cover(coverImageId),
                    PdfFormat.parse(format, null), PdfMode.parse(mode, null),
                    languages == null ? List.of() : languages.stream().filter(l -> LANG.matcher(l).matches()).toList(),
                    articleIds(articleIds)));
            redirect.addFlashAttribute("message", text.msg("issues.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/issues/" + issueId;
    }

    @PostMapping("/{id}/fill-month")
    public String fillMonth(@PathVariable String id, RedirectAttributes redirect) {
        int issueId = checkedId(id);
        redirect.addFlashAttribute("message", text.msg("issues.flash.filled", issues.fillMonth(issueId)));
        return "redirect:/mgmt/issues/" + issueId;
    }

    @PostMapping("/{id}/articles/move")
    public String move(@PathVariable String id, @RequestParam int article, @RequestParam int delta) {
        int issueId = checkedId(id);
        issues.move(issueId, article, delta);
        return "redirect:/mgmt/issues/" + issueId;
    }

    @PostMapping("/{id}/articles/remove")
    public String remove(@PathVariable String id, @RequestParam int article) {
        int issueId = checkedId(id);
        issues.remove(issueId, article);
        return "redirect:/mgmt/issues/" + issueId;
    }

    /**
     * Queues the issue's PDF: a format and a mode (blank: the issue's own, else the website's
     * defaults, resolved now and stored), the languages ticked (none: the issue's). The LLM mode
     * needs AI available to this person here.
     */
    @PostMapping("/{id}/generate")
    public String generate(@PathVariable String id, @RequestParam(required = false) String format,
                           @RequestParam(required = false) String mode,
                           @RequestParam(name = "languages", required = false) List<String> languages,
                           @RequestParam(defaultValue = "false") boolean refresh,
                           @RequestParam(defaultValue = "false") boolean next,
                           Authentication authentication, RedirectAttributes redirect) {
        int issueId = checkedId(id);
        JournalIssue issue = issues.find(issueId);
        PdfSettings settings = pdfSettings.load();
        PdfFormat pdfFormat = PdfFormat.parse(format, issue.getFormat() == null ? settings.format() : issue.getFormat());
        PdfMode pdfMode = PdfMode.parse(mode, issue.getMode() == null ? settings.mode() : issue.getMode());
        String failure = gotenberg.enabled() && printTokens.enabled() ? null : text.msg("jobs.pdf.off");
        if (failure == null && pdfMode == PdfMode.LLM) {
            failure = ai.refusalHere().map(text::label).orElse(null);
        }
        if (failure == null && issue.getArticleIds().isEmpty()) {
            failure = text.msg("issues.error.empty");
        }
        if (failure != null) {
            redirect.addFlashAttribute("error", failure);
            return "redirect:/mgmt/issues/" + issueId;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("issue", String.valueOf(issueId));
        params.put("format", pdfFormat.param());
        params.put("mode", pdfMode.param());
        List<String> offered = pdfSettings.offeredCodes();
        List<String> chosen = languages == null ? List.of()
                : languages.stream().filter(offered::contains).distinct().toList();
        if (!chosen.isEmpty()) {
            params.put("languages", String.join(",", chosen));
        }
        if (refresh && pdfMode == PdfMode.LLM) {
            params.put("refresh", "true");
        }
        Tenant tenant = TenantContext.current();
        Job job = queue.enqueue(tenant.siteId(), tenant.organisationId(), JobType.PDF_ISSUE, params,
                StudioPrincipals.displayName(authentication), next);
        redirect.addFlashAttribute("message", text.msg("jobs.flash.queued"));
        return "redirect:/mgmt/jobs/" + job.getId();
    }

    @PostMapping("/{id}/publish")
    @StudioPermission(read = "content.read", write = "content.publish")
    public String publish(@PathVariable String id, RedirectAttributes redirect) throws IOException {
        int issueId = checkedId(id);
        boolean withPdf = issues.publish(issueId);
        redirect.addFlashAttribute("message", text.msg(withPdf ? "issues.flash.published" : "issues.flash.publishedNoPdf"));
        return "redirect:/mgmt/issues/" + issueId;
    }

    @PostMapping("/{id}/unpublish")
    @StudioPermission(read = "content.read", write = "content.publish")
    public String unpublish(@PathVariable String id, RedirectAttributes redirect) throws IOException {
        int issueId = checkedId(id);
        issues.unpublish(issueId);
        redirect.addFlashAttribute("message", text.msg("issues.flash.unpublished"));
        return "redirect:/mgmt/issues/" + issueId;
    }

    @PostMapping("/{id}/delete")
    @StudioPermission(read = "content.read", write = "content.delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) throws IOException {
        issues.delete(checkedId(id));
        redirect.addFlashAttribute("message", text.msg("issues.flash.deleted"));
        return "redirect:/mgmt/issues";
    }

    /** A generated PDF, as a download: never inline on the studio's origin. */
    @GetMapping("/{id}/pdf/{lang}")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable String lang) {
        int issueId = checkedId(id);
        if (!LANG.matcher(lang).matches()) {
            throw new MgmtNotFoundException("issues.error.notFound", issueId);
        }
        issues.find(issueId);
        Path file = issues.draftPdf(issueId, lang);
        if (!Files.isRegularFile(file)) {
            throw new MgmtNotFoundException("issues.error.notFound", issueId);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("issue-" + issueId + "-" + lang + ".pdf").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new FileSystemResource(file));
    }

    private static int checkedId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new MgmtNotFoundException("issues.error.notFound", 0);
        }
        return Integer.parseInt(id);
    }

    private static Integer cover(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return null;
        }
        if (!ID.matcher(v).matches()) {
            throw new MgmtOperationException("issues.error.cover", 0);
        }
        return Integer.parseInt(v);
    }

    private static List<Integer> articleIds(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return List.of();
        }
        if (!IDS.matcher(v).matches()) {
            throw new MgmtOperationException("issues.error.articles");
        }
        List<Integer> ids = new ArrayList<>();
        Arrays.stream(v.split(",")).map(String::strip).map(Integer::parseInt).forEach(ids::add);
        return ids;
    }

    private static Map<String, String> prefixed(Map<String, String> params, String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (name.startsWith(prefix) && LANG.matcher(name.substring(prefix.length())).matches()) {
                values.put(name.substring(prefix.length()), value);
            }
        });
        return values;
    }
}
