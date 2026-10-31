package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.SiteFiles;
import org.springframework.data.mongodb.repository.MongoRepository;

/** A website's one {@link SiteFiles} document; the id is the constant {@link SiteFiles#ID}. */
public interface SiteFilesRepository extends MongoRepository<SiteFiles, String> {
}
