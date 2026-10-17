package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WebsiteRepository extends MongoRepository<Website, String> {

    List<Website> findByOrganisationIdOrderByNameAsc(String organisationId);

    List<Website> findByStatus(WebsiteStatus status);

    List<Website> findAllByOrderByNameAsc();

    long countByOrganisationId(String organisationId);
}
