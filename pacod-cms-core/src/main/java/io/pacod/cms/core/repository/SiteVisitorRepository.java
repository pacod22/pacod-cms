package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.SiteVisitor;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;

/**
 * The visitor heartbeat rows.
 *
 * <p>Deliberately carries no "find the visitors" finder. The two things anyone asks of this
 * collection are counts -- how many rows exist for a day, and how many distinct hashes were seen
 * in the last few minutes -- and the second is a {@code distinct}, which no derived query
 * expresses. Both go through {@code MongoTemplate}; a finder here would only invite someone to
 * load the hashes themselves, which is the one thing this collection exists not to hand out.
 */
public interface SiteVisitorRepository extends MongoRepository<SiteVisitor, String> {

    long countByDay(LocalDateTime day);
}
