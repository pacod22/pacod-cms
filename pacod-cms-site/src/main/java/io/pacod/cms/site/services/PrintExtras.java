package io.pacod.cms.site.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.PdfEnrichment;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.repository.PdfEnrichmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The front matter a print view shows above an article's body: an abstract and keywords.
 *
 * <p>In {@link PdfMode#CODE} they are the article's own description in the requested language
 * (exactly that language -- never {@code ArticleModel.getDescription()}, which makes a line up out
 * of the date, the authors and the title) and its tags, translated. In {@link PdfMode#LLM} they are
 * the gateway's {@link PdfEnrichment}, but only while it was written from the article's current
 * title and body in that language: an edited article falls back to its own description rather than
 * printing an abstract of its old text.
 *
 * <p>Computed here and handed to the template whole, because restricted-mode SpEL can neither
 * construct nor call statics. Never throws: a database that cannot be read prints the code mode.
 */
@Slf4j
@Service
public class PrintExtras {

    /** What a print view shows above the body; either part may be empty. */
    public record PrintMeta(String abstractText, List<String> keywords) {

        public boolean hasAbstract() {
            return abstractText != null && !abstractText.isBlank();
        }

        public boolean hasKeywords() {
            return keywords != null && !keywords.isEmpty();
        }
    }

    private final PdfEnrichmentRepository enrichments;

    public PrintExtras(PdfEnrichmentRepository enrichments) {
        this.enrichments = enrichments;
    }

    public PrintMeta meta(Article article, String lang, PdfMode mode, List<Tag> allTags) {
        PrintMeta own = own(article, lang, allTags);
        if (mode != PdfMode.LLM || article.getId() == null || lang == null || !lang.matches("[a-z]{2,3}")) {
            return own;
        }
        return enrichment(article, lang)
                .map(row -> new PrintMeta(
                        row.getAbstractText() == null || row.getAbstractText().isBlank() ? own.abstractText() : row.getAbstractText(),
                        row.getKeywords().isEmpty() ? own.keywords() : row.getKeywords()))
                .orElse(own);
    }

    private Optional<PdfEnrichment> enrichment(Article article, String lang) {
        try {
            return enrichments.findById(PdfEnrichment.key(article.getId(), lang))
                    .filter(row -> row.matches(article.titleIn(lang), article.textIn(lang)));
        } catch (RuntimeException e) {
            log.error("Cannot read the PDF enrichment of article {} ({}); printing its own front matter: {}",
                    article.getId(), lang, e.getMessage());
            return Optional.empty();
        }
    }

    private static PrintMeta own(Article article, String lang, List<Tag> allTags) {
        List<String> keywords = new ArrayList<>();
        for (String name : article.getTags() == null ? List.<String>of() : article.getTags()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String label = allTags == null ? name : allTags.stream()
                    .filter(t -> name.equals(t.getName())).findFirst()
                    .map(t -> t.translate(lang)).orElse(name);
            if (!keywords.contains(label)) {
                keywords.add(label);
            }
        }
        return new PrintMeta(article.descriptionIn(lang), keywords);
    }
}
