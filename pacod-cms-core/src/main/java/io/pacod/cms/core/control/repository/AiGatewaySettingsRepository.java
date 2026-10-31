package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.AiGatewaySettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AiGatewaySettingsRepository extends MongoRepository<AiGatewaySettings, String> {
}
