package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Banner;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface BannerRepository extends MongoRepository<Banner, Integer> {

    /** Reads a single document off the index; same shape as {@code AuthorRepository.getMaxId}. */
    @Query(value = "{}", fields = "{ '_id' : 1}", sort = "{ _id : -1 }")
    List<Banner> getMaxId(Pageable pageable);
}
