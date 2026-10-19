package io.pacod.cms.studio;

import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.PaperSize;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.data.TextItems;
import io.pacod.cms.core.repository.PdfSettingsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /mgmt/pdf}: a website's {@link PdfSettings}. Saved whole -- no job writes the document,
 * and the form carries every field -- after each value is checked here, where a crafted POST
 * meets the same rules as the form.
 */
@Service
public class MgmtPdfSettingsService {

    /** A text field per language: the original first, then the website's other languages. */
    public record LanguageField(String code, String name, String value) {
    }

    private final PdfSettingsRepository repository;
    private final MgmtLanguageService languages;

    public MgmtPdfSettingsService(PdfSettingsRepository repository, MgmtLanguageService languages) {
        this.repository = repository;
        this.languages = languages;
    }

    public PdfSettings load() {
        return repository.findById(PdfSettings.ID).orElseGet(PdfSettings::new);
    }

    /** One field per language the website offers, its original first, each with its stored text. */
    public List<LanguageField> fields(List<TextItem> items) {
        String original = LanguageChain.original();
        List<Language> offered = languages.findAll();
        List<LanguageField> fields = new ArrayList<>();
        fields.add(new LanguageField(original, offered.stream().filter(l -> original.equals(l.getCode()))
                .map(Language::getAdminName).findFirst().orElse(original), TextItems.find(items, original)));
        for (Language language : offered) {
            if (language.getCode() != null && !language.getCode().equals(original)) {
                fields.add(new LanguageField(language.getCode(), language.getAdminName(),
                        TextItems.find(items, language.getCode())));
            }
        }
        return fields;
    }

    /** The codes a per-language field may be posted for: what {@link #fields} offers. */
    public List<String> offeredCodes() {
        return fields(List.of()).stream().map(LanguageField::code).toList();
    }

    /**
     * Applies the posted values; per-language names come as {@code <prefix><code>} and only for
     * the languages the website offers, blank removing that language's.
     */
    public PdfSettings save(String format, String mode, String paper, int marginMm, String issn,
                            boolean runningHeader, boolean pageNumbers, boolean llmAbstract, boolean llmKeywords,
                            int maxKeywords, Map<String, String> journalNames) {
        if (marginMm < PdfSettings.MIN_MARGIN_MM || marginMm > PdfSettings.MAX_MARGIN_MM) {
            throw new MgmtOperationException("pdf.error.margin", PdfSettings.MIN_MARGIN_MM, PdfSettings.MAX_MARGIN_MM);
        }
        if (maxKeywords < PdfSettings.MIN_KEYWORDS || maxKeywords > PdfSettings.MAX_KEYWORDS) {
            throw new MgmtOperationException("pdf.error.keywords", PdfSettings.MIN_KEYWORDS, PdfSettings.MAX_KEYWORDS);
        }
        String cleanIssn = issn == null ? "" : issn.strip().toUpperCase();
        if (!cleanIssn.isEmpty() && !PdfSettings.ISSN.matcher(cleanIssn).matches()) {
            throw new MgmtOperationException("pdf.error.issn");
        }
        PdfSettings settings = load();
        settings.setDefaultFormat(PdfFormat.parse(format, settings.format()));
        settings.setDefaultMode(PdfMode.parse(mode, settings.mode()));
        settings.setPaperSize(paper(paper, settings.paper()));
        settings.setMarginMm(marginMm);
        settings.setIssn(cleanIssn.isEmpty() ? null : cleanIssn);
        settings.setRunningHeader(runningHeader);
        settings.setPageNumbers(pageNumbers);
        settings.setLlmAbstract(llmAbstract);
        settings.setLlmKeywords(llmKeywords);
        settings.setMaxKeywords(maxKeywords);
        List<TextItem> names = settings.getJournalNames();
        for (String code : offeredCodes()) {
            if (journalNames.containsKey(code)) {
                String name = journalNames.get(code);
                if (name != null && name.strip().length() > 120) {
                    throw new MgmtOperationException("pdf.error.journalName");
                }
                names = TextItems.put(names, code, name);
            }
        }
        settings.setJournalNames(names);
        settings.setLastModifiedOn(LocalDateTime.now());
        return repository.save(settings);
    }

    private static PaperSize paper(String value, PaperSize fallback) {
        for (PaperSize size : PaperSize.values()) {
            if (size.name().equalsIgnoreCase(value == null ? "" : value.strip())) {
                return size;
            }
        }
        return fallback;
    }
}
