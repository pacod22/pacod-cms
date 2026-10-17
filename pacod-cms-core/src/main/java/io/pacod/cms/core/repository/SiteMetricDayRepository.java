package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.SiteMetricDay;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/** Reads the daily traffic buckets. Read-only for the same reason as {@link SiteMetricHourRepository}. */
public interface SiteMetricDayRepository extends MongoRepository<SiteMetricDay, String> {

    /** Inclusive at both ends. See {@link SiteMetricHourRepository#findInWindow} for why it is hand-written. */
    @Query(value = "{ 'start': { $gte: ?0, $lte: ?1 } }", sort = "{ 'start': 1 }")
    List<SiteMetricDay> findInWindow(LocalDateTime from, LocalDateTime to);
}
