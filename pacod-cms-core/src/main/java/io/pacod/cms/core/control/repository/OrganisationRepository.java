package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.Organisation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrganisationRepository extends MongoRepository<Organisation, String> {

    List<Organisation> findAllByOrderByNameAsc();

    long countByCreatedBy(String createdBy);
}
