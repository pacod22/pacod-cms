package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.AiModelSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AiModelSettingsRepository extends MongoRepository<AiModelSettings, String> {
}
