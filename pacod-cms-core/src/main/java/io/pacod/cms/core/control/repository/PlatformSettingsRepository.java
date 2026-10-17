package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.PlatformSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlatformSettingsRepository extends MongoRepository<PlatformSettings, String> {
}
