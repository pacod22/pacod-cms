package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.PdfSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

/** A website's one {@link PdfSettings} document; the id is the constant {@link PdfSettings#ID}. */
public interface PdfSettingsRepository extends MongoRepository<PdfSettings, String> {
}
