package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.SiteMetricHour;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads the hourly traffic buckets. There is no write method here on purpose: buckets are written
 * only by {@code SiteMetricCollector}'s buffered {@code $inc}, and a {@code save()} of a whole
 * document would restore whatever was read at the start of the request -- the failure
 * {@code BannerForm} keeps {@code impressions} out of itself to avoid.
 */
public interface SiteMetricHourRepository extends MongoRepository<SiteMetricHour, String> {

    /**
     * The buckets in a closed window, <b>both ends included</b>.
     *
     * <p>Hand-written rather than derived, and neither spelling of the derived form works:
     * <ul>
     *   <li>{@code findByStartBetween} is <b>exclusive at both ends</b> on a Mongo repository --
     *       Spring Data renders it as {@code $gt}/{@code $lt}, not the inclusive range JPA's
     *       {@code Between} gives. The console asks for the window ending at the bucket open right
     *       now, so that form silently drops the newest bucket: traffic is recorded correctly and
     *       the chart reads zero for the hour anyone looks at first.</li>
     *   <li>{@code findByStartGreaterThanEqualAndStartLessThanEqual} says the right thing and then
     *       throws at query time -- <i>"you can't add a second 'start' expression"</i>. A derived
     *       query builds one {@code Criteria} per clause and {@code org.bson.Document} cannot hold
     *       the same key twice.</li>
     * </ul>
     * One document with both operators is legal, so that is what this sends. Both failures are
     * worth knowing before anyone "simplifies" it back into a method name.
     */
    @Query(value = "{ 'start': { $gte: ?0, $lte: ?1 } }", sort = "{ 'start': 1 }")
    List<SiteMetricHour> findInWindow(LocalDateTime from, LocalDateTime to);
}
