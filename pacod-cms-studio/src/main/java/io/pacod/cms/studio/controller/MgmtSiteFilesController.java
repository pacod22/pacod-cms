package io.pacod.cms.studio.controller;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.services.sitefiles.AiCrawlers;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.MgmtSiteFilesService;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code /mgmt/site-files/{robots,sitemap,llms,llms-full}}: the website's crawler files, one screen
 * per file -- its mode, additions, exclusions, custom text and options, and a preview of what the
 * site answers now. Anyone who may read the website sees them; changing them is
 * {@code website.manage}, like the website's other settings.
 *
 * <p>Each form is {@code multipart/form-data}: an url-encoded sitemap roughly triples
 * ({@code <}, {@code >}, {@code /} become {@code %XX}) and would meet Tomcat's 2 MB form limit
 * well before its own cap, with nothing to say why. A custom text may also come as a file.
 */
@StudioPermission(read = "website.read", write = "website.manage")
@Controller
@RequestMapping("/mgmt/site-files")
public class MgmtSiteFilesController {

    private final MgmtSiteFilesService service;
    private final MgmtText text;

    public MgmtSiteFilesController(MgmtSiteFilesService service, MgmtText text) {
        this.service = service;
        this.text = text;
    }

    @GetMapping
    public String index() {
        return "redirect:/mgmt/site-files/" + SiteFileKind.ROBOTS.param();
    }

    @GetMapping("/{kind}")
    public String show(@PathVariable String kind, Model model) {
        SiteFileKind file = kind(kind);
        SiteFiles files = service.load();
        model.addAttribute("kinds", SiteFileKind.values());
        model.addAttribute("kind", file);
        model.addAttribute("files", files);
        model.addAttribute("setting", files.setting(file));
        model.addAttribute("mode", files.modeOf(file));
        model.addAttribute("modes", file.modes());
        model.addAttribute("preview", service.preview(file, files));
        model.addAttribute("previewLines", MgmtSiteFilesService.PREVIEW_LINES);
        String origin = service.origin();
        model.addAttribute("origin", origin);
        model.addAttribute("publicUrl", origin.isEmpty() ? null : origin + file.path());
        model.addAttribute("ownHosts", String.join(", ", service.ownHosts()));
        model.addAttribute("customKilobytes", (file.customLimit() + 1023) / 1024);
        model.addAttribute("trainingAgents", String.join(", ", AiCrawlers.TRAINING));
        model.addAttribute("answeringAgents", String.join(", ", AiCrawlers.ANSWERING));
        model.addAttribute("maxArticles", file == SiteFileKind.LLMS_FULL ? SiteFiles.MAX_LLMS_FULL_ARTICLES
                : SiteFiles.MAX_LLMS_ARTICLES);
        model.addAttribute("articles", file == SiteFileKind.LLMS_FULL ? files.llmsFullArticleLimit()
                : files.llmsArticleLimit());
        return "mgmt/site-files";
    }

    @PostMapping("/{kind}")
    public String save(@PathVariable String kind,
                       @RequestParam(required = false) String mode,
                       @RequestParam(required = false) String additions,
                       @RequestParam(required = false) String exclusions,
                       @RequestParam(required = false) String custom,
                       @RequestParam(required = false) MultipartFile customFile,
                       @RequestParam(required = false) String options,
                       @RequestParam(defaultValue = "false") boolean blockAiTraining,
                       @RequestParam(defaultValue = "false") boolean blockAiAnswering,
                       @RequestParam(required = false) Integer articles,
                       RedirectAttributes redirect) {
        SiteFileKind file = kind(kind);
        try {
            String customText = custom;
            if (customFile != null && !customFile.isEmpty()) {
                if (customFile.getSize() > file.customLimit()) {
                    throw new MgmtOperationException("siteFiles.error.tooLarge", (file.customLimit() + 1023) / 1024);
                }
                customText = new String(customFile.getBytes(), StandardCharsets.UTF_8);
            }
            service.save(file, new MgmtSiteFilesService.Change(SiteFileMode.parse(mode, null), additions, exclusions,
                    customText, options != null, blockAiTraining, blockAiAnswering, articles));
            redirect.addFlashAttribute("message", text.msg("siteFiles.flash.saved", file.path()));
        } catch (IOException e) {
            redirect.addFlashAttribute("error", text.msg("siteFiles.error.upload"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/site-files/" + file.param();
    }

    @PostMapping("/{kind}/customise")
    public String customise(@PathVariable String kind, RedirectAttributes redirect) {
        SiteFileKind file = kind(kind);
        try {
            service.customise(file);
            redirect.addFlashAttribute("message", text.msg("siteFiles.flash.customised", file.path()));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/site-files/" + file.param();
    }

    private static SiteFileKind kind(String param) {
        return SiteFileKind.ofParam(param).orElseThrow(() -> new MgmtNotFoundException("siteFiles.error.noFile"));
    }
}
