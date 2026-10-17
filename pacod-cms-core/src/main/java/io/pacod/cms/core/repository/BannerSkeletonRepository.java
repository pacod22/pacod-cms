package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.BannerSkeleton;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Two seeded documents and whatever an operator adds. Read whole, for the reason
 * {@link BannerLayoutRepository} gives.
 */
public interface BannerSkeletonRepository extends MongoRepository<BannerSkeleton, String> {
}
