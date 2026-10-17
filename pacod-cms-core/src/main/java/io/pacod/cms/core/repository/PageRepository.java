package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Page;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * The standing pages.
 *
 * <p>A handful of documents, so there is no paging and no search finder here: {@code PageReader}
 * caches {@code findAll()} under one key and both the site and the console filter in the JVM. Same
 * reasoning as {@code BannerRepository}, which deliberately carries almost nothing for the same
 * reason -- a repository query would add a round trip and remove no work.
 */
public interface PageRepository extends MongoRepository<Page, String> {

    List<Page> findByPublishedTrueOrderBySortOrderAscSlugAsc();
}
