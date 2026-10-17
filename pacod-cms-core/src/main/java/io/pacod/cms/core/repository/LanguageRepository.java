package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Language;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * The languages the standing pages can be written in.
 *
 * <p>A handful of documents, read on every public page render and on every console screen that
 * shows a tab strip, so the ordering is done in the query rather than in each caller -- there is
 * exactly one order these are ever wanted in.
 *
 * <p>Sorted by {@code sortOrder} then {@code code}, and the tie-break matters: {@code sortOrder}
 * defaults to 0, so a language added without one would otherwise take an arbitrary place in the
 * public switcher that changes between reads.
 */
public interface LanguageRepository extends MongoRepository<Language, String> {

    List<Language> findAllByOrderBySortOrderAscCodeAsc();
}
