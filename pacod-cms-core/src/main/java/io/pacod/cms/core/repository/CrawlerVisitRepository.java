package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.CrawlerVisit;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * The crawler visit log.
 *
 * <p>Only the two lookups below go through it. The public site writes through
 * {@code MongoTemplate} in batches; the console's filtered log and aggregations go through
 * {@code MongoTemplate} too, since no derived query expresses an optional filter.
 */
public interface CrawlerVisitRepository extends MongoRepository<CrawlerVisit, String> {

    /**
     * The newest visit by one bot that was not shown to be forged. Derived {@code findFirst}
     * applies a limit of one, unlike an {@code Optional} on a {@code @Query}.
     */
    Optional<CrawlerVisit> findFirstByBotAndVerificationNotOrderByOccurredAtDesc(String bot,
                                                                               CrawlerVerification verification);

    /** The oldest row still retained: how far back "not seen" can honestly reach. */
    Optional<CrawlerVisit> findFirstByOrderByOccurredAtAsc();
}
