package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.PdfEnrichment;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * {@link PdfEnrichment} rows, by {@link PdfEnrichment#key}. Written by the studio's PDF jobs, read
 * by the site's print view; an article's rows go when the article does.
 */
public interface PdfEnrichmentRepository extends MongoRepository<PdfEnrichment, String> {

    void deleteByArticleId(Integer articleId);
}
