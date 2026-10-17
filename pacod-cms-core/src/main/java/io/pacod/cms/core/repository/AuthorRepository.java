package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.Author;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AuthorRepository extends MongoRepository<Author, Integer> {
    @Query(value="{}", fields="{ '_id' : 1}", sort = "{ _id : -1 }")
    List<Author> getMaxId(Pageable pageable);
}
