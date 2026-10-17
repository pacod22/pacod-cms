package io.pacod.cms.core.control.repository;

import io.pacod.cms.core.control.data.DeletedWebsite;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeletedWebsiteRepository extends MongoRepository<DeletedWebsite, String> {
}
