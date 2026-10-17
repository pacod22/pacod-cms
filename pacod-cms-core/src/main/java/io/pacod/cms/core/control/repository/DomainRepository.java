package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.Domain;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DomainRepository extends MongoRepository<Domain, String> {

    List<Domain> findByWebsiteId(String websiteId);
}
