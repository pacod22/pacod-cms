package io.pacod.cms.core.repository;

import io.pacod.cms.core.design.Theme;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ThemeRepository extends MongoRepository<Theme, String> {
}
