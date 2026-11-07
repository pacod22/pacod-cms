package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.BrokerSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BrokerSettingsRepository extends MongoRepository<BrokerSettings, String> {
}
