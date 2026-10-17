package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Category;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CategoryRepository extends MongoRepository<Category, Integer> {
}
