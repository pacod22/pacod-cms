package io.pacod.cms.core.repository;

import io.pacod.cms.core.design.Layout;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LayoutRepository extends MongoRepository<Layout, String> {
}
