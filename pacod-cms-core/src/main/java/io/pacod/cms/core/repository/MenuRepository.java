package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Menu;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Navigation trees, keyed by {@link Menu#MAIN} / {@link Menu#FOOTER}. */
public interface MenuRepository extends MongoRepository<Menu, String> {
}
