package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.BannerLayout;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * No custom finders on purpose.
 *
 * <p>The collection holds one document per HTML5 creative the site has ever run -- tens, not
 * thousands -- and the console's list screen reads it whole, sorts it in the JVM and filters it
 * through {@code MgmtSearch}. That is the same trade {@code MgmtBannerService}, {@code MgmtPageService}
 * and the author and category lists already make: a derived query would add a round trip and remove
 * no work. The id is the folder name, so {@code findById} covers every other access.
 */
public interface BannerLayoutRepository extends MongoRepository<BannerLayout, String> {
}
