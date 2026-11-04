package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.BrokerCredential;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BrokerCredentialRepository extends MongoRepository<BrokerCredential, String> {
}
