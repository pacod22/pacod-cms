package io.pacod.cms.studio.controller;

import io.pacod.cms.core.data.PaperSize;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.MgmtPdfSettingsService;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /mgmt/pdf}: the website's PDF defaults ({@link PdfSettings}) -- the format and mode a job
 * uses when it names none, the paper and margins, the journal's name and ISSN, and what the AI
 * gateway is asked for. Everyone who may read content sees them, since they say what a job will
 * do; changing them is {@code website.manage}, like the website's other settings: they are the
 * journal's public face.
 */
@StudioPermission(read = "content.read", write = "website.manage")
@Controller
@RequestMapping("/mgmt/pdf")
public class MgmtPdfSettingsController {

    private static final String NAME_PREFIX = "journalName_";

    private final MgmtPdfSettingsService service;
    private final AiAccess ai;
    private final MgmtText text;

    public MgmtPdfSettingsController(MgmtPdfSettingsService service, AiAccess ai, MgmtText text) {
        this.service = service;
        this.ai = ai;
        this.text = text;
    }

    @GetMapping
    public String show(Model model) {
        PdfSettings settings = service.load();
        model.addAttribute("settings", settings);
        model.addAttribute("journalNames", service.fields(settings.getJournalNames()));
        model.addAttribute("formats", PdfFormat.values());
        model.addAttribute("modes", PdfMode.values());
        model.addAttribute("papers", PaperSize.values());
        model.addAttribute("aiRefusal", ai.refusalHere().orElse(null));
        model.addAttribute("minMargin", PdfSettings.MIN_MARGIN_MM);
        model.addAttribute("maxMargin", PdfSettings.MAX_MARGIN_MM);
        model.addAttribute("minKeywords", PdfSettings.MIN_KEYWORDS);
        model.addAttribute("maxKeywords", PdfSettings.MAX_KEYWORDS);
        return "mgmt/pdf-settings";
    }

    @PostMapping
    public String save(@RequestParam(required = false) String defaultFormat,
                       @RequestParam(required = false) String defaultMode,
                       @RequestParam(required = false) String paperSize,
                       @RequestParam(defaultValue = "15") int marginMm,
                       @RequestParam(required = false) String issn,
                       @RequestParam(defaultValue = "false") boolean runningHeader,
                       @RequestParam(defaultValue = "false") boolean pageNumbers,
                       @RequestParam(defaultValue = "false") boolean llmAbstract,
                       @RequestParam(defaultValue = "false") boolean llmKeywords,
                       @RequestParam(defaultValue = "6") int maxKeywords,
                       @RequestParam Map<String, String> params,
                       RedirectAttributes redirect) {
        Map<String, String> names = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (name.startsWith(NAME_PREFIX)) {
                names.put(name.substring(NAME_PREFIX.length()), value);
            }
        });
        try {
            service.save(defaultFormat, defaultMode, paperSize, marginMm, issn, runningHeader, pageNumbers,
                    llmAbstract, llmKeywords, maxKeywords, names);
            redirect.addFlashAttribute("message", text.msg("pdf.flash.saved"));
        } catch (MgmtOperationException e) {
            redirect.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/pdf";
    }
}
