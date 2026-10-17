package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.SiteSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The website's single {@link SiteSettings} document, id {@link SiteSettings#ID}. */
public interface SiteSettingsRepository extends MongoRepository<SiteSettings, String> {
}
